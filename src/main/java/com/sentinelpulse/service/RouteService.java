package com.sentinelpulse.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * RouteService — Redis-backed route lookup for the transparent proxy.
 *
 * Routes are seeded into Redis on startup by RouteSeeder (ApplicationRunner).
 * Each route is stored as a Redis Hash:
 *
 *   Key:    route:{path}
 *   Fields:
 *     target_url → the downstream service base URL
 *     methods    → comma-separated allowed HTTP methods (e.g., "GET,POST")
 *
 * Example:
 *   HSET route:/api/v1/orders  target_url http://localhost:9091  methods GET,POST,PUT,DELETE
 *
 * Lookup is O(1) — a single Redis HGETALL per inbound request.
 * No database is involved. Redis IS the route configuration store.
 */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);
    private static final String ROUTE_PREFIX = "route:";

    private static final long CACHE_TTL_MS = 60_000L; // 60-second L1 Route Cache TTL

    /**
     * L1 In-Memory Route Cache entry holding target URL, allowed methods, and timestamp.
     */
    public record CachedRoute(String targetUrl, String methods, long cachedAt) {
        public boolean isExpired(long ttlMs) {
            return (System.currentTimeMillis() - cachedAt) > ttlMs;
        }
    }

    /**
     * High-throughput thread-safe L1 route cache.
     * Maps route path -> CachedRoute. Eliminates Redis roundtrip latency for hot routes.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, CachedRoute> routeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final RedisTemplate<String, String> redisTemplate;

    public RouteService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Clears the in-memory L1 route cache. Called by RouteSeeder on startup or admin reload.
     */
    public void invalidateCache() {
        routeCache.clear();
        log.info("[ROUTE-CACHE] L1 In-Memory Route Cache invalidated");
    }

    /**
     * Looks up the target URL for a given inbound request path.
     * Checks L1 In-Memory Cache first; falls back to Redis L2 store on cache miss.
     *
     * Path matching strategy: exact match first, then prefix-based fallback.
     * This handles both exact routes (/api/v1/orders) and sub-paths (/api/v1/orders/123).
     *
     * @param requestPath the full URI path from the inbound HTTP request
     * @return the downstream target_url string, or null if no route matches
     */
    public String resolveTargetUrl(String requestPath) {
        CachedRoute route = findRoute(requestPath);
        return route != null ? route.targetUrl() : null;
    }

    /**
     * Checks whether a given HTTP method is permitted for a route path.
     *
     * @param requestPath the inbound URI path (exact or subpath)
     * @param method      the HTTP method (GET, POST, etc.)
     * @return true if the method is allowed; false otherwise
     */
    public boolean isMethodAllowed(String requestPath, String method) {
        CachedRoute route = findRoute(requestPath);
        if (route == null || route.methods() == null) return false;

        return java.util.Arrays.stream(route.methods().split(","))
                .map(String::trim)
                .anyMatch(m -> m.equalsIgnoreCase(method));
    }

    /**
     * Finds a route definition by exact path match or prefix-based fallback.
     */
    private CachedRoute findRoute(String requestPath) {
        // Attempt 1: exact path match
        CachedRoute route = fetchRoute(requestPath);
        if (route != null) return route;

        // Attempt 2: prefix match — strip trailing segments until a route is found
        String trimmed = requestPath;
        while (trimmed.contains("/") && trimmed.length() > 1) {
            int lastSlash = trimmed.lastIndexOf('/');
            trimmed = (lastSlash > 0) ? trimmed.substring(0, lastSlash) : "/";
            route = fetchRoute(trimmed);
            if (route != null) return route;
        }

        return null;
    }

    /**
     * Reads route data from L1 In-Memory Cache if valid, or fetches from Redis Hash.
     */
    private CachedRoute fetchRoute(String path) {
        // 1. Check L1 in-memory cache
        CachedRoute cached = routeCache.get(path);
        if (cached != null && !cached.isExpired(CACHE_TTL_MS)) {
            return cached;
        }

        // 2. Fetch from Redis L2
        String redisKey = ROUTE_PREFIX + path;
        Map<Object, Object> routeData = redisTemplate.opsForHash().entries(redisKey);
        if (routeData.isEmpty()) {
            return null;
        }

        Object targetUrlObj = routeData.get("target_url");
        Object methodsObj   = routeData.get("methods");

        String targetUrl = (targetUrlObj != null) ? targetUrlObj.toString() : null;
        String methods   = (methodsObj != null) ? methodsObj.toString() : "GET";

        CachedRoute newEntry = new CachedRoute(targetUrl, methods, System.currentTimeMillis());
        routeCache.put(path, newEntry);
        return newEntry;
    }
}
