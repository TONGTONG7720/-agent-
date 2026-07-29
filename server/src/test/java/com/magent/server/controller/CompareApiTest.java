package com.magent.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magent.server.entity.LlmModel;
import com.magent.server.entity.Task;
import com.magent.server.mapper.LlmModelMapper;
import com.magent.server.mapper.TaskMapper;
import com.magent.server.service.AgentClient;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** B4-4a：多模型对比——同需求双任务、各自模型覆盖、自动模式。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CompareApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private LlmModelMapper modelMapper;
    @Autowired
    private TaskMapper taskMapper;
    @MockBean
    private AgentClient agentClient;

    private String token;
    private Long modelAId;
    private Long modelBId;

    @BeforeEach
    void setup() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
        token = om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asText();
        modelAId = ensureModel("模型甲", "gpt-a");
        modelBId = ensureModel("模型乙", "gpt-b");
    }

    private Long ensureModel(String name, String litellm) {
        LlmModel m = new LlmModel();
        m.setName(name);
        m.setLitellmModelName(litellm);
        m.setEnabled(true);
        modelMapper.insert(m);
        return m.getId();
    }

    @Test
    @SuppressWarnings("unchecked")
    void compareCreatesTwoTasksWithModelOverrides() throws Exception {
        MvcResult r = mvc.perform(post("/api/tasks/compare").header("satoken", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"projectId\":1,\"requirement\":\"写计算器\",\"modelAId\":" + modelAId
                                + ",\"modelBId\":" + modelBId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskAId").isNumber())
                .andExpect(jsonPath("$.data.taskBId").isNumber())
                .andReturn();
        JsonNode data = om.readTree(r.getResponse().getContentAsString()).path("data");
        long idA = data.path("taskAId").asLong();
        long idB = data.path("taskBId").asLong();
        assertThat(idA).isNotEqualTo(idB);

        // 两个任务都以 auto_mode=true 启动，且各角色模型分别被覆盖为 gpt-a / gpt-b
        ArgumentCaptor<Map<String, String>> models = ArgumentCaptor.forClass(Map.class);
        verify(agentClient).startTask(eq("T" + idA), eq("写计算器"), eq(true),
                models.capture(), anyMap(), any(), any());
        assertThat(models.getValue().values()).containsOnly("gpt-a");

        ArgumentCaptor<Map<String, String>> modelsB = ArgumentCaptor.forClass(Map.class);
        verify(agentClient).startTask(eq("T" + idB), eq("写计算器"), eq(true),
                modelsB.capture(), anyMap(), any(), any());
        assertThat(modelsB.getValue().values()).containsOnly("gpt-b");

        assertThat(taskMapper.selectById(idA).getAutoMode()).isTrue();
    }
}
