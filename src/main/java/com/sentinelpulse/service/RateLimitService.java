package com.sentinelpulse.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RateLimitService — Token-Bucket algorithm backed by Redis.
 *
 * Algorithm:
 *   Each client has a virtual "bucket" holding tokens. Tokens are consumed on
 *   each request and refilled at a fixed rate over time. When the bucket is empty,
 *   requests are rejected with HTTP 429 Too Many Requests.
 *
 * Redis key: rate_limit:{client_id}
 * Redis type: Hash with fields:
 *   tokens       → current token count (integer string)
 *   last_refill  → epoch seconds of last refill timestamp
 *   capacity     → maximum bucket size (overrides default if present)
 *   refill_rate  → tokens per second (overrides default if present)
 *
 * Per-client configuration:
 *   SentinelPulse first checks Redis for a client-specific rate_limit:{client_id} hash.
 *   If absent, falls back to application.yml defaults:
 *     sentinelpulse.rate-limit.default-capacity    (10 tokens)
 *     sentinelpulse.rate-limit.default-refill-rate (2 tokens/sec)
 *
 * Thread-safety:
 *   An AtomicInteger is used as a CAS guard to prevent concurrent threads
 *   from double-spending the same token for the same client. The Redis Hash
 *   is the authoritative state; AtomicInteger guards the JVM-level race.
 *
 * Design note: A fully distributed lock (e.g., Redlock) would be correct in
 * a multi-node deployment. For this single-node setup, the AtomicInteger
 * guard combined with Redis is sufficient and avoids lock overhead.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final String RATE_LIMIT_PREFIX = "rate_limit:";

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${sentinelpulse.rate-limit.default-capacity}")
    private int defaultCapacity;

    @Value("${sentinelpulse.rate-limit.default-refill-rate}")
    private int defaultRefillRate;

    /**
     * JVM-level concurrency guard: one AtomicInteger per client_id.
     * Acts as a lightweight CAS mutex to prevent simultaneous threads from
     * concurrently issuing the same client's tokens during a refill cycle.
     * Uses ConcurrentHashMap for thread-safe guard instantiation.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>
            clientLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public RateLimitService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Attempts to consume one token from the client's bucket.
     *
     * @param clientId the resolved client identifier (from API key lookup)
     * @return true if a token was successfully consumed (request allowed);
     *         false if the bucket is empty (request should be rejected with 429)
     */
    public boolean tryConsume(String clientId) {
        AtomicInteger lock = clientLocks.computeIfAbsent(clientId, k -> new AtomicInteger(0));

        // Spin-wait CAS guard: wait briefly if another thread is currently updating the bucket
        long deadline = System.currentTimeMillis() + 500;
        while (!lock.compareAndSet(0, 1)) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("[RATE-LIMIT] Concurrent access timeout for client='{}', rejecting", clientId);
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        try {
            return consumeToken(clientId);
        } finally {
            lock.set(0); // Release the CAS guard
        }
    }

    /**
     * Core token-bucket logic: refill based on elapsed time, then consume one token.
     */
    private boolean consumeToken(String clientId) {
        String redisKey = RATE_LIMIT_PREFIX + clientId;
        Map<Object, Object> bucketData = redisTemplate.opsForHash().entries(redisKey);

        long now = Instant.now().getEpochSecond();
        int capacity;
        int refillRate;
        int tokens;
        long lastRefill;

        if (bucketData.isEmpty()) {
            // No client-specific config found — apply global defaults
            capacity = defaultCapacity;
            refillRate = defaultRefillRate;
            tokens = defaultCapacity; // start full
            lastRefill = now;
            log.debug("[RATE-LIMIT] New bucket for client='{}' with defaults (cap={}, rate={})",
                    clientId, capacity, refillRate);
        } else {
            // Load existing bucket state from Redis
            capacity   = parseInt(bucketData, "capacity",    defaultCapacity);
            refillRate = parseInt(bucketData, "refill_rate", defaultRefillRate);
            tokens     = parseInt(bucketData, "tokens",      defaultCapacity);
            lastRefill = parseLong(bucketData, "last_refill", now);
        }

        // ── Refill phase ──────────────────────────────────────────────────────
        long elapsed = now - lastRefill;
        if (elapsed > 0) {
            int refilled = (int) (elapsed * refillRate);
            tokens = Math.min(capacity, tokens + refilled);
            lastRefill = now;
            log.debug("[RATE-LIMIT] Refilled {} tokens for client='{}', new total={}",
                    refilled, clientId, tokens);
        }

        // ── Consume phase ─────────────────────────────────────────────────────
        if (tokens <= 0) {
            // Write back state even on rejection to keep timestamps accurate
            persistBucket(redisKey, tokens, lastRefill, capacity, refillRate);
            log.warn("[RATE-LIMIT] Bucket empty for client='{}' — HTTP 429", clientId);
            return false;
        }

        tokens--;
        persistBucket(redisKey, tokens, lastRefill, capacity, refillRate);
        log.debug("[RATE-LIMIT] Token consumed for client='{}', remaining={}", clientId, tokens);
        return true;
    }

    /**
     * Persists the current bucket state back to Redis as a Hash.
     */
    private void persistBucket(String redisKey, int tokens, long lastRefill,
                                int capacity, int refillRate) {
        redisTemplate.opsForHash().put(redisKey, "tokens",      String.valueOf(tokens));
        redisTemplate.opsForHash().put(redisKey, "last_refill", String.valueOf(lastRefill));
        redisTemplate.opsForHash().put(redisKey, "capacity",    String.valueOf(capacity));
        redisTemplate.opsForHash().put(redisKey, "refill_rate", String.valueOf(refillRate));
    }

    private int parseInt(Map<Object, Object> map, String field, int defaultVal) {
        Object val = map.get(field);
        if (val == null) return defaultVal;
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private long parseLong(Map<Object, Object> map, String field, long defaultVal) {
        Object val = map.get(field);
        if (val == null) return defaultVal;
        try { return Long.parseLong(val.toString()); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
