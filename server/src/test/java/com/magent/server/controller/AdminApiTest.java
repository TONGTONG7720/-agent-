package com.magent.server.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.config.AppProps;
import com.magent.server.entity.Artifact;
import com.magent.server.entity.LlmModel;
import com.magent.server.entity.Task;
import com.magent.server.mapper.ArtifactMapper;
import com.magent.server.mapper.LlmModelMapper;
import com.magent.server.service.AgentClient;
import com.magent.server.service.TaskService;
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
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private AppProps props;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ArtifactMapper artifactMapper;
    @Autowired
    private LlmModelMapper modelMapper;
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

    @Test
    void artifactListAndDownload() throws Exception {
        Task t = taskService.create(1L, "计算器", true, 1L);
        // 在工作目录放一个真实文件
        Path dir = Path.of(props.getWorkspaceRoot(), "T" + t.getId());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("PRD.md"), "# PRD 内容", StandardCharsets.UTF_8);
        Artifact a = new Artifact();
        a.setTaskId(t.getId());
        a.setName("PRD.md");
        a.setType("prd");
        a.setPath("PRD.md");
        a.setVersion(1);
        artifactMapper.insert(a);

        mvc.perform(get("/api/tasks/" + t.getId() + "/artifacts").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("PRD.md"));

        MvcResult r = mvc.perform(get("/api/artifacts/" + a.getId() + "/download")
                        .header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("PRD 内容");
    }

    @Test
    void downloadRejectsPathEscape() throws Exception {
        Task t = taskService.create(1L, "计算器", true, 1L);
        Artifact a = new Artifact();
        a.setTaskId(t.getId());
        a.setName("evil");
        a.setType("code");
        a.setPath("../../pom.xml");     // 逃逸路径
        a.setVersion(1);
        artifactMapper.insert(a);
        mvc.perform(get("/api/artifacts/" + a.getId() + "/download").header("satoken", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void modelKeyIsEncryptedAndMasked() throws Exception {
        mvc.perform(post("/api/models").header("satoken", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"通义\",\"litellmModelName\":\"qwen-plus\",\"apiKey\":\"sk-abcdef1234\"}"))
                .andExpect(status().isOk());
        // 落库为密文（按名字精确取刚建的模型，避免受种子模型干扰）
        LlmModel created = modelMapper.selectOne(
                new QueryWrapper<LlmModel>().eq("name", "通义"));
        assertThat(created.getApiKeyEnc()).isNotBlank().doesNotContain("sk-abcdef1234");
        // 列表返回脱敏，绝无明文与密文字段
        MvcResult r = mvc.perform(get("/api/models").header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("sk-***1234").doesNotContain("sk-abcdef1234").doesNotContain("apiKeyEnc");
    }

    @Test
    void roleConfigUpdate() throws Exception {
        mvc.perform(put("/api/role-configs/coder").header("satoken", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"systemPrompt\":\"新提示词\",\"defaultModelId\":null}"))
                .andExpect(status().isOk());
        MvcResult r = mvc.perform(get("/api/role-configs").header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        boolean found = false;
        for (JsonNode n : data) {
            if ("coder".equals(n.path("role").asText())) {
                assertThat(n.path("systemPrompt").asText()).isEqualTo("新提示词");
                found = true;
            }
        }
        assertThat(found).isTrue();
    }
}
