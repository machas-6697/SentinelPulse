package com.sentinelpulse.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * AuthService — API Key validation against Redis.
 *
 * Valid API keys are stored in Redis as String keys:
 *   apikeys:{key} → {client_id}
 *
 * The gateway checks whether the key from the X-API-Key header exists in Redis.
 * If the key is absent, authentication fails and the request is rejected with HTTP 401.
 *
 * To register a valid API key manually (via RedisInsight or redis-cli):
 *   SET apikeys:my-secret-key client-001
 *
 * Key design decisions:
 *   - No TTL on API keys (they are permanent until explicitly deleted).
 *   - The value stored is the client_id, used downstream for per-client rate limiting.
 *   - Validation is O(1) — a single Redis GET per inbound request.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String API_KEY_PREFIX = "apikeys:";

    private final RedisTemplate<String, String> redisTemplate;

    public AuthService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Validates an API key by looking it up in Redis.
     *
     * @param apiKey the raw value from the X-API-Key header
     * @return true if the key exists and is valid; false otherwise
     */
    public boolean isValidApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[AUTH] Rejected: X-API-Key header is missing or blank");
            return false;
        }
        String redisKey = API_KEY_PREFIX + apiKey;
        String clientId = redisTemplate.opsForValue().get(redisKey);
        if (clientId == null) {
            log.warn("[AUTH] Rejected: API key '{}' not found in Redis", maskKey(apiKey));
            return false;
        }
        log.debug("[AUTH] Accepted: API key maps to client_id='{}'", clientId);
        return true;
    }

    /**
     * Resolves the client_id for a given API key.
     * Returns null if the key does not exist.
     *
     * @param apiKey the raw value from the X-API-Key header
     * @return the client_id string stored in Redis, or null
     */
    public String resolveClientId(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        return redisTemplate.opsForValue().get(API_KEY_PREFIX + apiKey);
    }

    /**
     * Masks an API key for safe log output (shows first 4 chars + ***).
     */
    private String maskKey(String key) {
        if (key.length() <= 4) return "****";
        return key.substring(0, 4) + "****";
    }
}
