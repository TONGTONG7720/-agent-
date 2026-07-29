package com.magent.server.service;

import java.util.Map;

/**
 * Agent 服务客户端契约（内网 HTTP，见设计文档 4.2）。测试用 Mockito mock。
 */
public interface AgentClient {

    void startTask(String taskId, String requirement, boolean autoMode,
                   Map<String, String> roleModels, Map<String, String> rolePrompts);

    void resume(String taskId, String decision, String comment, String target);

    /** 失败任务断点重试；afterSeq 为已落库最大事件序号，供 agent 续号防撞。 */
    void retry(String taskId, int afterSeq);

    /** 已完成任务的多轮迭代；基于现有产物增量修改。 */
    void iterate(String taskId, String feedback, int afterSeq);

    /** 把任务产物推送到 Git 仓库新分支，返回分支名；token 可空且不落库。 */
    String push(String taskId, String repoUrl, String token);

    void cancel(String taskId);
}
