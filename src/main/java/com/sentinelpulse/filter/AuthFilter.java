package com.sentinelpulse.filter;

import com.sentinelpulse.metrics.SentinelMetrics;
import com.sentinelpulse.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * AuthFilter — First line of defense in the SentinelPulse filter chain.
 *
 * Intercepts every inbound HTTP request and validates the X-API-Key header
 * against the Redis apikeys:{key} store. If the key is absent or invalid,
 * the filter short-circuits the request immediately with HTTP 401 Unauthorized.
 *
 * Execution order: @Order(1) — runs before RateLimitFilter (@Order(2)).
 * This ensures unauthenticated traffic never consumes rate-limit tokens.
 *
 * Bypass paths (no auth required):
 *   /actuator/**  — Prometheus scraping and health checks must be unauthenticated.
 *
 * On success, the resolved client_id is attached to the request as an attribute
 * (attribute key: "client_id") so downstream filters and controllers can access it
 * without redundant Redis lookups.
 */
@Component
@Order(1)
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String CLIENT_ID_ATTR = "client_id";

    private final AuthService authService;
    private final SentinelMetrics metrics;

    public AuthFilter(AuthService authService, SentinelMetrics metrics) {
        this.authService = authService;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // ── Security Hardening: Standard Edge Protection Headers ─────────────
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "1; mode=block");

        metrics.incrementRequestsTotal();

        String path = request.getRequestURI();

        // ── Bypass: actuator endpoints must be accessible without auth ────────
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        // ── Extract, sanitize, and validate X-API-Key header ───────────────────
        String apiKey = request.getHeader(API_KEY_HEADER);

        // Security check: reject keys with illegal control characters or exceeding 128 chars
        if (apiKey != null && (apiKey.length() > 128 || apiKey.contains("\r") || apiKey.contains("\n"))) {
            metrics.incrementRejectedAuth();
            log.warn("[AUTH-FILTER] Security alert: Malformed or oversized API key rejected for path='{}'", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {
                      "status": 401,
                      "error": "Unauthorized",
                      "message": "Malformed X-API-Key header.",
                      "path": "%s"
                    }
                    """.formatted(path));
            return;
        }

        if (!authService.isValidApiKey(apiKey)) {
            metrics.incrementRejectedAuth();
            log.warn("[AUTH-FILTER] 401 Unauthorized — path='{}', key='{}'",
                    path, apiKey != null ? maskKey(apiKey) : "MISSING");

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {
                      "status": 401,
                      "error": "Unauthorized",
                      "message": "Invalid or missing X-API-Key header. Provide a valid API key.",
                      "path": "%s"
                    }
                    """.formatted(path));
            return; // Short-circuit — do not proceed down the filter chain
        }

        // ── Attach client_id to request for downstream use ────────────────────
        String clientId = authService.resolveClientId(apiKey);
        request.setAttribute(CLIENT_ID_ATTR, clientId);
        log.debug("[AUTH-FILTER] Authenticated client_id='{}' for path='{}'", clientId, path);

        filterChain.doFilter(request, response);
    }

    /**
     * Masks an API key for safe log output: shows first 4 chars + ****.
     */
    private String maskKey(String key) {
        if (key.length() <= 4) return "****";
        return key.substring(0, 4) + "****";
    }
}
