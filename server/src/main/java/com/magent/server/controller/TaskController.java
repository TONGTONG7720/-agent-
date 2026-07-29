package com.magent.server.controller;

import java.util.List;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.common.Result;
import com.magent.server.config.AppProps;
import com.magent.server.entity.Task;
import com.magent.server.entity.TaskEvent;
import com.magent.server.mapper.TaskMapper;
import com.magent.server.service.AgentStreamRelay;
import com.magent.server.service.SseRegistry;
import com.magent.server.service.TaskEventService;
import com.magent.server.service.TaskService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final TaskService taskService;
    private final TaskMapper taskMapper;
    private final TaskEventService eventService;
    private final SseRegistry sseRegistry;
    private final AgentStreamRelay relay;
    private final AppProps props;

    public record CreateTaskRequest(@NotNull Long projectId,
                                    @NotBlank String requirement,
                                    boolean autoMode) {
    }

    public record ApproveRequest(@NotBlank String decision, String comment, String target) {
    }

    public record IterateRequest(@NotBlank String feedback) {
    }

    @PostMapping
    public Result<Task> create(@Validated @RequestBody CreateTaskRequest req) {
        Task task = taskService.create(req.projectId(), req.requirement(),
                req.autoMode(), StpUtil.getLoginIdAsLong());
        if (props.isRelayEnabled()) {
            relay.startRelay(task.getId());
        }
        return Result.ok(task);
    }

    @GetMapping
    public Result<List<Task>> list(@RequestParam(required = false) Long projectId) {
        QueryWrapper<Task> qw = new QueryWrapper<Task>().orderByDesc("id");
        if (projectId != null) {
            qw.eq("project_id", projectId);
        }
        return Result.ok(taskMapper.selectList(qw));
    }

    @GetMapping("/{id}")
    public Result<Task> get(@PathVariable Long id) {
        return Result.ok(taskService.getOrThrow(id));
    }

    @GetMapping("/{id}/events")
    public Result<List<TaskEvent>> events(@PathVariable Long id,
                                          @RequestParam(defaultValue = "0") int afterSeq) {
        taskService.getOrThrow(id);
        return Result.ok(eventService.listAfter(id, afterSeq));
    }

    @GetMapping("/{id}/stream")
    public SseEmitter stream(@PathVariable Long id) {
        taskService.getOrThrow(id);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseRegistry.add(id, emitter);
        return emitter;
    }

    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id,
                                @Validated @RequestBody ApproveRequest req) {
        taskService.approve(id, req.decision(), req.comment(), req.target());
        return Result.ok();
    }

    @PostMapping("/{id}/iterate")
    public Result<Void> iterate(@PathVariable Long id,
                                @Validated @RequestBody IterateRequest req) {
        taskService.iterate(id, req.feedback());
        if (props.isRelayEnabled()) {
            relay.startRelay(id);   // 新一轮事件流重新订阅
        }
        return Result.ok();
    }

    @PostMapping("/{id}/retry")
    public Result<Void> retry(@PathVariable Long id) {
        taskService.retry(id);
        if (props.isRelayEnabled()) {
            relay.startRelay(id);   // 重试后重新订阅事件流
        }
        return Result.ok();
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        taskService.cancel(id);
        return Result.ok();
    }
}
