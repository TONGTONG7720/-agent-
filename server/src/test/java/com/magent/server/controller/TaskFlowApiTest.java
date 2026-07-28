package com.magent.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magent.server.service.AgentClient;
import com.magent.server.service.AgentStreamRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskFlowApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private AgentStreamRelay relay;
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

    private long createProjectAndTask() throws Exception {
        MvcResult pr = mvc.perform(post("/api/projects").header("satoken", token)
                        .contentType(APPLICATION_JSON).content("{\"name\":\"演示项目\"}"))
                .andExpect(status().isOk()).andReturn();
        long projectId = om.readTree(pr.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        MvcResult tr = mvc.perform(post("/api/tasks").header("satoken", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"projectId\":" + projectId + ",\"requirement\":\"计算器\",\"autoMode\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("running")).andReturn();
        return om.readTree(tr.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    @Test
    void projectListContainsCreated() throws Exception {
        createProjectAndTask();
        MvcResult r = mvc.perform(get("/api/projects").header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("演示项目");
    }

    @Test
    void eventsReplayAfterSeq() throws Exception {
        long taskId = createProjectAndTask();
        relay.handleLine(taskId, "data:{\"event\":\"agent_message\",\"seq\":1,\"agent\":\"pm\",\"data\":{\"content\":\"PRD\"},\"ts\":1}");
        relay.handleLine(taskId, "data:{\"event\":\"node_end\",\"seq\":2,\"agent\":\"pm\",\"data\":{},\"ts\":2}");
        MvcResult r = mvc.perform(get("/api/tasks/" + taskId + "/events?afterSeq=1")
                        .header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = om.readTree(r.getResponse().getContentAsString()).path("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("seq").asInt()).isEqualTo(2);
    }

    @Test
    void approveResumesAgentOnlyWhenWaitingReview() throws Exception {
        long taskId = createProjectAndTask();
        // 非 waiting_review 审批 → 409
        mvc.perform(post("/api/tasks/" + taskId + "/approve").header("satoken", token)
                        .contentType(APPLICATION_JSON).content("{\"decision\":\"pass\",\"comment\":\"\"}"))
                .andExpect(status().isConflict());
        // 进入人审后审批 → resume 被调用，状态回 running
        relay.handleLine(taskId, "data:{\"event\":\"interrupt\",\"seq\":3,\"data\":{\"gate\":\"prd_gate\"},\"ts\":3}");
        mvc.perform(post("/api/tasks/" + taskId + "/approve").header("satoken", token)
                        .contentType(APPLICATION_JSON).content("{\"decision\":\"pass\",\"comment\":\"ok\"}"))
                .andExpect(status().isOk());
        verify(agentClient).resume("T" + taskId, "pass", "ok");
        mvc.perform(get("/api/tasks/" + taskId).header("satoken", token))
                .andExpect(jsonPath("$.data.status").value("running"));
    }

    @Test
    void cancelSetsCanceled() throws Exception {
        long taskId = createProjectAndTask();
        mvc.perform(post("/api/tasks/" + taskId + "/cancel").header("satoken", token))
                .andExpect(status().isOk());
        verify(agentClient).cancel("T" + taskId);
        mvc.perform(get("/api/tasks/" + taskId).header("satoken", token))
                .andExpect(jsonPath("$.data.status").value("canceled"));
    }

    @Test
    void streamEndpointStartsAsyncSse() throws Exception {
        long taskId = createProjectAndTask();
        MvcResult r = mvc.perform(get("/api/tasks/" + taskId + "/stream")
                        .header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        // SseEmitter 异步响应：MockMvc 下断言异步已启动即订阅成功
        assertThat(r.getRequest().isAsyncStarted()).isTrue();
    }
}
