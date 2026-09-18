package com.sentinelpulse.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /api/v1/subscribers.
 *
 * A subscriber registers a target endpoint that will receive webhook
 * HTTP POST deliveries whenever a matching event_type is emitted.
 *
 * Stored in Redis as a Hash:
 *   Key:    subscriber:{subscriber_id}
 *   Fields: subscriber_id, target_url, event_type
 *
 * Example Redis entry:
 *   HSET subscriber:sub-001
 *        subscriber_id sub-001
 *        target_url    https://hooks.example.com/receive
 *        event_type    order.completed
 */
public class SubscriberRequest {

    /** Unique identifier for this subscriber. Provided by the caller. */
    @JsonProperty("subscriber_id")
    private String subscriberId;

    /** The HTTPS/HTTP endpoint SentinelPulse will POST event payloads to. */
    @JsonProperty("target_url")
    private String targetUrl;

    /** The event type this subscriber is interested in (e.g., "order.completed"). */
    @JsonProperty("event_type")
    private String eventType;

    // ── Constructors ─────────────────────────────────────────────────────────

    public SubscriberRequest() {}

    public SubscriberRequest(String subscriberId, String targetUrl, String eventType) {
        this.subscriberId = subscriberId;
        this.targetUrl = targetUrl;
        this.eventType = eventType;
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public String getSubscriberId() { return subscriberId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    @Override
    public String toString() {
        return "SubscriberRequest{subscriberId='" + subscriberId
                + "', targetUrl='" + targetUrl
                + "', eventType='" + eventType + "'}";
    }
}
