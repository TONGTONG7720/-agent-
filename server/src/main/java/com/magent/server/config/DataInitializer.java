package com.magent.server.config;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.entity.AgentRoleConfig;
import com.magent.server.entity.SysUser;
import com.magent.server.mapper.AgentRoleConfigMapper;
import com.magent.server.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 幂等种子数据：admin 账号 + 五个 Agent 角色配置（替代 data.sql，BCrypt 哈希需运行期生成）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final List<String> AGENT_ROLES =
            List.of("pm", "architect", "coder", "tester", "reviewer");

    private final SysUserMapper userMapper;
    private final AgentRoleConfigMapper roleConfigMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (userMapper.selectCount(new QueryWrapper<SysUser>().eq("username", "admin")) == 0) {
            SysUser admin = new SysUser();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setRole("admin");
            userMapper.insert(admin);
            log.info("已创建默认管理员 admin/admin123，请尽快修改密码");
        }
        for (String role : AGENT_ROLES) {
            if (roleConfigMapper.selectCount(
                    new QueryWrapper<AgentRoleConfig>().eq("role", role)) == 0) {
                AgentRoleConfig rc = new AgentRoleConfig();
                rc.setRole(role);
                // systemPrompt 留空 = 使用 Agent 服务内置默认 prompt
                roleConfigMapper.insert(rc);
            }
        }
    }
}
