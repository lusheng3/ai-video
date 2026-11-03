package com.dovideo.config;

import com.dovideo.dto.AuthResponse;
import com.dovideo.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录鉴权拦截器：解析 Authorization 请求头，将 userId 与 accountType 注入请求上下文
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authHeader = request.getHeader("Authorization");
        AuthResponse session = authService.verifyToken(authHeader);
        request.setAttribute(AuthService.ATTR_USER_ID, session.getUserId());
        request.setAttribute(AuthService.ATTR_ACCOUNT_TYPE, session.getAccountType());
        return true;
    }
}
