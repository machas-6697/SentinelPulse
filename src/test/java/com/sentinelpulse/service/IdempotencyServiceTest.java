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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdempotencyServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idempotencyService = new IdempotencyService(redisTemplate);
        ReflectionTestUtils.setField(idempotencyService, "ttlSeconds", 86400L);
    }

    @Test
    void testComputeSha256Consistent() {
        String payload = "{\"event\":\"test\"}";
        String hash1 = idempotencyService.computeSha256(payload);
        String hash2 = idempotencyService.computeSha256(payload);

        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
    }

    @Test
    void testIsNewEventTrueWhenKeyAbsent() {
        byte[] payload = "{\"order_id\": 123}".getBytes();

        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(86400L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);

        boolean isNew = idempotencyService.isNewEvent(payload);

        assertTrue(isNew, "Should be considered a new event when Redis key was absent");
        verify(valueOperations, times(1)).setIfAbsent(startsWith("idempotency:"), eq("1"), eq(86400L), eq(TimeUnit.SECONDS));
    }

    @Test
    void testIsNewEventFalseWhenKeyAlreadyExists() {
        byte[] payload = "{\"order_id\": 123}".getBytes();

        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(86400L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        boolean isNew = idempotencyService.isNewEvent(payload);

        assertFalse(isNew, "Should be considered duplicate when Redis key already exists");
    }
}
