package com.magent.server.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.magent.server.config.AppProps;
import com.magent.server.entity.Artifact;
import com.magent.server.entity.TaskEvent;
import com.magent.server.mapper.ArtifactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 消费 Agent 服务 SSE 事件流：落库 task_event → 驱动任务状态机 → 转发前端。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStreamRelay {

    private final AppProps props;
    private final TaskEventService eventService;
    private final TaskService taskService;
    private final ArtifactMapper artifactMapper;
    private final SseRegistry sseRegistry;
    private final ObjectMapper om = new ObjectMapper();

    /** 后台线程消费 Agent SSE 流（任务成功启动后调用）。 */
    public void startRelay(Long taskId) {
        Thread t = new Thread(() -> consume(taskId), "agent-relay-" + taskId);
        t.setDaemon(true);
        t.start();
    }

    private void consume(Long taskId) {
        String url = props.getAgentBaseUrl() + "/agent/tasks/T" + taskId + "/stream";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)   // 同 HttpAgentClient：避免 h2c 升级头
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("X-Internal-Token", props.getInternalToken())
                    .GET().build();
            HttpResponse<java.io.InputStream> resp =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(taskId, line);
                }
            }
        } catch (Exception e) {
            log.error("relay for task {} aborted: {}", taskId, e.getMessage());
        } finally {
            // 流结束仍非终态 → 事件流中断，标记失败（checkpoint 在 Agent 侧仍可恢复）
            if (!isTerminal(taskId)) {
                taskService.markFailed(taskId, "agent event stream lost");
            }
        }
    }

    /** 处理一行 SSE：非 data: 行与坏 JSON 一律跳过。 */
    public void handleLine(Long taskId, String line) {
        if (line == null || !line.startsWith("data:")) {
            return;
        }
        JsonNode node;
        try {
            node = om.readTree(line.substring(5));
        } catch (Exception e) {
            log.warn("task {} bad event json, skipped", taskId);
            return;
        }
        String eventType = node.path("event").asText();
        JsonNode data = node.path("data");

        TaskEvent event = new TaskEvent();
        event.setTaskId(taskId);
        event.setSeq(node.path("seq").asInt());
        event.setEvent(eventType);
        event.setAgent(node.hasNonNull("agent") ? node.get("agent").asText() : null);
        event.setData(data.toString());
        event.setTs(node.path("ts").asLong());
        eventService.save(event);

        switch (eventType) {
            case "interrupt" -> taskService.markWaitingReview(taskId, data.path("gate").asText());
            case "task_done" -> taskService.markDone(taskId);
            case "task_failed" -> taskService.markFailed(taskId, data.path("error").asText());
            case "artifact_created" -> saveArtifact(taskId, data);
            default -> { /* node_end / agent_message 仅落库转发 */ }
        }
        sseRegistry.broadcast(taskId, event);
    }

    private void saveArtifact(Long taskId, JsonNode data) {
        Artifact artifact = new Artifact();
        artifact.setTaskId(taskId);
        artifact.setName(data.path("name").asText());
        artifact.setType(data.path("type").asText());
        artifact.setPath(data.path("path").asText());
        artifact.setVersion(1);
        artifactMapper.insert(artifact);
    }

    private boolean isTerminal(Long taskId) {
        String status = taskService.getOrThrow(taskId).getStatus();
        return "done".equals(status) || "failed".equals(status) || "canceled".equals(status);
    }
}
