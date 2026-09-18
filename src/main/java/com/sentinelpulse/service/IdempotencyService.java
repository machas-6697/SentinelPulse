package com.sentinelpulse.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * IdempotencyService — SHA-256 payload fingerprinting + Redis deduplication.
 *
 * Prevents duplicate webhook dispatches when:
 *   - A network drop causes the downstream service to retry emitting the same event.
 *   - A Postman re-send or automation script re-fires the same payload.
 *
 * Algorithm:
 *   1. Compute SHA-256 of the raw request body bytes.
 *   2. Attempt SET idempotency:{hash} "1" EX 86400 NX (SET if Not eXists).
 *   3. If Redis responds TRUE → payload is new → allow processing.
 *   4. If Redis responds FALSE → payload is a duplicate → silently drop, return 409.
 *
 * The NX flag is atomic in Redis — no race condition is possible even under
 * concurrent inbound duplicate events.
 *
 * TTL: 86,400 seconds (24 hours) — covers standard webhook retry windows without
 * causing long-term memory bloat.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String IDEMPOTENCY_PREFIX = "idempotency:";

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${sentinelpulse.idempotency.ttl-seconds}")
    private long ttlSeconds;

    public IdempotencyService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Checks whether the payload is new (not seen before within the TTL window).
     * If new, atomically marks it as seen in Redis.
     *
     * @param rawBody the raw HTTP request body bytes of the event
     * @return true if this is a new (unique) event and dispatch should proceed;
     *         false if this is a duplicate and should be silently dropped
     */
    public boolean isNewEvent(byte[] rawBody) {
        String hash = computeSha256(rawBody);
        String redisKey = IDEMPOTENCY_PREFIX + hash;

        // SET NX EX — atomic: returns TRUE only if key did not already exist
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", ttlSeconds, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(isNew)) {
            log.debug("[IDEMPOTENCY] New event accepted. Hash={}", hash);
            return true;
        } else {
            log.warn("[IDEMPOTENCY] Duplicate event detected and dropped. Hash={}", hash);
            return false;
        }
    }

    /**
     * Computes a SHA-256 hex digest of the given byte array.
     * Used as the deduplication fingerprint for event payloads.
     *
     * @param data the raw bytes to hash
     * @return lowercase hex string of the SHA-256 digest
     */
    public String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java spec — this cannot happen in practice
            throw new RuntimeException("[IDEMPOTENCY] SHA-256 algorithm unavailable", e);
        }
    }

    /**
     * Convenience overload: computes SHA-256 from a UTF-8 string.
     *
     * @param body the raw event body as a string
     * @return lowercase hex SHA-256 digest
     */
    public String computeSha256(String body) {
        return computeSha256(body.getBytes(StandardCharsets.UTF_8));
    }
}
