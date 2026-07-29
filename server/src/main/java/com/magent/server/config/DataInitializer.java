package com.magent.server.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

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
 * 幂等种子数据：admin + 默认模型 + 默认五角色流水线；并对老库补齐流水线元数据列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final String DEFAULT_MODEL_NAME = "gpt-5.6-terra";

    /** 默认流水线：role, name, kind, ord, hasGate, reworkTarget */
    private static final List<Object[]> DEFAULT_PIPELINE = List.of(
            new Object[]{"pm", "产品经理", "analysis", 1, true, null},
            new Object[]{"architect", "架构师", "analysis", 2, true, null},
            new Object[]{"coder", "开发工程师", "code", 3, false, null},
            new Object[]{"tester", "测试工程师", "test", 4, false, null},
            new Object[]{"reviewer", "代码审查员", "review", 5, false, "coder"});

    private final SysUserMapper userMapper;
    private final AgentRoleConfigMapper roleConfigMapper;
    private final LlmModelMapper modelMapper;
    private final PasswordEncoder passwordEncoder;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        evolveSchema();

        if (userMapper.selectCount(new QueryWrapper<SysUser>().eq("username", "admin")) == 0) {
            SysUser admin = new SysUser();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setRole("admin");
            userMapper.insert(admin);
            log.info("已创建默认管理员 admin/admin123，请尽快修改密码");
        }

        Long defaultModelId = ensureDefaultModel();

        for (Object[] p : DEFAULT_PIPELINE) {
            String role = (String) p[0];
            AgentRoleConfig rc = roleConfigMapper.selectOne(
                    new QueryWrapper<AgentRoleConfig>().eq("role", role));
            if (rc == null) {
                rc = new AgentRoleConfig();
                rc.setRole(role);
                rc.setDefaultModelId(defaultModelId);
            }
            // 幂等补齐流水线元数据：ord==0/null 视为“未配置”（老库 ALTER 加列带 DEFAULT 0），戳默认值；
            // 已配置（ord>=1）则仅补 null，不覆盖用户自定义排序/开关
            boolean unconfigured = rc.getOrd() == null || rc.getOrd() == 0;
            if (unconfigured) {
                rc.setName((String) p[1]);
                rc.setKind((String) p[2]);
                rc.setOrd((Integer) p[3]);
                rc.setEnabled(true);
                rc.setHasGate((Boolean) p[4]);
                rc.setReworkTarget((String) p[5]);
            } else {
                if (rc.getName() == null) rc.setName((String) p[1]);
                if (rc.getKind() == null) rc.setKind((String) p[2]);
                if (rc.getEnabled() == null) rc.setEnabled(true);
                if (rc.getHasGate() == null) rc.setHasGate((Boolean) p[4]);
            }
            if (rc.getDefaultModelId() == null) rc.setDefaultModelId(defaultModelId);
            if (rc.getId() == null) {
                roleConfigMapper.insert(rc);
            } else {
                roleConfigMapper.updateById(rc);
            }
        }
    }

    /** 对已存在的老库补齐新列（元数据检查后 ALTER；H2/MySQL 通用）。 */
    private void evolveSchema() {
        Map<String, String> cols = Map.of(
                "name", "VARCHAR(64)",
                "kind", "VARCHAR(16) DEFAULT 'analysis'",
                "ord", "INT DEFAULT 0",
                "enabled", "TINYINT DEFAULT 1",
                "has_gate", "TINYINT DEFAULT 0",
                "rework_target", "VARCHAR(32)");
        try (Connection conn = dataSource.getConnection()) {
            for (Map.Entry<String, String> e : cols.entrySet()) {
                if (!columnExists(conn, "agent_role_config", e.getKey())) {
                    try (var st = conn.createStatement()) {
                        st.execute("ALTER TABLE agent_role_config ADD COLUMN " + e.getKey() + " " + e.getValue());
                        log.info("已为 agent_role_config 补列 {}", e.getKey());
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("schema evolve skipped: {}", ex.getMessage());
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws Exception {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                String c = rs.getString("COLUMN_NAME");
                if (c != null && c.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        // 大写表名兜底（部分数据库大小写敏感）
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table.toUpperCase(), null)) {
            while (rs.next()) {
                String c = rs.getString("COLUMN_NAME");
                if (c != null && c.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Long ensureDefaultModel() {
        LlmModel model = modelMapper.selectOne(
                new QueryWrapper<LlmModel>().eq("name", DEFAULT_MODEL_NAME));
        if (model == null) {
            model = new LlmModel();
            model.setName(DEFAULT_MODEL_NAME);
            model.setLitellmModelName(DEFAULT_MODEL_NAME);
            model.setEnabled(true);
            modelMapper.insert(model);
            log.info("已登记默认模型 {}", DEFAULT_MODEL_NAME);
        }
        return model.getId();
    }
}
