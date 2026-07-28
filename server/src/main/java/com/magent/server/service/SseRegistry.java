package com.magent.server.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.magent.server.entity.TaskEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 前端 SSE 会话注册表（内存实现，单实例内部工具够用）。
 */
@Slf4j
@Component
public class SseRegistry {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper om = new ObjectMapper();

    public void add(Long taskId, SseEmitter emitter) {
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
    }

    public void remove(Long taskId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    public void broadcast(Long taskId, TaskEvent event) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event.getEvent()).data(buildPayload(event)));
            } catch (Exception e) {
                remove(taskId, emitter);
            }
        }
    }

    /** 转发给前端的完整事件 JSON（与落库结构一致，Jackson 序列化避免手工转义问题）。 */
    private String buildPayload(TaskEvent e) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", e.getEvent());
        payload.put("task_id", e.getTaskId());
        payload.put("agent", e.getAgent());
        payload.put("seq", e.getSeq());
        payload.put("data", om.readTree(e.getData() == null ? "{}" : e.getData()));
        payload.put("ts", e.getTs());
        return om.writeValueAsString(payload);
    }
}
