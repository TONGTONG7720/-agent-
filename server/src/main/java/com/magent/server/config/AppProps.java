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
}
