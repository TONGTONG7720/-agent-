package com.magent.server.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.magent.server.config.AppProps;
import com.magent.server.entity.Artifact;
import com.magent.server.entity.Task;
import com.magent.server.entity.TaskEvent;
import com.magent.server.mapper.ArtifactMapper;
import com.magent.server.mapper.TaskMapper;
import com.magent.server.service.AgentClient;
import com.magent.server.service.TaskEventService;
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
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 批次1功能：断点重试 / 产物内容预览 / 任务zip打包。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class B1FeatureApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private AppProps props;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskEventService eventService;
    @Autowired
    private ArtifactMapper artifactMapper;
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

    private Task newTask() {
        return taskService.create(1L, "计算器", true, 1L);
    }

    private TaskEvent ev(long taskId, int seq) {
        TaskEvent e = new TaskEvent();
        e.setTaskId(taskId);
        e.setSeq(seq);
        e.setEvent("agent_message");
        e.setAgent("pm");
        e.setData("{}");
        e.setTs(1L);
        return e;
    }

    @Test
    void retryFailedTaskPassesMaxSeqAndSetsRunning() throws Exception {
        Task t = newTask();
        eventService.save(ev(t.getId(), 1));
        eventService.save(ev(t.getId(), 2));
        taskService.markFailed(t.getId(), "boom");

        mvc.perform(post("/api/tasks/" + t.getId() + "/retry").header("satoken", token))
                .andExpect(status().isOk());
        verify(agentClient).retry("T" + t.getId(), 2);
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("running");
    }

    @Test
    void retryNonFailedTaskIs409() throws Exception {
        Task t = newTask();   // running
        mvc.perform(post("/api/tasks/" + t.getId() + "/retry").header("satoken", token))
                .andExpect(status().isConflict());
    }

    @Test
    void artifactContentPreview() throws Exception {
        Task t = newTask();
        Path dir = Path.of(props.getWorkspaceRoot(), "T" + t.getId());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("PRD.md"), "# 你好 PRD", StandardCharsets.UTF_8);
        Artifact a = new Artifact();
        a.setTaskId(t.getId());
        a.setName("PRD.md");
        a.setType("prd");
        a.setPath("PRD.md");
        a.setVersion(1);
        artifactMapper.insert(a);

        mvc.perform(get("/api/artifacts/" + a.getId() + "/content").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("# 你好 PRD"));
    }

    @Test
    void artifactContentRejectsEscapeAndHuge() throws Exception {
        Task t = newTask();
        Path dir = Path.of(props.getWorkspaceRoot(), "T" + t.getId());
        Files.createDirectories(dir);
        // 逃逸
        Artifact evil = new Artifact();
        evil.setTaskId(t.getId());
        evil.setName("evil");
        evil.setType("code");
        evil.setPath("../../pom.xml");
        evil.setVersion(1);
        artifactMapper.insert(evil);
        mvc.perform(get("/api/artifacts/" + evil.getId() + "/content").header("satoken", token))
                .andExpect(status().isBadRequest());
        // 超大文件
        Files.writeString(dir.resolve("big.txt"), "x".repeat(1024 * 1024 + 10));
        Artifact big = new Artifact();
        big.setTaskId(t.getId());
        big.setName("big.txt");
        big.setType("code");
        big.setPath("big.txt");
        big.setVersion(1);
        artifactMapper.insert(big);
        mvc.perform(get("/api/artifacts/" + big.getId() + "/content").header("satoken", token))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void zipDownloadContainsFiles() throws Exception {
        Task t = newTask();
        Path dir = Path.of(props.getWorkspaceRoot(), "T" + t.getId(), "src");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.py"), "X = 1");

        MvcResult r = mvc.perform(get("/api/tasks/" + t.getId() + "/artifacts/zip")
                        .header("satoken", token))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains(".zip");
        assertThat(r.getResponse().getContentAsByteArray().length).isGreaterThan(50);
    }

    @Test
    void zipMissingWorkspaceIs404() throws Exception {
        Task t = newTask();   // 不创建工作目录
        // 确保目录不存在
        Path dir = Path.of(props.getWorkspaceRoot(), "T" + t.getId());
        if (Files.exists(dir)) {
            return;   // 其他用例可能已建过同 id 目录，跳过（自增id一般不会）
        }
        mvc.perform(get("/api/tasks/" + t.getId() + "/artifacts/zip").header("satoken", token))
                .andExpect(status().isNotFound());
    }
}
