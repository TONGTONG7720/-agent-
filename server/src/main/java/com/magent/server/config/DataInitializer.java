package com.magent.server.config;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.entity.AgentRoleConfig;
import com.magent.server.entity.LlmModel;
import com.magent.server.entity.SysUser;
import com.magent.server.mapper.AgentRoleConfigMapper;
import com.magent.server.mapper.LlmModelMapper;
import com.magent.server.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 幂等种子数据：admin 账号 + 默认模型(gpt-5.6-terra) + 五个 Agent 角色配置（全部绑定该默认模型）。
 * 替代 data.sql，因 BCrypt 哈希需运行期生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final List<String> AGENT_ROLES =
            List.of("pm", "architect", "coder", "tester", "reviewer");
    /** 默认模型名（须与 llm-gateway/litellm-config.yaml 中注册的 model_name 一致）。 */
    private static final String DEFAULT_MODEL_NAME = "gpt-5.6-terra";

    private final SysUserMapper userMapper;
    private final AgentRoleConfigMapper roleConfigMapper;
    private final LlmModelMapper modelMapper;
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

        Long defaultModelId = ensureDefaultModel();

        for (String role : AGENT_ROLES) {
            AgentRoleConfig rc = roleConfigMapper.selectOne(
                    new QueryWrapper<AgentRoleConfig>().eq("role", role));
            if (rc == null) {
                rc = new AgentRoleConfig();
                rc.setRole(role);
                rc.setDefaultModelId(defaultModelId);   // 新建即绑定默认模型
                // systemPrompt 留空 = 使用 Agent 服务内置默认 prompt
                roleConfigMapper.insert(rc);
            } else if (rc.getDefaultModelId() == null) {
                // 已存在但未指定模型：回填为默认模型（与运行时兼底一致，仅让 UI 显式可见）
                rc.setDefaultModelId(defaultModelId);
                roleConfigMapper.updateById(rc);
            }
        }
    }

    /** 确保默认模型已登记，返回其 id（幂等）。 */
    private Long ensureDefaultModel() {
        LlmModel model = modelMapper.selectOne(
                new QueryWrapper<LlmModel>().eq("name", DEFAULT_MODEL_NAME));
        if (model == null) {
            model = new LlmModel();
            model.setName(DEFAULT_MODEL_NAME);
            model.setLitellmModelName(DEFAULT_MODEL_NAME);
            model.setEnabled(true);
            // api_key 走网关配置，此处无需存储
            modelMapper.insert(model);
            log.info("已登记默认模型 {}", DEFAULT_MODEL_NAME);
        }
        return model.getId();
    }
}
