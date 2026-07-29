package com.magent.server.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.magent.server.common.BizException;
import com.magent.server.config.AppProps;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Agent 服务 HTTP 客户端实现（内网，Header 带共享密钥）。
 */
@Component
@RequiredArgsConstructor
public class HttpAgentClient implements AgentClient {

    private final AppProps props;
    private final ObjectMapper om = new ObjectMapper();
    // 必须强制 HTTP/1.1：JDK 默认的 h2c 升级头会导致 uvicorn 读不到请求体（422）
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public void startTask(String taskId, String requirement, boolean autoMode,
                          Map<String, String> roleModels, Map<String, String> rolePrompts,
                          Map<String, Object> pipeline) {
        Map<String, Object> body = new HashMap<>();
        body.put("task_id", taskId);
        body.put("requirement", requirement);
        body.put("auto_mode", autoMode);
        body.put("role_models", roleModels);
        body.put("role_prompts", rolePrompts);
        body.put("pipeline", pipeline);   // null = 默认五角色图
        post("/agent/tasks", body);
    }

    @Override
    public void resume(String taskId, String decision, String comment, String target) {
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decision);
        body.put("comment", comment == null ? "" : comment);
        body.put("target", target);   // 可为 null，agent 侧按门默认
        post("/agent/tasks/" + taskId + "/resume", body);
    }

    @Override
    public void retry(String taskId, int afterSeq) {
        post("/agent/tasks/" + taskId + "/retry", Map.of("after_seq", afterSeq));
    }

    @Override
    public void iterate(String taskId, String feedback, int afterSeq) {
        post("/agent/tasks/" + taskId + "/iterate",
                Map.of("feedback", feedback, "after_seq", afterSeq));
    }

    @Override
    public String push(String taskId, String repoUrl, String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("repo_url", repoUrl);
        body.put("token", token);   // 可为 null
        String resp = post("/agent/tasks/" + taskId + "/push", body);
        try {
            return om.readTree(resp).path("branch").asText();
        } catch (Exception e) {
            throw new BizException(502, "Agent推送响应解析失败");
        }
    }

    @Override
    public void cancel(String taskId) {
        post("/agent/tasks/" + taskId + "/cancel", Map.of());
    }

    private String post(String path, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(props.getAgentBaseUrl() + path))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", props.getInternalToken())
                    .timeout(Duration.ofSeconds(180))
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new BizException(502, "Agent服务响应异常 " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "Agent服务不可达: " + e.getMessage());
        }
    }
}
