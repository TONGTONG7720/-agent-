package com.magent.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.magent.server.entity.Task;
import com.magent.server.mapper.TaskMapper;
import com.magent.server.service.AgentClient;
import com.magent.server.service.TaskEventService;
import com.magent.server.service.TaskService;
import com.magent.server.entity.TaskEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 批次2：多轮迭代 + 审批定向回退透传。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class B2FeatureApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskEventService eventService;
    @MockBean
    private AgentClient agentClient;

    private String token;

    @BeforeEach
    void loginAdmin() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
        token = om.readTree(r.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    private Task doneTask() {
        Task t = taskService.create(1L, "计算器", true, 1L);
        taskService.markDone(t.getId());
        return t;
    }

    @Test
    void iterateDoneTaskCallsAgentWithFeedbackAndMaxSeq() throws Exception {
        Task t = doneTask();
        TaskEvent e = new TaskEvent();
        e.setTaskId(t.getId());
        e.setSeq(7);
        e.setEvent("task_done");
        e.setData("{}");
        e.setTs(1L);
        eventService.save(e);

        mvc.perform(post("/api/tasks/" + t.getId() + "/iterate").header("satoken", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"feedback\":\"把除法改成返回分数\"}"))
                .andExpect(status().isOk());
        verify(agentClient).iterate("T" + t.getId(), "把除法改成返回分数", 7);
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("running");
    }

    @Test
    void iterateNonDoneTaskIs409() throws Exception {
        Task t = taskService.create(1L, "计算器", true, 1L);   // running
        mvc.perform(post("/api/tasks/" + t.getId() + "/iterate").header("satoken", token)
                        .contentType(APPLICATION_JSON).content("{\"feedback\":\"x\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void approveRejectForwardsTarget() throws Exception {
        Task t = taskService.create(1L, "计算器", false, 1L);
        taskService.markWaitingReview(t.getId(), "accept_gate");
        mvc.perform(post("/api/tasks/" + t.getId() + "/approve").header("satoken", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"decision\":\"reject\",\"comment\":\"架构不行\",\"target\":\"architect\"}"))
                .andExpect(status().isOk());
        verify(agentClient).resume("T" + t.getId(), "reject", "架构不行", "architect");
    }

    @Test
    void pushDoneTaskReturnsBranch() throws Exception {
        Task t = doneTask();
        when(agentClient.push("T" + t.getId(), "https://github.com/u/r.git", "tok"))
                .thenReturn("magent/T" + t.getId() + "-x");
        mvc.perform(post("/api/tasks/" + t.getId() + "/push").header("satoken", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/u/r.git\",\"token\":\"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.branch").value("magent/T" + t.getId() + "-x"));
        // 推送不改变任务状态
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("done");
    }

    @Test
    void pushNonDoneTaskIs409() throws Exception {
        Task t = taskService.create(1L, "计算器", true, 1L);   // running
        mvc.perform(post("/api/tasks/" + t.getId() + "/push").header("satoken", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"repoUrl\":\"https://github.com/u/r.git\"}"))
                .andExpect(status().isConflict());
    }
}
