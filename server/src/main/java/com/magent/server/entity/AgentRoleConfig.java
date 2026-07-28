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
    /** pm/architect/coder/tester/reviewer */
    private String role;
    private String systemPrompt;
    private Long defaultModelId;
}
