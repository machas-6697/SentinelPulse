package com.sentinelpulse.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents an inbound event payload received at POST /api/v1/events.
 *
 * Emitted by a downstream microservice to trigger webhook fan-out.
 * The "data" map accepts any additional key-value pairs the downstream
 * service includes, keeping the gateway fully decoupled from business schemas.
 *
 * Idempotency is enforced by SHA-256 hashing the entire raw request body
 * (not this object) — see IdempotencyService.
 *
 * Example request body:
 * {
 *   "event_type": "order.completed",
 *   "source":     "order-service",
 *   "data": {
 *     "order_id":  "ORD-7891",
 *     "amount":    149.99,
 *     "currency":  "USD"
 *   }
 * }
 */
public class EventPayload {

    /** The event type string used to match registered subscribers. */
    @JsonProperty("event_type")
    private String eventType;

    /** Identifies which downstream service emitted this event. */
    @JsonProperty("source")
    private String source;

    /** Free-form event data — forwarded verbatim to subscribers. */
    @JsonProperty("data")
    private Map<String, Object> data = new LinkedHashMap<>();

    /** Overflow bucket: captures any extra top-level fields not explicitly mapped. */
    private Map<String, Object> extras = new LinkedHashMap<>();

    // ── Constructors ─────────────────────────────────────────────────────────

    public EventPayload() {}

    public EventPayload(String eventType, String source, Map<String, Object> data) {
        this.eventType = eventType;
        this.source = source;
        this.data = data;
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    @JsonAnyGetter
    public Map<String, Object> getExtras() { return extras; }

    @JsonAnySetter
    public void setExtra(String key, Object value) { extras.put(key, value); }

    @Override
    public String toString() {
        return "EventPayload{eventType='" + eventType
                + "', source='" + source
                + "', data=" + data + "}";
    }
}
