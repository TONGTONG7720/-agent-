package com.magent.server.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
    private final KnowledgeService knowledgeService;

    public Task create(Long projectId, String requirement, boolean autoMode, Long userId) {
        return createInternal(projectId, requirement, autoMode, userId, null);
    }

    /** 多模型对比：同需求双任务，各角色模型分别强制为 A/B，均 auto_mode。 */
    public Map<String, Long> createComparison(Long projectId, String requirement,
                                              Long userId, Long modelAId, Long modelBId) {
        Task a = createInternal(projectId, requirement, true, userId, resolveLitellm(modelAId));
        Task b = createInternal(projectId, requirement, true, userId, resolveLitellm(modelBId));
        Map<String, Long> result = new HashMap<>();
        result.put("taskAId", a.getId());
        result.put("taskBId", b.getId());
        return result;
    }

    private String resolveLitellm(Long modelId) {
        LlmModel m = modelId == null ? null : modelMapper.selectById(modelId);
        if (m == null || !Boolean.TRUE.equals(m.getEnabled())) {
            throw new BizException(400, "模型不可用: " + modelId);
        }
        return m.getLitellmModelName();
    }

    /** 创建并启动任务；forcedModel 非空时将所有角色模型强制为该值（对比用）。 */
    private Task createInternal(Long projectId, String requirement, boolean autoMode,
                                Long userId, String forcedModel) {
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
            if (forcedModel != null) {
                roleModels.put(rc.getRole(), forcedModel);
            } else if (rc.getDefaultModelId() != null) {
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
            String knowledge = knowledgeService.retrieve(requirement, 4, 2400);
            agentClient.startTask(task.agentTaskId(), requirement, autoMode, roleModels, rolePrompts,
                    buildPipelineSpec(), knowledge.isBlank() ? null : knowledge);
        } catch (Exception e) {
            task.setStatus("failed");
            taskMapper.updateById(task);
            throw e instanceof BizException be ? be : new BizException(502, "启动Agent任务失败: " + e.getMessage());
        }
        task.setStatus("running");
        taskMapper.updateById(task);
        return task;
    }

    /** 默认五角色流水线签名（role:kind:gate:rework），与之一致则不传 pipeline。 */
    private static final List<String> DEFAULT_SIG = List.of(
            "pm:analysis:1:", "architect:analysis:1:", "coder:code:0:",
            "tester:test:0:", "reviewer:review:0:coder");

    /** 从启用角色按 ord 组装 pipeline spec；与默认五角色完全一致时返回 null（走旧图）。 */
    private Map<String, Object> buildPipelineSpec() {
        List<AgentRoleConfig> roles = roleConfigMapper.selectList(
                new QueryWrapper<AgentRoleConfig>().orderByAsc("ord", "id"));
        List<AgentRoleConfig> enabled = new ArrayList<>();
        List<String> sig = new ArrayList<>();
        for (AgentRoleConfig r : roles) {
            if (Boolean.FALSE.equals(r.getEnabled())) {
                continue;
            }
            enabled.add(r);
            sig.add(r.getRole() + ":" + nz(r.getKind(), "analysis") + ":"
                    + (Boolean.TRUE.equals(r.getHasGate()) ? 1 : 0) + ":" + nz(r.getReworkTarget(), ""));
        }
        if (sig.equals(DEFAULT_SIG)) {
            return null;
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (AgentRoleConfig r : enabled) {
            Map<String, Object> step = new HashMap<>();
            step.put("key", r.getRole());
            step.put("name", nz(r.getName(), r.getRole()));
            step.put("kind", nz(r.getKind(), "analysis"));
            step.put("gate", Boolean.TRUE.equals(r.getHasGate()));
            if (r.getReworkTarget() != null && !r.getReworkTarget().isBlank()) {
                step.put("rework_target", r.getReworkTarget());
            }
            if (r.getSystemPrompt() != null && !r.getSystemPrompt().isBlank()) {
                step.put("system_prompt", r.getSystemPrompt());
            }
            steps.add(step);
        }
        Map<String, Object> spec = new HashMap<>();
        spec.put("steps", steps);
        spec.put("final_gate", true);
        return spec;
    }

    private static String nz(String v, String dft) {
        return (v == null || v.isBlank()) ? dft : v;
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

    /** 把产物推送到 Git 仓库：仅 done 可调，返回分支名（不改变任务状态）。 */
    public String pushToGit(Long taskId, String repoUrl, String token) {
        Task task = getOrThrow(taskId);
        requireStatus(task, "done");
        return agentClient.push(task.agentTaskId(), repoUrl, token);
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
