package com.magent.server.service;

import java.util.Map;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.magent.server.common.BizException;
import com.magent.server.entity.SysUser;
import com.magent.server.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public Map<String, Object> login(String username, String password) {
        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", username));
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BizException(401, "用户名或密码错误");
        }
        StpUtil.login(user.getId());
        return Map.of("token", StpUtil.getTokenValue(),
                "username", user.getUsername(),
                "role", user.getRole());
    }
}
