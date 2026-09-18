package com.sentinelpulse.controller;

import com.sentinelpulse.model.SubscriberRequest;
import com.sentinelpulse.service.SubscriberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SubscriberController — REST endpoint for webhook subscriber registration.
 *
 * Endpoint:  POST /api/v1/subscribers
 * Auth:      Required (X-API-Key validated by AuthFilter)
 * Rate Limit: Applied (RateLimitFilter)
 *
 * Request body:
 * {
 *   "subscriber_id": "sub-001",
 *   "target_url":    "https://hooks.example.com/receive",
 *   "event_type":    "order.completed"
 * }
 *
 * Responses:
 *   201 Created     → New subscriber registered
 *   200 OK          → Existing subscriber updated
 *   400 Bad Request → Missing required fields
 */
@RestController
@RequestMapping("/api/v1/subscribers")
public class SubscriberController {

    private static final Logger log = LoggerFactory.getLogger(SubscriberController.class);

    private final SubscriberService subscriberService;

    public SubscriberController(SubscriberService subscriberService) {
        this.subscriberService = subscriberService;
    }

    /**
     * Registers a webhook subscriber.
     *
     * Returns 201 Created for new registrations.
     * Returns 200 OK if the subscriber_id already exists and was updated.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody SubscriberRequest request) {
        // ── Validate required fields ──────────────────────────────────────────
        if (request.getSubscriberId() == null || request.getSubscriberId().isBlank()) {
            return badRequest("subscriber_id is required and cannot be blank");
        }
        if (request.getTargetUrl() == null || request.getTargetUrl().isBlank()) {
            return badRequest("target_url is required and cannot be blank");
        }

        // Basic Security: validate that target_url is a well-formed HTTP/HTTPS URL
        try {
            java.net.URI uri = java.net.URI.create(request.getTargetUrl().trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return badRequest("target_url must use 'http' or 'https' protocol");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return badRequest("target_url must contain a valid hostname");
            }
        } catch (Exception e) {
            return badRequest("target_url is malformed: " + e.getMessage());
        }

        if (request.getEventType() == null || request.getEventType().isBlank()) {
            return badRequest("event_type is required and cannot be blank");
        }

        log.info("[SUBSCRIBER-CTRL] Registration request: subscriber_id='{}', event_type='{}'",
                request.getSubscriberId(), request.getEventType());

        boolean isNew = subscriberService.register(request);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",        isNew ? 201 : 200);
        body.put("message",       isNew ? "Subscriber registered successfully"
                                        : "Subscriber updated successfully");
        body.put("subscriber_id", request.getSubscriberId());
        body.put("target_url",    request.getTargetUrl());
        body.put("event_type",    request.getEventType());
        body.put("registered_at", Instant.now().toString());

        HttpStatus httpStatus = isNew ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(httpStatus).body(body);
    }

    /**
     * Builds a 400 Bad Request response with a descriptive error message.
     */
    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status",  400);
        error.put("error",   "Bad Request");
        error.put("message", message);
        return ResponseEntity.badRequest().body(error);
    }
}
