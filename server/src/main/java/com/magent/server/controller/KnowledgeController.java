package com.magent.server.controller;

import java.util.List;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.common.Result;
import com.magent.server.entity.KnowledgeDoc;
import com.magent.server.mapper.KnowledgeDocMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 知识库管理：上传 / 列表（仅元信息）/ 删除。 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeDocMapper knowledgeDocMapper;

    public record UploadRequest(@NotBlank String name, @NotBlank String content) {
    }

    /** 列表：不返回全文，只给名称/大小，避免大响应。 */
    @GetMapping
    public Result<List<DocMeta>> list() {
        List<DocMeta> metas = knowledgeDocMapper.selectList(
                        new QueryWrapper<KnowledgeDoc>().orderByDesc("id"))
                .stream()
                .map(d -> new DocMeta(d.getId(), d.getName(),
                        d.getContent() == null ? 0 : d.getContent().length()))
                .toList();
        return Result.ok(metas);
    }

    @SaCheckRole("admin")
    @PostMapping
    public Result<DocMeta> upload(@Validated @RequestBody UploadRequest req) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setName(req.name());
        doc.setContent(req.content());
        knowledgeDocMapper.insert(doc);
        return Result.ok(new DocMeta(doc.getId(), doc.getName(), req.content().length()));
    }

    @SaCheckRole("admin")
    @PostMapping("/{id}/delete")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeDocMapper.deleteById(id);
        return Result.ok();
    }

    public record DocMeta(Long id, String name, int size) {
    }
}
