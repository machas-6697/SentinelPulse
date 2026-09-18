package com.sentinelpulse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpulse.model.EventPayload;
import com.sentinelpulse.model.SubscriberRequest;
import com.sentinelpulse.service.IdempotencyService;
import com.sentinelpulse.service.SubscriberService;
import com.sentinelpulse.service.WebhookDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EventController — Webhook event intake endpoint.
 *
 * Endpoint:   POST /api/v1/events
 * Auth:       Required (X-API-Key validated by AuthFilter)
 * Rate Limit: Applied (RateLimitFilter)
 *
 * Receives event payloads emitted by downstream microservices and fans them out
 * asynchronously to all registered subscribers matching the event_type.
 *
 * Pipeline:
 *   1. Validate required fields (event_type, source)
 *   2. Idempotency check — SHA-256(raw body) → Redis SET NX EX 86400
 *      → Duplicate: return 409 Conflict, drop silently
 *      → New: proceed
 *   3. Subscriber lookup — find all matching event_type subscribers from Redis
 *   4. Async fan-out — dispatch to each subscriber via WebhookDispatchService
 *   5. Return 202 Accepted immediately (dispatch is non-blocking)
 *
 * Request body:
 * {
 *   "event_type": "order.completed",
 *   "source":     "order-service",
 *   "data": {
 *     "order_id": "ORD-7891",
 *     "amount":   149.99
 *   }
 * }
 *
 * Responses:
 *   202 Accepted  → Event accepted and dispatch initiated
 *   409 Conflict  → Duplicate event (idempotency check failed)
 *   400 Bad Req   → Missing required fields
 *   404 Not Found → No subscribers registered for this event_type
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final IdempotencyService idempotencyService;
    private final SubscriberService subscriberService;
    private final WebhookDispatchService webhookDispatchService;
    private final ObjectMapper objectMapper;

    public EventController(IdempotencyService idempotencyService,
                           SubscriberService subscriberService,
                           WebhookDispatchService webhookDispatchService,
                           ObjectMapper objectMapper) {
        this.idempotencyService   = idempotencyService;
        this.subscriberService    = subscriberService;
        this.webhookDispatchService = webhookDispatchService;
        this.objectMapper         = objectMapper;
    }

    /**
     * Ingests an event payload, enforces idempotency, and fans out to subscribers.
     *
     * @param rawBody the raw request body as String for SHA-256 fingerprinting
     * @param payload the deserialized event payload
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestBody String rawBody) {

        // ── Step 1: Deserialize payload ───────────────────────────────────────
        EventPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, EventPayload.class);
        } catch (Exception e) {
            return badRequest("Invalid JSON body: " + e.getMessage());
        }

        // ── Step 2: Validate required fields ──────────────────────────────────
        if (payload.getEventType() == null || payload.getEventType().isBlank()) {
            return badRequest("event_type is required and cannot be blank");
        }
        if (payload.getSource() == null || payload.getSource().isBlank()) {
            return badRequest("source is required and cannot be blank");
        }

        log.info("[EVENT-CTRL] Received event: type='{}', source='{}'",
                payload.getEventType(), payload.getSource());

        // ── Step 3: Idempotency check ─────────────────────────────────────────
        boolean isNew = idempotencyService.isNewEvent(rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!isNew) {
            log.warn("[EVENT-CTRL] Duplicate event dropped: type='{}', source='{}'",
                    payload.getEventType(), payload.getSource());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status",     409);
            body.put("error",      "Conflict");
            body.put("message",    "Duplicate event detected. This payload has already been processed within the last 24 hours.");
            body.put("event_type", payload.getEventType());
            return ResponseEntity.status(409).body(body);
        }

        // ── Step 4: Find matching subscribers ─────────────────────────────────
        List<SubscriberRequest> subscribers = subscriberService.findByEventType(payload.getEventType());

        if (subscribers.isEmpty()) {
            log.warn("[EVENT-CTRL] No subscribers found for event_type='{}'", payload.getEventType());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status",     404);
            body.put("error",      "Not Found");
            body.put("message",    "No subscribers registered for event_type: " + payload.getEventType());
            body.put("event_type", payload.getEventType());
            return ResponseEntity.status(404).body(body);
        }

        // ── Step 5: Async fan-out dispatch ─────────────────────────────────────
        log.info("[EVENT-CTRL] Dispatching event_type='{}' to {} subscriber(s)",
                payload.getEventType(), subscribers.size());
        webhookDispatchService.fanOut(payload, subscribers, rawBody);

        // Return 202 immediately — dispatch is async
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",           202);
        body.put("message",          "Event accepted. Dispatch initiated asynchronously.");
        body.put("event_type",       payload.getEventType());
        body.put("source",           payload.getSource());
        body.put("subscriber_count", subscribers.size());
        body.put("accepted_at",      java.time.Instant.now().toString());
        return ResponseEntity.accepted().body(body);
    }

    /**
     * Builds a 400 Bad Request response.
     */
    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status",  400);
        error.put("error",   "Bad Request");
        error.put("message", message);
        return ResponseEntity.badRequest().body(error);
    }
}
