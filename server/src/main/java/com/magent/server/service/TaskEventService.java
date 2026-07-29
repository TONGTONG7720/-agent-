package com.magent.server.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.entity.TaskEvent;
import com.magent.server.mapper.TaskEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskEventService {

    private final TaskEventMapper eventMapper;

    /** 幂等落库：同 (task_id, seq) 已存在则忽略（唯一键兜底）。 */
    public void save(TaskEvent event) {
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            // 断线重连等场景的重复事件，直接忽略
        }
    }

    public List<TaskEvent> listAfter(Long taskId, int afterSeq) {
        return eventMapper.selectList(new QueryWrapper<TaskEvent>()
                .eq("task_id", taskId).gt("seq", afterSeq).orderByAsc("seq"));
    }

    /** 已落库的最大事件序号（无事件返回 0）。 */
    public int maxSeq(Long taskId) {
        TaskEvent last = eventMapper.selectOne(new QueryWrapper<TaskEvent>()
                .eq("task_id", taskId).orderByDesc("seq").last("limit 1"));
        return last == null ? 0 : last.getSeq();
    }
}
