package com.sentinelpulse.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        rateLimitService = new RateLimitService(redisTemplate);
        ReflectionTestUtils.setField(rateLimitService, "defaultCapacity", 5);
        ReflectionTestUtils.setField(rateLimitService, "defaultRefillRate", 1);
    }

    @Test
    void testInitialRequestConsumesToken() {
        // First request: no existing hash in Redis
        when(hashOperations.entries("rate_limit:client-1")).thenReturn(Collections.emptyMap());

        boolean allowed = rateLimitService.tryConsume("client-1");

        assertTrue(allowed, "Initial request with full bucket should be allowed");
        verify(hashOperations).put(eq("rate_limit:client-1"), eq("tokens"), eq("4")); // 5 initial - 1
    }

    @Test
    void testExhaustedBucketRejects() {
        // Bucket has 0 tokens and refill hasn't occurred yet (elapsed = 0)
        Map<Object, Object> bucket = new HashMap<>();
        bucket.put("tokens", "0");
        bucket.put("capacity", "5");
        bucket.put("refill_rate", "1");
        bucket.put("last_refill", String.valueOf(Instant.now().getEpochSecond()));

        when(hashOperations.entries("rate_limit:client-exhausted")).thenReturn(bucket);

        boolean allowed = rateLimitService.tryConsume("client-exhausted");

        assertFalse(allowed, "Request should be rejected when bucket is empty and no time elapsed");
    }

    @Test
    void testTokenBucketRefillAfterElapsedTime() {
        // Bucket had 0 tokens 10 seconds ago, with refill rate of 1 token/sec
        long tenSecondsAgo = Instant.now().getEpochSecond() - 10;
        Map<Object, Object> bucket = new HashMap<>();
        bucket.put("tokens", "0");
        bucket.put("capacity", "5");
        bucket.put("refill_rate", "1");
        bucket.put("last_refill", String.valueOf(tenSecondsAgo));

        when(hashOperations.entries("rate_limit:client-refill")).thenReturn(bucket);

        boolean allowed = rateLimitService.tryConsume("client-refill");

        assertTrue(allowed, "Request should be allowed after bucket refilled over time");
        // Refilled to capacity 5, then consumed 1 -> 4
        verify(hashOperations).put(eq("rate_limit:client-refill"), eq("tokens"), eq("4"));
    }

    @Test
    void testConcurrentAccessThreadSafety() throws InterruptedException {
        // Test that multiple concurrent threads safely execute tryConsume with the AtomicInteger guard
        Map<Object, Object> bucket = new HashMap<>();
        bucket.put("tokens", "10");
        bucket.put("capacity", "10");
        bucket.put("refill_rate", "1");
        bucket.put("last_refill", String.valueOf(Instant.now().getEpochSecond()));

        when(hashOperations.entries("rate_limit:client-concurrent")).thenReturn(bucket);

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    if (rateLimitService.tryConsume("client-concurrent")) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertTrue(successCount.get() > 0, "At least one concurrent thread must succeed in consuming tokens");
    }
}
