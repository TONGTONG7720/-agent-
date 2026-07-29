package com.magent.server.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.magent.server.common.BizException;
import com.magent.server.entity.AgentRoleConfig;
import com.magent.server.entity.LlmModel;
import com.magent.server.entity.Task;
import com.magent.server.mapper.AgentRoleConfigMapper;
import com.magent.server.mapper.LlmModelMapper;
import com.magent.server.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 任务生命周期集中管理（状态机见计划文档）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private static final Set<String> TERMINAL = Set.of("done", "failed", "canceled");

    private final TaskMapper taskMapper;
    private final AgentRoleConfigMapper roleConfigMapper;
    private final LlmModelMapper modelMapper;
    private final AgentClient agentClient;
    private final TaskEventService eventService;

    public Task create(Long projectId, String requirement, boolean autoMode, Long userId) {
        Task task = new Task();
        task.setProjectId(projectId);
        task.setRequirement(requirement);
        task.setStatus("pending");
        task.setAutoMode(autoMode);
        task.setCreatedBy(userId);
        taskMapper.insert(task);

        Map<String, String> roleModels = new HashMap<>();
        Map<String, String> rolePrompts = new HashMap<>();
        for (AgentRoleConfig rc : roleConfigMapper.selectList(null)) {
            if (rc.getDefaultModelId() != null) {
                LlmModel m = modelMapper.selectById(rc.getDefaultModelId());
                if (m != null && Boolean.TRUE.equals(m.getEnabled())) {
                    roleModels.put(rc.getRole(), m.getLitellmModelName());
                }
            }
            if (rc.getSystemPrompt() != null && !rc.getSystemPrompt().isBlank()) {
                rolePrompts.put(rc.getRole(), rc.getSystemPrompt());
            }
        }

        try {
            agentClient.startTask(task.agentTaskId(), requirement, autoMode, roleModels, rolePrompts);
        } catch (Exception e) {
            task.setStatus("failed");
            taskMapper.updateById(task);
            throw e instanceof BizException be ? be : new BizException(502, "启动Agent任务失败: " + e.getMessage());
        }
        task.setStatus("running");
        taskMapper.updateById(task);
        return task;
    }

    public Task getOrThrow(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(404, "任务不存在");
        }
        return task;
    }

    public void markWaitingReview(Long taskId, String node) {
        Task task = getOrThrow(taskId);
        requireStatus(task, "running");
        task.setStatus("waiting_review");
        task.setCurrentNode(node);
        taskMapper.updateById(task);
    }

    /** 审批通过/驳回后恢复执行；target 为驳回定向回退目标（可空，按门默认）。 */
    public void approve(Long taskId, String decision, String comment, String target) {
        Task task = getOrThrow(taskId);
        requireStatus(task, "waiting_review");
        agentClient.resume(task.agentTaskId(), decision, comment, target);
        task.setStatus("running");
        taskMapper.updateById(task);
    }

    /** 已完成任务的多轮迭代：仅 done 可调。 */
    public void iterate(Long taskId, String feedback) {
        Task task = getOrThrow(taskId);
        requireStatus(task, "done");
        agentClient.iterate(task.agentTaskId(), feedback, eventService.maxSeq(taskId));
        task.setStatus("running");
        taskMapper.updateById(task);
    }

    /** 失败任务断点重试：仅 failed 可调；传已落库最大 seq 供 agent 续号。 */
    public void retry(Long taskId) {
        Task task = getOrThrow(taskId);
        requireStatus(task, "failed");
        agentClient.retry(task.agentTaskId(), eventService.maxSeq(taskId));
        task.setStatus("running");
        taskMapper.updateById(task);
    }

    public void markDone(Long taskId) {
        markTerminal(taskId, "done", null);
    }

    public void markFailed(Long taskId, String reason) {
        markTerminal(taskId, "failed", reason);
    }

    public void cancel(Long taskId) {
        Task task = getOrThrow(taskId);
        if (TERMINAL.contains(task.getStatus())) {
            throw new BizException(409, "任务已结束，无法取消");
        }
        agentClient.cancel(task.agentTaskId());
        task.setStatus("canceled");
        taskMapper.updateById(task);
    }

    /** 终态迁移：已是终态则幂等忽略。 */
    private void markTerminal(Long taskId, String status, String reason) {
        Task task = getOrThrow(taskId);
        if (TERMINAL.contains(task.getStatus())) {
            return;
        }
        task.setStatus(status);
        taskMapper.updateById(task);
        if (reason != null) {
            log.warn("task {} -> {}: {}", taskId, status, reason);
        }
    }

    private void requireStatus(Task task, String expected) {
        if (!expected.equals(task.getStatus())) {
            throw new BizException(409,
                    "任务状态为 " + task.getStatus() + "，不允许此操作");
        }
    }
}
