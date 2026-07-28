package com.magent.server.config;

import java.util.List;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.magent.server.entity.SysUser;
import com.magent.server.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }

    /** Sa-Token 角色来源：按登录 id 查库。 */
    @Component
    @RequiredArgsConstructor
    public static class StpInterfaceImpl implements StpInterface {

        private final SysUserMapper userMapper;

        @Override
        public List<String> getRoleList(Object loginId, String loginType) {
            SysUser user = userMapper.selectById(Long.valueOf(loginId.toString()));
            return user == null ? List.of() : List.of(user.getRole());
        }

        @Override
        public List<String> getPermissionList(Object loginId, String loginType) {
            return List.of();
        }
    }
}
