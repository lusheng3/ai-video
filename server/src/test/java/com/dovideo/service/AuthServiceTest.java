package com.dovideo.service;

import com.dovideo.common.ErrorCode;
import com.dovideo.dto.AuthRequest;
import com.dovideo.dto.AuthResponse;
import com.dovideo.entity.UserInfo;
import com.dovideo.exception.BusinessException;
import com.dovideo.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserInfoMapper userInfoMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userInfoMapper, redisTemplate);
    }

    @Test
    void shouldRegisterUserAndIssueRedisToken() {
        when(userInfoMapper.selectOne(any())).thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            UserInfo u = invocation.getArgument(0);
            u.setId(1001L);
            return 1;
        }).when(userInfoMapper).insert(any(UserInfo.class));

        AuthRequest req = new AuthRequest();
        req.setUsername("hanyihao");
        req.setPassword("123456");
        req.setAccountType(1);

        AuthResponse resp = authService.register(req);

        assertEquals(1001L, resp.getUserId());
        assertEquals("hanyihao", resp.getUsername());
        assertEquals(0, resp.getAccountType());
        assertNotNull(resp.getToken());
        verify(valueOperations).set(eq("auth:token:" + resp.getToken()), any(), eq(Duration.ofHours(24)));
    }

    @Test
    void shouldTranslateDuplicateKeyExceptionToConflict409() {
        when(userInfoMapper.selectOne(any())).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_username")).when(userInfoMapper).insert(any(UserInfo.class));

        AuthRequest req = new AuthRequest();
        req.setUsername("duplicateUser");
        req.setPassword("123456");

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(req));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    void shouldRejectLoginWithWrongPassword() {
        UserInfo stored = UserInfo.builder()
                .id(1001L)
                .username("hanyihao")
                .passwordHash(authService.hashPassword("correct-pass"))
                .accountType(0)
                .build();
        when(userInfoMapper.selectOne(any())).thenReturn(stored);

        AuthRequest req = new AuthRequest();
        req.setUsername("hanyihao");
        req.setPassword("wrong-pass");

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(req));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }
}
