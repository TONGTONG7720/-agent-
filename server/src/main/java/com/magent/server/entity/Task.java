package com.magent.server.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("task")
public class Task {

    /** 状态: pending/running/waiting_review/done/failed/canceled */
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String requirement;
    private String status;
    private Boolean autoMode;
    private String currentNode;
    private Long createdBy;
    private LocalDateTime createdAt;

    /** Agent 服务侧使用的任务标识。 */
    public String agentTaskId() {
        return "T" + id;
    }
}
