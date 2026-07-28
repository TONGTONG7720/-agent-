package com.magent.server.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.entity.Artifact;
import com.magent.server.entity.Task;
import com.magent.server.mapper.ArtifactMapper;
import com.magent.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentStreamRelayTest {

    @Autowired
    private AgentStreamRelay relay;
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
    @MockBean
    private SseRegistry sseRegistry;

    private Long taskId;

    @BeforeEach
    void setup() {
        Task t = taskService.create(1L, "计算器", true, 1L);
        taskId = t.getId();
    }

    @Test
    void dataLineIsPersistedAndBroadcast() {
        relay.handleLine(taskId,
                "data:{\"event\":\"agent_message\",\"task_id\":\"T" + taskId
                        + "\",\"agent\":\"pm\",\"seq\":1,\"data\":{\"content\":\"PRD\"},\"ts\":123}");
        assertThat(eventService.listAfter(taskId, 0)).hasSize(1);
        verify(sseRegistry).broadcast(eq(taskId), any());
    }

    @Test
    void interruptMovesTaskToWaitingReview() {
        relay.handleLine(taskId,
                "data:{\"event\":\"interrupt\",\"task_id\":\"T" + taskId
                        + "\",\"seq\":2,\"data\":{\"gate\":\"prd_gate\",\"question\":\"请确认\"},\"ts\":123}");
        Task t = taskMapper.selectById(taskId);
        assertThat(t.getStatus()).isEqualTo("waiting_review");
        assertThat(t.getCurrentNode()).isEqualTo("prd_gate");
    }

    @Test
    void taskDoneAndArtifactArePersisted() {
        relay.handleLine(taskId,
                "data:{\"event\":\"artifact_created\",\"task_id\":\"T" + taskId
                        + "\",\"agent\":\"pm\",\"seq\":3,\"data\":{\"name\":\"PRD.md\",\"type\":\"prd\",\"path\":\"PRD.md\"},\"ts\":1}");
        relay.handleLine(taskId,
                "data:{\"event\":\"task_done\",\"task_id\":\"T" + taskId
                        + "\",\"seq\":4,\"data\":{\"review_passed\":true},\"ts\":2}");
        assertThat(taskMapper.selectById(taskId).getStatus()).isEqualTo("done");
        List<Artifact> arts = artifactMapper.selectList(
                new QueryWrapper<Artifact>().eq("task_id", taskId));
        assertThat(arts).hasSize(1);
        assertThat(arts.get(0).getType()).isEqualTo("prd");
    }

    @Test
    void garbageLinesAreIgnored() {
        relay.handleLine(taskId, "");                    // 空行
        relay.handleLine(taskId, ": keepalive");         // 注释行
        relay.handleLine(taskId, "data:{not-json");      // 坏 JSON
        assertThat(eventService.listAfter(taskId, 0)).isEmpty();
        assertThat(taskMapper.selectById(taskId).getStatus()).isEqualTo("running");
    }
}
