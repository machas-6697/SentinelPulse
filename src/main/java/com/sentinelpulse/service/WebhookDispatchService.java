package com.sentinelpulse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpulse.metrics.SentinelMetrics;
import com.sentinelpulse.model.EventPayload;
import com.sentinelpulse.model.SubscriberRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebhookDispatchService — Asynchronous fan-out delivery with exponential backoff and DLQ.
 *
 * Dispatch lifecycle per subscriber:
 *   1. HTTP POST to subscriber.target_url with full event payload + idempotency headers.
 *   2. On 2xx → success, done.
 *   3. On 5xx or connection failure → exponential backoff retry:
 *        Attempt 1: wait 2s
 *        Attempt 2: wait 4s
 *        Attempt 3: wait 8s
 *   4. If all 3 attempts fail → RPUSH payload JSON to Redis List dlq:webhooks.
 *
 * Each dispatch runs on the dedicated webhook-worker- thread pool (ExecutorConfig).
 * The @Async annotation ensures dispatch never blocks the HTTP request thread.
 *
 * Outbound request headers added to every delivery:
 *   X-SentinelPulse-Event    → event_type string
 *   X-SentinelPulse-Delivery → unique UUID per delivery attempt
 *   Content-Type             → application/json
 */
@Service
public class WebhookDispatchService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchService.class);
    private static final String DLQ_KEY = "dlq:webhooks";

    private final WebClient webhookWebClient;
    private final RedisTemplate<String, String> redisTemplate;
    private final SentinelMetrics metrics;
    private final ObjectMapper objectMapper;

    @Value("${sentinelpulse.webhook.max-retries}")
    private int maxRetries;

    @Value("${sentinelpulse.webhook.backoff-base-ms}")
    private long backoffBaseMs;

    public WebhookDispatchService(@Qualifier("webhookWebClient") WebClient webhookWebClient,
                                  RedisTemplate<String, String> redisTemplate,
                                  SentinelMetrics metrics,
                                  ObjectMapper objectMapper) {
        this.webhookWebClient = webhookWebClient;
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /**
     * Fans out the event payload to all matched subscribers asynchronously.
     * Each subscriber dispatch runs as a separate async task on the webhook executor pool.
     *
     * @param payload     the inbound event payload
     * @param subscribers list of subscribers matching this event's event_type
     * @param rawBody     the original raw JSON body string (for DLQ persistence)
     */
    @Async("webhookExecutor")
    public void fanOut(EventPayload payload, List<SubscriberRequest> subscribers, String rawBody) {
        log.info("[DISPATCH] Fanning out event_type='{}' to {} subscriber(s)",
                payload.getEventType(), subscribers.size());

        for (SubscriberRequest subscriber : subscribers) {
            dispatchWithRetry(subscriber, payload, rawBody);
        }
    }

    /**
     * Dispatches to a single subscriber with exponential backoff retries.
     * This method blocks the webhook-worker thread during backoff sleep intervals —
     * this is intentional and isolated to the dedicated executor pool.
     *
     * @param subscriber the target subscriber
     * @param payload    the event payload to deliver
     * @param rawBody    original raw JSON (for DLQ persistence)
     */
    private void dispatchWithRetry(SubscriberRequest subscriber, EventPayload payload, String rawBody) {
        String deliveryId = java.util.UUID.randomUUID().toString();
        int attempt = 0;
        boolean success = false;

        while (attempt < maxRetries && !success) {
            attempt++;
            metrics.incrementWebhookDispatched();

            long delayMs = backoffBaseMs * (long) Math.pow(2, attempt - 1); // 2s, 4s, 8s

            if (attempt > 1) {
                log.info("[DISPATCH] Retry attempt {}/{} for subscriber='{}' after {}ms delay",
                        attempt, maxRetries, subscriber.getSubscriberId(), delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("[DISPATCH] Interrupted during backoff for subscriber='{}'",
                            subscriber.getSubscriberId());
                    break;
                }
            }

            success = doPost(subscriber, payload, deliveryId);

            if (success) {
                log.info("[DISPATCH] SUCCESS: subscriber='{}' on attempt {}/{}",
                        subscriber.getSubscriberId(), attempt, maxRetries);
            } else if (attempt < maxRetries) {
                log.warn("[DISPATCH] FAILED attempt {}/{} for subscriber='{}'",
                        attempt, maxRetries, subscriber.getSubscriberId());
            }
        }

        if (!success) {
            log.error("[DISPATCH] All {} retries exhausted for subscriber='{}' — pushing to DLQ",
                    maxRetries, subscriber.getSubscriberId());
            metrics.incrementWebhookDispatchFailed();
            pushToDlq(subscriber, payload, rawBody, deliveryId);
        }
    }

    /**
     * Performs the actual HTTP POST to the subscriber's target_url.
     *
     * @return true if the response status is 2xx; false on 5xx or connection error
     */
    private boolean doPost(SubscriberRequest subscriber, EventPayload payload, String deliveryId) {
        try {
            String responseStatus = webhookWebClient.post()
                    .uri(subscriber.getTargetUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-SentinelPulse-Event",    payload.getEventType())
                    .header("X-SentinelPulse-Delivery", deliveryId)
                    .header("X-SentinelPulse-Source",   "sentinelpulse-gateway")
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, resp -> {
                        log.warn("[DISPATCH] Subscriber='{}' returned 5xx: {}",
                                subscriber.getSubscriberId(), resp.statusCode().value());
                        return reactor.core.publisher.Mono.error(
                                new RuntimeException("5xx from subscriber: " + resp.statusCode().value()));
                    })
                    .bodyToMono(String.class)
                    .block();

            log.debug("[DISPATCH] POST to '{}' succeeded. Response snippet: {}",
                    subscriber.getTargetUrl(),
                    responseStatus != null && responseStatus.length() > 100
                            ? responseStatus.substring(0, 100) : responseStatus);
            return true;

        } catch (WebClientRequestException e) {
            log.warn("[DISPATCH] Connection error to subscriber='{}': {}",
                    subscriber.getSubscriberId(), e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("[DISPATCH] POST failed for subscriber='{}': {}",
                    subscriber.getSubscriberId(), e.getMessage());
            return false;
        }
    }

    /**
     * Pushes the exhausted dispatch record to the Redis DLQ (dlq:webhooks) via RPUSH.
     * The DLQ entry is a JSON object preserving full context for manual inspection
     * via RedisInsight.
     *
     * DLQ entry structure:
     * {
     *   "delivery_id":   "...",
     *   "subscriber_id": "...",
     *   "target_url":    "...",
     *   "event_type":    "...",
     *   "failed_at":     "ISO-8601 timestamp",
     *   "attempts":      3,
     *   "raw_payload":   "{original event JSON}"
     * }
     */
    private void pushToDlq(SubscriberRequest subscriber, EventPayload payload,
                            String rawBody, String deliveryId) {
        Map<String, Object> dlqEntry = new HashMap<>();
        dlqEntry.put("delivery_id",   deliveryId);
        dlqEntry.put("subscriber_id", subscriber.getSubscriberId());
        dlqEntry.put("target_url",    subscriber.getTargetUrl());
        dlqEntry.put("event_type",    payload.getEventType());
        dlqEntry.put("failed_at",     Instant.now().toString());
        dlqEntry.put("attempts",      maxRetries);
        dlqEntry.put("raw_payload",   rawBody);

        try {
            String dlqJson = objectMapper.writeValueAsString(dlqEntry);
            redisTemplate.opsForList().rightPush(DLQ_KEY, dlqJson);
            log.error("[DLQ] Pushed failed delivery to dlq:webhooks. delivery_id='{}', subscriber='{}'",
                    deliveryId, subscriber.getSubscriberId());
        } catch (JsonProcessingException e) {
            log.error("[DLQ] Failed to serialize DLQ entry for subscriber='{}': {}",
                    subscriber.getSubscriberId(), e.getMessage());
        }
    }
}
