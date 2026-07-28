package com.magent.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProps {

    private String agentBaseUrl;
    private String internalToken;
    private String aesKey;
    private String workspaceRoot;
    /** 是否启动 Agent SSE 中继线程（测试环境关闭，避免连不上Agent服务误标failed）。 */
    private boolean relayEnabled = true;
}
