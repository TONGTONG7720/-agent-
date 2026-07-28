package com.magent.server.controller;

import java.util.List;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.common.Result;
import com.magent.server.entity.Task;
import com.magent.server.mapper.TaskMapper;
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

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskMapper taskMapper;

    public record CreateTaskRequest(@NotNull Long projectId,
                                    @NotBlank String requirement,
                                    boolean autoMode) {
    }

    @PostMapping
    public Result<Task> create(@Validated @RequestBody CreateTaskRequest req) {
        Task task = taskService.create(req.projectId(), req.requirement(),
                req.autoMode(), StpUtil.getLoginIdAsLong());
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
}
