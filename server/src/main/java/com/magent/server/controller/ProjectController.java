package com.magent.server.controller;

import java.util.List;

import cn.dev33.satoken.stp.StpUtil;
import com.magent.server.common.Result;
import com.magent.server.entity.Project;
import com.magent.server.mapper.ProjectMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectMapper projectMapper;

    public record CreateProjectRequest(@NotBlank String name) {
    }

    @PostMapping
    public Result<Project> create(@Validated @RequestBody CreateProjectRequest req) {
        Project p = new Project();
        p.setName(req.name());
        p.setOwnerId(StpUtil.getLoginIdAsLong());
        projectMapper.insert(p);
        return Result.ok(p);
    }

    @GetMapping
    public Result<List<Project>> list() {
        // 团队内部工具：项目全员可见
        return Result.ok(projectMapper.selectList(null));
    }
}
