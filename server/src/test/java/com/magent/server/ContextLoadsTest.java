package com.magent.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ContextLoadsTest {

    @Test
    void contextLoads() {
        // 上下文能启动 = 依赖装配 + schema.sql 在 H2(MySQL模式) 下执行成功
    }
}
