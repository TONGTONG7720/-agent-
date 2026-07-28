package com.magent.server.controller;

import java.util.List;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.magent.server.common.AesUtil;
import com.magent.server.common.Result;
import com.magent.server.entity.LlmModel;
import com.magent.server.mapper.LlmModelMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final LlmModelMapper modelMapper;
    private final AesUtil aesUtil;

    public record CreateModelRequest(@NotBlank String name,
                                     @NotBlank String litellmModelName,
                                     String apiKey) {
    }

    /** 对外视图：key 只出现脱敏形式。 */
    public record ModelView(Long id, String name, String litellmModelName,
                            Boolean enabled, String apiKeyMasked) {
    }

    @SaCheckRole("admin")
    @PostMapping
    public Result<ModelView> create(@Validated @RequestBody CreateModelRequest req) {
        LlmModel model = new LlmModel();
        model.setName(req.name());
        model.setLitellmModelName(req.litellmModelName());
        model.setEnabled(true);
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            model.setApiKeyEnc(aesUtil.encrypt(req.apiKey()));
        }
        modelMapper.insert(model);
        return Result.ok(toView(model));
    }

    @GetMapping
    public Result<List<ModelView>> list() {
        return Result.ok(modelMapper.selectList(null).stream().map(this::toView).toList());
    }

    private ModelView toView(LlmModel m) {
        String masked = "";
        if (m.getApiKeyEnc() != null && !m.getApiKeyEnc().isBlank()) {
            masked = AesUtil.mask(aesUtil.decrypt(m.getApiKeyEnc()));
        }
        return new ModelView(m.getId(), m.getName(), m.getLitellmModelName(),
                m.getEnabled(), masked);
    }
}
