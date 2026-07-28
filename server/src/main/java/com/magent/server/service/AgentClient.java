package com.magent.server.service;

import java.util.Map;

/**
 * Agent 服务客户端契约（内网 HTTP，见设计文档 4.2）。测试用 Mockito mock。
 */
public interface AgentClient {

    void startTask(String taskId, String requirement, boolean autoMode,
                   Map<String, String> roleModels, Map<String, String> rolePrompts);

    void resume(String taskId, String decision, String comment);

    void cancel(String taskId);
}
