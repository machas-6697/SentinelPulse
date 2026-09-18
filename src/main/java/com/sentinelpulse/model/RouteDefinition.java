package com.sentinelpulse.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents a single route definition loaded from routes.json at startup.
 *
 * Each RouteDefinition is seeded into Redis as a Hash under the key:
 *   route:{path}
 * with fields:
 *   target_url  → downstream service URL
 *   methods     → comma-separated list of allowed HTTP methods
 *
 * Example Redis entry:
 *   HSET route:/api/v1/orders target_url http://localhost:9091 methods GET,POST
 */
public class RouteDefinition {

    /** The inbound path that SentinelPulse intercepts (e.g., /api/v1/orders). */
    @JsonProperty("path")
    private String path;

    /** The downstream service URL to transparently forward matching requests to. */
    @JsonProperty("target_url")
    private String targetUrl;

    /** HTTP methods permitted for this route (e.g., ["GET", "POST"]). */
    @JsonProperty("methods")
    private List<String> methods;

    // ── Constructors ─────────────────────────────────────────────────────────

    public RouteDefinition() {}

    public RouteDefinition(String path, String targetUrl, List<String> methods) {
        this.path = path;
        this.targetUrl = targetUrl;
        this.methods = methods;
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

    public List<String> getMethods() { return methods; }
    public void setMethods(List<String> methods) { this.methods = methods; }

    @Override
    public String toString() {
        return "RouteDefinition{path='" + path + "', targetUrl='" + targetUrl
                + "', methods=" + methods + "}";
    }
}
