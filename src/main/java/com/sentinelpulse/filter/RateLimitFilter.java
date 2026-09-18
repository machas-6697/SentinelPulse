package com.sentinelpulse.filter;

import com.sentinelpulse.metrics.SentinelMetrics;
import com.sentinelpulse.service.RateLimitService;
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
 * RateLimitFilter — Token-Bucket enforcement layer, second in the filter chain.
 *
 * Runs after AuthFilter (@Order(1)), using the client_id attribute already
 * resolved and injected by AuthFilter — no redundant Redis lookup needed.
 *
 * If the client's token bucket is exhausted:
 *   → Returns HTTP 429 Too Many Requests
 *   → Includes Retry-After header (10 seconds — covers one full refill cycle)
 *   → Short-circuits: request never reaches the proxy controller
 *
 * If the client's token is successfully consumed:
 *   → Proceeds to the next filter/controller
 *
 * Bypass paths (no rate limiting):
 *   /actuator/**  — Prometheus and health endpoints are infrastructure traffic,
 *                   not client requests. Never rate-limited.
 *
 * Execution order: @Order(2) — runs immediately after AuthFilter.
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String CLIENT_ID_ATTR   = "client_id";
    private static final String FALLBACK_CLIENT  = "anonymous";
    private static final int    RETRY_AFTER_SEC  = 10;

    private final RateLimitService rateLimitService;
    private final com.sentinelpulse.service.ThrottlingService throttlingService;
    private final SentinelMetrics  metrics;

    public RateLimitFilter(RateLimitService rateLimitService,
                           com.sentinelpulse.service.ThrottlingService throttlingService,
                           SentinelMetrics metrics) {
        this.rateLimitService = rateLimitService;
        this.throttlingService = throttlingService;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // ── Bypass: actuator endpoints are infrastructure, never rate-limited ──
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        // ── Resolve client_id injected by AuthFilter ──────────────────────────
        Object clientIdAttr = request.getAttribute(CLIENT_ID_ATTR);
        String clientId = (clientIdAttr != null) ? clientIdAttr.toString() : FALLBACK_CLIENT;

        // ── Core Mechanic 1: Token-Bucket Rate Limiting ────────────────────────
        boolean allowed = rateLimitService.tryConsume(clientId);

        if (!allowed) {
            metrics.incrementRejectedRateLimit();
            log.warn("[RATE-LIMIT-FILTER] 429 Too Many Requests — client='{}', path='{}'",
                    clientId, path);

            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SEC));
            response.getWriter().write("""
                    {
                      "status": 429,
                      "error": "Too Many Requests",
                      "message": "Rate limit exceeded. Token bucket empty. Retry after %d seconds.",
                      "client_id": "%s",
                      "retry_after_seconds": %d,
                      "path": "%s"
                    }
                    """.formatted(RETRY_AFTER_SEC, clientId, RETRY_AFTER_SEC, path));
            return; // Short-circuit — do not forward the request
        }

        // ── Core Mechanic 2: Concurrency Throttling (In-Flight Limiting) ───────
        boolean acquired = throttlingService.tryAcquire(clientId);
        if (!acquired) {
            metrics.incrementRejectedThrottling();
            log.warn("[THROTTLING-FILTER] 429 Throttled (in-flight concurrency limit) — client='{}', path='{}'",
                    clientId, path);

            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("X-Throttled", "true");
            response.setHeader("Retry-After", "1");
            response.getWriter().write("""
                    {
                      "status": 429,
                      "error": "Too Many Requests",
                      "message": "Concurrency limit exceeded. Request throttled. Please retry shortly.",
                      "reason": "concurrent_in_flight_exceeded",
                      "client_id": "%s",
                      "path": "%s"
                    }
                    """.formatted(clientId, path));
            return; // Short-circuit
        }

        try {
            log.debug("[RATE-LIMIT-FILTER] Token consumed and slot acquired for client='{}', path='{}'", clientId, path);
            filterChain.doFilter(request, response);
        } finally {
            // Guaranteed release of in-flight concurrency slot
            throttlingService.release(clientId);
        }
    }
}
