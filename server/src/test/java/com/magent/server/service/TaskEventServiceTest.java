package com.magent.server.service;

import java.util.List;

import com.magent.server.entity.TaskEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskEventServiceTest {

    @Autowired
    private TaskEventService eventService;
    @MockBean
    private AgentClient agentClient;

    private TaskEvent ev(long taskId, int seq, String event) {
        TaskEvent e = new TaskEvent();
        e.setTaskId(taskId);
        e.setSeq(seq);
        e.setEvent(event);
        e.setAgent("pm");
        e.setData("{}");
        e.setTs(System.currentTimeMillis());
        return e;
    }

    @Test
    void saveThenListAfterReturnsOrdered() {
        eventService.save(ev(1L, 2, "node_end"));
        eventService.save(ev(1L, 1, "agent_message"));
        List<TaskEvent> list = eventService.listAfter(1L, 0);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getSeq()).isEqualTo(1);
        assertThat(eventService.listAfter(1L, 1)).hasSize(1);
    }

    @Test
    void duplicateSeqIsIgnored() {
        eventService.save(ev(1L, 1, "node_end"));
        eventService.save(ev(1L, 1, "node_end"));   // 重复不抛异常
        assertThat(eventService.listAfter(1L, 0)).hasSize(1);
    }
}
