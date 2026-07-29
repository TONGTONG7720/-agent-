package com.magent.server.controller;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.common.BizException;
import com.magent.server.common.Result;
import com.magent.server.config.AppProps;
import com.magent.server.entity.Artifact;
import com.magent.server.mapper.ArtifactMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
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

    private static final long MAX_PREVIEW_BYTES = 1024 * 1024;   // 1MB 预览上限

    /** 解析产物真实文件路径并做防逃逸校验。 */
    private Path resolveSafe(Artifact artifact) {
        Path base = Path.of(props.getWorkspaceRoot(), "T" + artifact.getTaskId())
                .toAbsolutePath().normalize();
        Path file = base.resolve(artifact.getPath()).normalize();
        if (!file.startsWith(base)) {
            throw new BizException(400, "非法产物路径");
        }
        return file;
    }

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
        Path file = resolveSafe(artifact);
        if (!Files.isRegularFile(file)) {
            throw new BizException(404, "产物文件缺失");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + artifact.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    /** 在线预览：返回文本内容（>1MB 拒绝，防逃逸）。 */
    @GetMapping("/artifacts/{id}/content")
    public Result<String> content(@PathVariable Long id) {
        Artifact artifact = artifactMapper.selectById(id);
        if (artifact == null) {
            throw new BizException(404, "产物不存在");
        }
        Path file = resolveSafe(artifact);
        if (!Files.isRegularFile(file)) {
            throw new BizException(404, "产物文件缺失");
        }
        try {
            if (Files.size(file) > MAX_PREVIEW_BYTES) {
                throw new BizException(413, "文件过大，请下载查看");
            }
            return Result.ok(Files.readString(file, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new BizException(500, "读取产物失败");
        }
    }

    /** 任务全部产物打包 zip 下载。 */
    @GetMapping("/tasks/{taskId}/artifacts/zip")
    public ResponseEntity<ByteArrayResource> zip(@PathVariable Long taskId) {
        Path base = Path.of(props.getWorkspaceRoot(), "T" + taskId).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new BizException(404, "任务产物目录不存在");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos); Stream<Path> walk = Files.walk(base)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                zos.putNextEntry(new ZipEntry(base.relativize(p).toString().replace('\\', '/')));
                zos.write(Files.readAllBytes(p));
                zos.closeEntry();
            }
        } catch (java.io.IOException e) {
            throw new BizException(500, "打包失败");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"task-" + taskId + "-artifacts.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new ByteArrayResource(bos.toByteArray()));
    }
}
