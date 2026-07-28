package com.magent.server.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("artifact")
public class Artifact {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String name;
    /** prd/design/code/test_report */
    private String type;
    private String path;
    private Integer version;
    private LocalDateTime createdAt;
}
