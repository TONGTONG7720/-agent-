package com.magent.server.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.common.BizException;
import com.magent.server.common.Result;
import com.magent.server.config.AppProps;
import com.magent.server.entity.Artifact;
import com.magent.server.mapper.ArtifactMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactMapper artifactMapper;
    private final AppProps props;

    @GetMapping("/tasks/{taskId}/artifacts")
    public Result<List<Artifact>> list(@PathVariable Long taskId) {
        return Result.ok(artifactMapper.selectList(
                new QueryWrapper<Artifact>().eq("task_id", taskId).orderByAsc("id")));
    }

    @GetMapping("/artifacts/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long id) {
        Artifact artifact = artifactMapper.selectById(id);
        if (artifact == null) {
            throw new BizException(404, "产物不存在");
        }
        Path base = Path.of(props.getWorkspaceRoot(), "T" + artifact.getTaskId())
                .toAbsolutePath().normalize();
        Path file = base.resolve(artifact.getPath()).normalize();
        // 防路径逃逸：规范化后必须仍在任务目录内
        if (!file.startsWith(base)) {
            throw new BizException(400, "非法产物路径");
        }
        if (!Files.isRegularFile(file)) {
            throw new BizException(404, "产物文件缺失");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + artifact.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }
}
