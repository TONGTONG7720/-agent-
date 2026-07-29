package com.magent.server.service;

import java.util.List;

import com.magent.server.entity.KnowledgeDoc;
import com.magent.server.mapper.KnowledgeDocMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** B4-3a：知识库 BM25-lite 检索。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KnowledgeServiceTest {

    @Autowired
    private KnowledgeService knowledgeService;
    @Autowired
    private KnowledgeDocMapper knowledgeDocMapper;

    private void seed(String name, String content) {
        KnowledgeDoc d = new KnowledgeDoc();
        d.setName(name);
        d.setContent(content);
        knowledgeDocMapper.insert(d);
    }

    @Test
    void retrieveReturnsMostRelevantDoc() {
        seed("编码规范", "所有 Python 函数必须写类型注解，禁止使用 eval，命名用蛇形 snake_case。");
        seed("部署手册", "使用 Docker Compose 部署，端口映射到 8080，数据库连接 MySQL。");
        String ctx = knowledgeService.retrieve("写 Python 函数的类型注解要求", 3, 2000);
        assertThat(ctx).contains("类型注解");
        assertThat(ctx).doesNotContain("Docker Compose");   // 不相关文档不应命中
    }

    @Test
    void retrieveEmptyWhenNoDocs() {
        assertThat(knowledgeService.retrieve("任意查询", 3, 2000)).isEmpty();
    }

    @Test
    void retrieveBoundedByMaxChars() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            big.append("Python 类型注解规范条目").append(i).append("。");
        }
        seed("长文档", big.toString());
        String ctx = knowledgeService.retrieve("Python 类型注解", 5, 200);
        assertThat(ctx.length()).isLessThanOrEqualTo(200);
    }
}
