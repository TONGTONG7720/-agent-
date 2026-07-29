package com.magent.server.controller;

import java.util.List;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.common.BizException;
import com.magent.server.common.Result;
import com.magent.server.entity.AgentRoleConfig;
import com.magent.server.mapper.AgentRoleConfigMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流水线（自定义角色）管理：列表 / 新增 / 更新 / 删除 / 重排。
 */
@RestController
@RequestMapping("/api/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final AgentRoleConfigMapper roleConfigMapper;

    public record AddRoleRequest(@NotBlank String role, @NotBlank String name,
                                 String kind, boolean hasGate, String reworkTarget,
                                 String systemPrompt, Long defaultModelId) {
    }

    public record UpdateRoleRequest(String name, String kind, Boolean hasGate, Boolean enabled,
                                    String reworkTarget, String systemPrompt, Long defaultModelId) {
    }

    public record ReorderRequest(List<Long> orderedIds) {
    }

    @GetMapping
    public Result<List<AgentRoleConfig>> list() {
        return Result.ok(roleConfigMapper.selectList(
                new QueryWrapper<AgentRoleConfig>().orderByAsc("ord", "id")));
    }

    @SaCheckRole("admin")
    @PostMapping("/roles")
    public Result<AgentRoleConfig> add(@Validated @RequestBody AddRoleRequest req) {
        if (roleConfigMapper.selectCount(new QueryWrapper<AgentRoleConfig>().eq("role", req.role())) > 0) {
            throw new BizException(409, "角色 key 已存在: " + req.role());
        }
        Integer maxOrd = roleConfigMapper.selectList(
                        new QueryWrapper<AgentRoleConfig>().orderByDesc("ord").last("limit 1"))
                .stream().findFirst().map(AgentRoleConfig::getOrd).orElse(0);
        AgentRoleConfig rc = new AgentRoleConfig();
        rc.setRole(req.role());
        rc.setName(req.name());
        rc.setKind(req.kind() == null ? "analysis" : req.kind());
        rc.setHasGate(req.hasGate());
        rc.setEnabled(true);
        rc.setOrd(maxOrd + 1);
        rc.setReworkTarget(req.reworkTarget());
        rc.setSystemPrompt(req.systemPrompt());
        rc.setDefaultModelId(req.defaultModelId());
        roleConfigMapper.insert(rc);
        return Result.ok(rc);
    }

    @SaCheckRole("admin")
    @PutMapping("/roles/{id}")
    public Result<AgentRoleConfig> update(@PathVariable Long id, @RequestBody UpdateRoleRequest req) {
        AgentRoleConfig rc = roleConfigMapper.selectById(id);
        if (rc == null) {
            throw new BizException(404, "角色不存在");
        }
        if (req.name() != null) rc.setName(req.name());
        if (req.kind() != null) rc.setKind(req.kind());
        if (req.hasGate() != null) rc.setHasGate(req.hasGate());
        if (req.enabled() != null) rc.setEnabled(req.enabled());
        rc.setReworkTarget(req.reworkTarget());
        rc.setSystemPrompt(req.systemPrompt());
        rc.setDefaultModelId(req.defaultModelId());
        roleConfigMapper.updateById(rc);
        return Result.ok(rc);
    }

    @SaCheckRole("admin")
    @PostMapping("/roles/{id}/delete")
    public Result<Void> delete(@PathVariable Long id) {
        roleConfigMapper.deleteById(id);
        return Result.ok();
    }

    @SaCheckRole("admin")
    @PostMapping("/reorder")
    public Result<Void> reorder(@RequestBody ReorderRequest req) {
        int ord = 1;
        for (Long id : req.orderedIds()) {
            AgentRoleConfig rc = roleConfigMapper.selectById(id);
            if (rc != null) {
                rc.setOrd(ord++);
                roleConfigMapper.updateById(rc);
            }
        }
        return Result.ok();
    }
}
