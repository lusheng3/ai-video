package com.dovideo.controller;

import com.dovideo.common.Result;
import com.dovideo.dto.AuthRequest;
import com.dovideo.dto.AuthResponse;
import com.dovideo.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户控制层（Controller层）：处理注册、登录、退出与当前身份查询
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<AuthResponse> register(@Valid @RequestBody AuthRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        return Result.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(authorization);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> currentUser(
            @RequestAttribute(AuthService.ATTR_USER_ID) Long userId,
            @RequestAttribute(AuthService.ATTR_ACCOUNT_TYPE) Integer accountType) {
        return Result.ok(Map.of(
                "userId", userId,
                "accountType", accountType
        ));
    }
}
