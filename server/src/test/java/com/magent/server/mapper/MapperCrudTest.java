package com.magent.server.mapper;

import com.magent.server.entity.SysUser;
import com.magent.server.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MapperCrudTest {

    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private TaskMapper taskMapper;

    @Test
    void insertAndSelectUser() {
        SysUser u = new SysUser();
        u.setUsername("alice");
        u.setPassword("hash");
        u.setRole("member");
        userMapper.insert(u);
        assertThat(u.getId()).isNotNull();
        assertThat(userMapper.selectById(u.getId()).getUsername()).isEqualTo("alice");
    }

    @Test
    void taskDefaultsToPending() {
        Task t = new Task();
        t.setProjectId(1L);
        t.setRequirement("做一个计算器");
        t.setStatus("pending");
        t.setAutoMode(false);
        t.setCreatedBy(1L);
        taskMapper.insert(t);
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("pending");
    }
}
