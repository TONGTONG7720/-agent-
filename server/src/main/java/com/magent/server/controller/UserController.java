package com.magent.server.controller;

import java.util.List;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.common.BizException;
import com.magent.server.common.Result;
import com.magent.server.entity.SysUser;
import com.magent.server.mapper.SysUserMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public record CreateUserRequest(@NotBlank String username,
                                    @NotBlank String password,
                                    String role) {
    }

    /** 对外视图：绝不含密码。 */
    public record UserView(Long id, String username, String role) {
        static UserView of(SysUser u) {
            return new UserView(u.getId(), u.getUsername(), u.getRole());
        }
    }

    @SaCheckRole("admin")
    @PostMapping
    public Result<UserView> create(@Validated @RequestBody CreateUserRequest req) {
        if (userMapper.selectCount(
                new QueryWrapper<SysUser>().eq("username", req.username())) > 0) {
            throw new BizException(409, "用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setRole("admin".equals(req.role()) ? "admin" : "member");
        userMapper.insert(user);
        return Result.ok(UserView.of(user));
    }

    @SaCheckRole("admin")
    @GetMapping
    public Result<List<UserView>> list() {
        return Result.ok(userMapper.selectList(null).stream().map(UserView::of).toList());
    }
}
