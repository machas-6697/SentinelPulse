package com.sentinelpulse.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouteServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        routeService = new RouteService(redisTemplate);
    }

    @Test
    void testResolveTargetUrlExactMatch() {
        when(hashOperations.entries("route:/api/v1/orders"))
                .thenReturn(Map.of("target_url", "http://localhost:9091", "methods", "GET,POST"));

        String target = routeService.resolveTargetUrl("/api/v1/orders");

        assertEquals("http://localhost:9091", target);
    }

    @Test
    void testResolveTargetUrlL1CacheHit() {
        when(hashOperations.entries("route:/api/v1/orders"))
                .thenReturn(Map.of("target_url", "http://localhost:9091", "methods", "GET,POST"));

        // Call 1: Cache Miss -> Fetches from Redis
        String target1 = routeService.resolveTargetUrl("/api/v1/orders");
        // Call 2: Cache Hit -> Served from in-memory L1 cache
        String target2 = routeService.resolveTargetUrl("/api/v1/orders");

        assertEquals("http://localhost:9091", target1);
        assertEquals("http://localhost:9091", target2);
        // Verify Redis was queried only once due to L1 cache
        verify(hashOperations, times(1)).entries("route:/api/v1/orders");
    }

    @Test
    void testResolveTargetUrlPrefixFallback() {
        // Subpath /api/v1/orders/123/items should strip back to /api/v1/orders
        when(hashOperations.entries("route:/api/v1/orders/123/items")).thenReturn(Collections.emptyMap());
        when(hashOperations.entries("route:/api/v1/orders/123")).thenReturn(Collections.emptyMap());
        when(hashOperations.entries("route:/api/v1/orders"))
                .thenReturn(Map.of("target_url", "http://localhost:9091", "methods", "GET,POST"));

        String target = routeService.resolveTargetUrl("/api/v1/orders/123/items");

        assertEquals("http://localhost:9091", target);
    }

    @Test
    void testResolveTargetUrlNotFound() {
        when(hashOperations.entries(anyString())).thenReturn(Collections.emptyMap());

        String target = routeService.resolveTargetUrl("/api/v1/unknown");

        assertNull(target);
    }

    @Test
    void testIsMethodAllowed() {
        when(hashOperations.entries("route:/api/v1/orders"))
                .thenReturn(Map.of("target_url", "http://localhost:9091", "methods", "GET,POST,PUT"));

        assertTrue(routeService.isMethodAllowed("/api/v1/orders", "GET"));
        assertTrue(routeService.isMethodAllowed("/api/v1/orders", "post"));
        assertTrue(routeService.isMethodAllowed("/api/v1/orders", "PUT"));
        assertFalse(routeService.isMethodAllowed("/api/v1/orders", "DELETE"));
    }

    @Test
    void testInvalidateCache() {
        when(hashOperations.entries("route:/api/v1/orders"))
                .thenReturn(Map.of("target_url", "http://localhost:9091", "methods", "GET,POST"));

        routeService.resolveTargetUrl("/api/v1/orders");
        routeService.invalidateCache();
        routeService.resolveTargetUrl("/api/v1/orders");

        // After invalidation, Redis should be queried a second time
        verify(hashOperations, times(2)).entries("route:/api/v1/orders");
    }
}
