package com.magent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("task_event")
public class TaskEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer seq;
    private String event;
    private String agent;
    /** 事件 data 的 JSON 字符串。 */
    private String data;
    private Long ts;
}
