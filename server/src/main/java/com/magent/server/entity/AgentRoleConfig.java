package com.magent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("agent_role_config")
public class AgentRoleConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 角色唯一键：pm/architect/coder/tester/reviewer 或自定义 */
    private String role;
    /** 展示名 */
    private String name;
    /** 类型：analysis(产文档) / code(产代码) / test(跑测试) / review(判通过可返工) */
    private String kind;
    /** 流水线顺序（升序） */
    private Integer ord;
    /** 是否启用（不启用则不进流水线） */
    private Boolean enabled;
    /** 是否在该步后插入人审门 */
    private Boolean hasGate;
    /** review 步失败时回退到的角色 key */
    private String reworkTarget;
    private String systemPrompt;
    private Long defaultModelId;
}
