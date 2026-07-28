package com.magent.server.controller;

import java.util.List;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.common.BizException;
import com.magent.server.common.Result;
import com.magent.server.entity.AgentRoleConfig;
import com.magent.server.mapper.AgentRoleConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/role-configs")
@RequiredArgsConstructor
public class RoleConfigController {

    private final AgentRoleConfigMapper roleConfigMapper;

    public record UpdateRoleConfigRequest(String systemPrompt, Long defaultModelId) {
    }

    @GetMapping
    public Result<List<AgentRoleConfig>> list() {
        return Result.ok(roleConfigMapper.selectList(
                new QueryWrapper<AgentRoleConfig>().orderByAsc("id")));
    }

    @SaCheckRole("admin")
    @PutMapping("/{role}")
    public Result<AgentRoleConfig> update(@PathVariable String role,
                                          @RequestBody UpdateRoleConfigRequest req) {
        AgentRoleConfig config = roleConfigMapper.selectOne(
                new QueryWrapper<AgentRoleConfig>().eq("role", role));
        if (config == null) {
            throw new BizException(404, "角色不存在: " + role);
        }
        config.setSystemPrompt(req.systemPrompt());
        config.setDefaultModelId(req.defaultModelId());
        roleConfigMapper.updateById(config);
        return Result.ok(config);
    }
}
