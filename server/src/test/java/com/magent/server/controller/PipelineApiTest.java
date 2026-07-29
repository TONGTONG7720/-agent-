package com.magent.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magent.server.entity.Task;
import com.magent.server.mapper.TaskMapper;
import com.magent.server.service.AgentClient;
import com.magent.server.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** B4-2c：流水线（自定义角色）CRUD + 建任务时组装 spec。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional   // 逐用例回滚，避免新增角色污染共享 H2 库
class PipelineApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskMapper taskMapper;
    @MockBean
    private AgentClient agentClient;

    private String token;

    @BeforeEach
    void loginAdmin() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
        token = om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    @Test
    void defaultPipelineHasFiveOrderedSteps() throws Exception {
        MvcResult r = mvc.perform(get("/api/pipeline").header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        JsonNode steps = om.readTree(r.getResponse().getContentAsString()).path("data");
        assertThat(steps).hasSize(5);
        assertThat(steps.get(0).path("role").asText()).isEqualTo("pm");
        assertThat(steps.get(0).path("kind").asText()).isEqualTo("analysis");
        assertThat(steps.get(0).path("hasGate").asBoolean()).isTrue();
        assertThat(steps.get(4).path("role").asText()).isEqualTo("reviewer");
        assertThat(steps.get(4).path("kind").asText()).isEqualTo("review");
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultTaskPassesNoPipelineToAgent() throws Exception {
        Task t = taskService.create(1L, "计算器", true, 1L);
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(agentClient).startTask(eq("T" + t.getId()), anyString(), anyBoolean(),
                anyMap(), anyMap(), cap.capture(), any());
        assertThat(cap.getValue()).isNull();   // 与默认一致 → 不传 pipeline，走旧图
    }

    @Test
    @SuppressWarnings("unchecked")
    void customizedPipelinePassesSpecToAgent() throws Exception {
        // 新增一个自定义分析角色 security
        mvc.perform(post("/api/pipeline/roles").header("satoken", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"role\":\"security\",\"name\":\"安全审计员\",\"kind\":\"analysis\",\"hasGate\":false}"))
                .andExpect(status().isOk());

        Task t = taskService.create(1L, "计算器", true, 1L);
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(agentClient).startTask(eq("T" + t.getId()), anyString(), anyBoolean(),
                anyMap(), anyMap(), cap.capture(), any());
        Map<String, Object> spec = cap.getValue();
        assertThat(spec).isNotNull();
        var steps = (java.util.List<Map<String, Object>>) spec.get("steps");
        assertThat(steps).anyMatch(s -> "security".equals(s.get("key")));
    }

    @Test
    void addThenDeleteRole() throws Exception {
        mvc.perform(post("/api/pipeline/roles").header("satoken", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"role\":\"doc\",\"name\":\"文档\",\"kind\":\"analysis\",\"hasGate\":false}"))
                .andExpect(status().isOk());
        MvcResult r = mvc.perform(get("/api/pipeline").header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        JsonNode steps = om.readTree(r.getResponse().getContentAsString()).path("data");
        long docId = -1;
        for (JsonNode s : steps) {
            if ("doc".equals(s.path("role").asText())) docId = s.path("id").asLong();
        }
        assertThat(docId).isGreaterThan(0);
        mvc.perform(post("/api/pipeline/roles/" + docId + "/delete").header("satoken", token))
                .andExpect(status().isOk());
    }
}
