package com.magent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("llm_model")
public class LlmModel {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String litellmModelName;
    /** AES 加密后的 api key，可为空（key 在网关配置时）。 */
    private String apiKeyEnc;
    private Boolean enabled;
}
