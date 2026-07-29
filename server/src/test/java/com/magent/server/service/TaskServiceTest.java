package com.magent.server.service;

import com.magent.server.common.BizException;
import com.magent.server.entity.AgentRoleConfig;
import com.magent.server.entity.LlmModel;
import com.magent.server.entity.Task;
import com.magent.server.mapper.AgentRoleConfigMapper;
import com.magent.server.mapper.LlmModelMapper;
import com.magent.server.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskServiceTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private LlmModelMapper modelMapper;
    @Autowired
    private AgentRoleConfigMapper roleConfigMapper;
    @MockBean
    private AgentClient agentClient;

    private Task createOk() {
        return taskService.create(1L, "做一个计算器", false, 1L);
    }

    @Test
    void createStartsAgentAndMarksRunning() {
        // 配置 coder 角色默认模型
        LlmModel m = new LlmModel();
        m.setName("deepseek");
        m.setLitellmModelName("deepseek-v3");
        m.setEnabled(true);
        modelMapper.insert(m);
        AgentRoleConfig coder = roleConfigMapper.selectOne(
                new QueryWrapper<AgentRoleConfig>().eq("role", "coder"));
        coder.setDefaultModelId(m.getId());
        coder.setSystemPrompt("自定义coder提示词");
        roleConfigMapper.updateById(coder);

        Task t = createOk();

        assertThat(t.getStatus()).isEqualTo("running");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> models = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> prompts = ArgumentCaptor.forClass(Map.class);
        verify(agentClient).startTask(eq("T" + t.getId()), eq("做一个计算器"), eq(false),
                models.capture(), prompts.capture(), any(), any());
        assertThat(models.getValue()).containsEntry("coder", "deepseek-v3");
        assertThat(prompts.getValue()).containsEntry("coder", "自定义coder提示词");
    }

    @Test
    void createMarksFailedWhenAgentUnreachable() {
        doThrow(new BizException(502, "agent down")).when(agentClient)
                .startTask(anyString(), anyString(), anyBoolean(), anyMap(), anyMap(), any(), any());
        assertThatThrownBy(this::createOk).isInstanceOf(BizException.class);
        // 取最新一条（其他非事务测试可能残留旧任务）
        Task saved = taskMapper.selectList(
                new QueryWrapper<Task>().orderByDesc("id").last("limit 1")).get(0);
        assertThat(saved.getStatus()).isEqualTo("failed");
    }

    @Test
    void waitingReviewOnlyFromRunning() {
        Task t = createOk();
        taskService.markWaitingReview(t.getId(), "prd_gate");
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("waiting_review");
        assertThat(taskMapper.selectById(t.getId()).getCurrentNode()).isEqualTo("prd_gate");
        // 已是 waiting_review 再标一次 → 409
        assertThatThrownBy(() -> taskService.markWaitingReview(t.getId(), "x"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void terminalStatesAreIdempotent() {
        Task t = createOk();
        taskService.markDone(t.getId());
        taskService.markDone(t.getId());     // 幂等不抛
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("done");
        taskService.markFailed(t.getId(), "x");  // done 后 failed 忽略
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("done");
    }

    @Test
    void cancelCallsAgentAndSetsStatus() {
        Task t = createOk();
        taskService.cancel(t.getId());
        verify(agentClient).cancel("T" + t.getId());
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("canceled");
    }
}
