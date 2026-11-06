package com.dovideo.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dovideo.common.ErrorCode;
import com.dovideo.dto.AuthRequest;
import com.dovideo.dto.AuthResponse;
import com.dovideo.entity.UserInfo;
import com.dovideo.enums.AccountTypeEnum;
import com.dovideo.exception.BusinessException;
import com.dovideo.mapper.UserInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 用户认证与会话管理服务（Service层）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String ATTR_USER_ID = "CURRENT_USER_ID";
    public static final String ATTR_ACCOUNT_TYPE = "CURRENT_ACCOUNT_TYPE";
    private static final String TOKEN_KEY_PREFIX = "auth:token:";
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final String PASSWORD_PREFIX = "pbkdf2";
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_KEY_BITS = 256;
    private static final int PASSWORD_SALT_BYTES = 16;

    private final UserInfoMapper userInfoMapper;
    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 用户注册：利用数据库 uk_username 唯一索引防御并发同名注册
     */
    public AuthResponse register(AuthRequest request) {
        String username = request.getUsername().trim();
        UserInfo existing = userInfoMapper.selectOne(
                new LambdaQueryWrapper<UserInfo>().eq(UserInfo::getUsername, username));
        if (existing != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }

        String nickname = (request.getNickname() == null || request.getNickname().isBlank())
                ? username
                : request.getNickname().trim();

        UserInfo user = UserInfo.builder()
                .username(username)
                .passwordHash(hashPassword(request.getPassword()))
                .nickname(nickname)
                // 账户等级只能由后台或支付回调修改，绝不能信任注册请求。
                .accountType(AccountTypeEnum.FREE.getCode())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .isDeleted(0)
                .build();

        try {
            userInfoMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }

        return issueSessionToken(user);
    }

    /**
     * 用户登录：校验密码哈希并签发 Redis 会话 Token
     */
    public AuthResponse login(AuthRequest request) {
        String username = request.getUsername().trim();
        UserInfo user = userInfoMapper.selectOne(
                new LambdaQueryWrapper<UserInfo>().eq(UserInfo::getUsername, username));
        if (user == null || !passwordMatches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!user.getPasswordHash().startsWith(PASSWORD_PREFIX + "$")) {
            user.setPasswordHash(hashPassword(request.getPassword()));
            user.setUpdateTime(LocalDateTime.now());
            userInfoMapper.updateById(user);
        }
        return issueSessionToken(user);
    }

    /**
     * 校验 Bearer Token 并返回会话信息
     */
    public AuthResponse verifyToken(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        if (token == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未提供有效的登录凭证");
        }
        String json = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token);
        if (json == null || json.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已过期，请重新登录");
        }
        return JSON.parseObject(json, AuthResponse.class);
    }

    /**
     * 退出登录：清理 Redis 会话
     */
    public void logout(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        if (token != null) {
            redisTemplate.delete(TOKEN_KEY_PREFIX + token);
        }
    }

    private AuthResponse issueSessionToken(UserInfo user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        AuthResponse session = AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .accountType(user.getAccountType())
                .token(token)
                .build();
        redisTemplate.opsForValue().set(
                TOKEN_KEY_PREFIX + token,
                JSON.toJSONString(session),
                SESSION_TTL);
        return session;
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        if (authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7).trim();
        }
        return authorizationHeader.trim();
    }

    public String hashPassword(String rawPassword) {
        byte[] salt = new byte[PASSWORD_SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(rawPassword, salt, PASSWORD_ITERATIONS);
        return String.join("$",
                PASSWORD_PREFIX,
                String.valueOf(PASSWORD_ITERATIONS),
                Base64.getEncoder().withoutPadding().encodeToString(salt),
                Base64.getEncoder().withoutPadding().encodeToString(hash));
    }

    public boolean passwordMatches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) {
            return false;
        }
        if (!storedPassword.startsWith(PASSWORD_PREFIX + "$")) {
            return MessageDigest.isEqual(
                    legacySha256(rawPassword).getBytes(StandardCharsets.UTF_8),
                    storedPassword.getBytes(StandardCharsets.UTF_8));
        }
        try {
            String[] parts = storedPassword.split("\\$", -1);
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword, salt, iterations);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String legacySha256(String rawPassword) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(("dovideo-salt-2026:" + rawPassword)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }

    private byte[] derive(String rawPassword, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(
                rawPassword.toCharArray(), salt, iterations, PASSWORD_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("密码哈希失败", ex);
        } finally {
            spec.clearPassword();
        }
    }
}
