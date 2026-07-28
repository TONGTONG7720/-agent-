package com.magent.server.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
                emitter.send(SseEmitter.event().name(event.getEvent())
                        .data(event.getData() == null ? "{}" : buildPayload(event)));
            } catch (Exception e) {
                remove(taskId, emitter);
            }
        }
    }

    /** 转发给前端的完整事件 JSON（与落库结构一致）。 */
    private String buildPayload(TaskEvent e) {
        return "{\"event\":\"" + e.getEvent() + "\",\"task_id\":" + e.getTaskId()
                + ",\"agent\":" + (e.getAgent() == null ? "null" : "\"" + e.getAgent() + "\"")
                + ",\"seq\":" + e.getSeq() + ",\"data\":" + e.getData()
                + ",\"ts\":" + e.getTs() + "}";
    }
}
