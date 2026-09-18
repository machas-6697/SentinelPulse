package com.sentinelpulse.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        authService = new AuthService(redisTemplate);
    }

    @Test
    void testValidApiKeyReturnsTrue() {
        when(valueOperations.get("apikeys:valid-key-123")).thenReturn("client-001");

        assertTrue(authService.isValidApiKey("valid-key-123"));
        assertEquals("client-001", authService.resolveClientId("valid-key-123"));
    }

    @Test
    void testInvalidApiKeyReturnsFalse() {
        when(valueOperations.get("apikeys:invalid-key")).thenReturn(null);

        assertFalse(authService.isValidApiKey("invalid-key"));
        assertNull(authService.resolveClientId("invalid-key"));
    }

    @Test
    void testMissingOrBlankApiKey() {
        assertFalse(authService.isValidApiKey(null));
        assertFalse(authService.isValidApiKey("   "));
        assertNull(authService.resolveClientId(null));
        assertNull(authService.resolveClientId(""));
    }
}
