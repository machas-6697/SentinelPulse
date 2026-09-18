package com.sentinelpulse.service;

import com.sentinelpulse.model.SubscriberRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SubscriberService — Redis Hash CRUD for webhook subscriber registrations.
 *
 * Redis Key Schema:
 *   Key:    subscriber:{subscriber_id}
 *   Type:   Hash
 *   Fields: subscriber_id, target_url, event_type
 *
 * An index of all known subscriber IDs is maintained in a Redis Set:
 *   Key:    subscribers:index
 *   Type:   Set
 *   Members: all registered subscriber_id values
 *
 * This index allows the WebhookDispatchService to find all subscribers
 * matching a specific event_type without scanning all Redis keys.
 */
@Service
public class SubscriberService {

    private static final Logger log = LoggerFactory.getLogger(SubscriberService.class);
    private static final String SUBSCRIBER_PREFIX = "subscriber:";
    private static final String SUBSCRIBER_INDEX  = "subscribers:index";

    private final RedisTemplate<String, String> redisTemplate;

    public SubscriberService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Registers a new subscriber or overwrites an existing one with the same subscriber_id.
     *
     * @param request the subscriber registration payload
     * @return true if this is a newly created subscriber; false if it overwrote an existing one
     */
    public boolean register(SubscriberRequest request) {
        String redisKey = SUBSCRIBER_PREFIX + request.getSubscriberId();

        // Check if subscriber already exists (for 201 vs 200 status logic)
        boolean isNew = !Boolean.TRUE.equals(redisTemplate.hasKey(redisKey));

        // Store subscriber as Redis Hash
        redisTemplate.opsForHash().put(redisKey, "subscriber_id", request.getSubscriberId());
        redisTemplate.opsForHash().put(redisKey, "target_url",    request.getTargetUrl());
        redisTemplate.opsForHash().put(redisKey, "event_type",    request.getEventType());

        // Add to global index for fast event-type fan-out lookups
        redisTemplate.opsForSet().add(SUBSCRIBER_INDEX, request.getSubscriberId());

        log.info("[SUBSCRIBER] {} subscriber: id='{}', url='{}', event_type='{}'",
                isNew ? "Registered new" : "Updated existing",
                request.getSubscriberId(), request.getTargetUrl(), request.getEventType());

        return isNew;
    }

    /**
     * Finds all subscribers registered for a specific event type.
     * Iterates over the subscribers:index set and filters by event_type field.
     *
     * @param eventType the event type to match (e.g., "order.completed")
     * @return list of matching SubscriberRequest objects
     */
    public List<SubscriberRequest> findByEventType(String eventType) {
        Set<String> allIds = redisTemplate.opsForSet().members(SUBSCRIBER_INDEX);
        List<SubscriberRequest> matched = new ArrayList<>();

        if (allIds == null || allIds.isEmpty()) return matched;

        for (String id : allIds) {
            String redisKey = SUBSCRIBER_PREFIX + id;
            Map<Object, Object> data = redisTemplate.opsForHash().entries(redisKey);
            if (data.isEmpty()) continue;

            Object storedEventType = data.get("event_type");
            if (storedEventType != null && storedEventType.toString().equalsIgnoreCase(eventType)) {
                SubscriberRequest sub = new SubscriberRequest(
                        data.getOrDefault("subscriber_id", id).toString(),
                        data.getOrDefault("target_url", "").toString(),
                        storedEventType.toString()
                );
                matched.add(sub);
            }
        }

        log.debug("[SUBSCRIBER] Found {} subscriber(s) for event_type='{}'", matched.size(), eventType);
        return matched;
    }

    /**
     * Checks whether a subscriber with the given ID already exists in Redis.
     *
     * @param subscriberId the subscriber_id to check
     * @return true if the subscriber exists; false otherwise
     */
    public boolean exists(String subscriberId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(SUBSCRIBER_PREFIX + subscriberId));
    }
}
