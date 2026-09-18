package com.sentinelpulse.controller;

import com.sentinelpulse.metrics.SentinelMetrics;
import com.sentinelpulse.service.RouteService;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.*;

/**
 * ProxyController — Transparent HTTP reverse proxy.
 *
 * Catches ALL incoming requests that do not match /api/v1/** or /actuator/**
 * and forwards them to the downstream service registered for that path in Redis.
 *
 * Transparency contract:
 *   - Preserves original HTTP method (GET, POST, PUT, DELETE, PATCH)
 *   - Preserves original request body verbatim
 *   - Preserves original request headers
 *   - Appends gateway proxy headers:
 *       X-Forwarded-For  → client IP address
 *       X-Request-ID     → UUID generated per request for trace correlation
 *       X-Gateway-Time   → ISO-8601 timestamp when SentinelPulse processed the request
 *
 * Response contract:
 *   - Returns the downstream response status code, body, and content-type as-is.
 *   - On route not found → 404
 *   - On downstream connection error → 502 Bad Gateway
 *   - On downstream 4xx/5xx → forwarded as-is
 *
 * Proxy latency is recorded via Micrometer Timer for Prometheus/Grafana dashboards.
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    // Headers that should NOT be forwarded downstream (hop-by-hop headers)
    private static final Set<String> EXCLUDED_HEADERS = Set.of(
            "host", "content-length", "transfer-encoding", "connection",
            "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "upgrade"
    );

    private final WebClient proxyWebClient;
    private final RouteService routeService;
    private final SentinelMetrics metrics;

    public ProxyController(@Qualifier("proxyWebClient") WebClient proxyWebClient,
                           RouteService routeService,
                           SentinelMetrics metrics) {
        this.proxyWebClient = proxyWebClient;
        this.routeService   = routeService;
        this.metrics        = metrics;
    }

    /**
     * Catch-all request mapping — intercepts every request not handled by other controllers.
     *
     * Exclusion note: Spring routes /api/v1/** and /actuator/** to their dedicated
     * controllers/endpoints first. This mapping receives everything else.
     */
    @RequestMapping(value = {"/**"})
    public ResponseEntity<byte[]> proxy(
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {

        String path     = request.getRequestURI();
        String method   = request.getMethod();
        String clientIp = resolveClientIp(request);
        String requestId = UUID.randomUUID().toString();

        log.info("[PROXY] → {} {} | client={} | requestId={}", method, path, clientIp, requestId);

        // ── Basic Security: Guard against path traversal ──────────────────────
        String normalizedPath = java.net.URI.create(path).normalize().getPath();
        if (normalizedPath.contains("..")) {
            log.warn("[PROXY] 400 Path traversal detected: path='{}' | requestId={}", path, requestId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(("{\"status\":400,\"error\":\"Bad Request\","
                            + "\"message\":\"Path traversal is not permitted.\","
                            + "\"request_id\":\"" + requestId + "\"}").getBytes());
        }

        // ── Resolve downstream target from Redis ──────────────────────────────
        String targetUrl = routeService.resolveTargetUrl(path);
        if (targetUrl == null) {
            log.warn("[PROXY] 404 No route for path='{}' | requestId={}", path, requestId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(("{\"status\":404,\"error\":\"Not Found\","
                            + "\"message\":\"No route configured for path: " + path + "\","
                            + "\"request_id\":\"" + requestId + "\"}").getBytes());
        }

        // ── API Gateway Method Validation (HTTP 405 Method Not Allowed) ───────
        if (!routeService.isMethodAllowed(path, method)) {
            log.warn("[PROXY] 405 Method Not Allowed: method='{}' path='{}' | requestId={}", method, path, requestId);
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .body(("{\"status\":405,\"error\":\"Method Not Allowed\","
                            + "\"message\":\"HTTP method " + method + " is not allowed for route " + path + "\","
                            + "\"request_id\":\"" + requestId + "\"}").getBytes());
        }

        // ── Build full downstream URI ──────────────────────────────────────────
        String queryString = request.getQueryString();
        String downstreamUri = targetUrl + path + (queryString != null ? "?" + queryString : "");

        log.debug("[PROXY] Forwarding → {} | requestId={}", downstreamUri, requestId);

        // ── Collect and filter original request headers ───────────────────────
        HttpHeaders forwardHeaders = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                if (!EXCLUDED_HEADERS.contains(name.toLowerCase())) {
                    forwardHeaders.add(name, request.getHeader(name));
                }
            }
        }

        // ── Append gateway proxy headers ──────────────────────────────────────
        forwardHeaders.set("X-Forwarded-For",  clientIp);
        forwardHeaders.set("X-Request-ID",     requestId);
        forwardHeaders.set("X-Gateway-Time",   Instant.now().toString());

        // ── Forward request and measure latency ───────────────────────────────
        Timer.Sample sample = Timer.start();

        try {
            WebClient.RequestBodySpec requestSpec = proxyWebClient
                    .method(HttpMethod.valueOf(method))
                    .uri(downstreamUri)
                    .headers(h -> h.addAll(forwardHeaders));

            // Attach body if present (POST, PUT, PATCH)
            WebClient.ResponseSpec responseSpec = (body != null && body.length > 0)
                    ? requestSpec.bodyValue(body).retrieve()
                    : requestSpec.retrieve();

            // Capture raw response including status code
            byte[] responseBody = responseSpec
                    .onStatus(status -> true, resp -> reactor.core.publisher.Mono.empty())
                    .bodyToMono(byte[].class)
                    .block();

            sample.stop(metrics.getProxyLatencyTimer());

            log.info("[PROXY] ← Response received for requestId={}", requestId);
            return ResponseEntity.ok(responseBody != null ? responseBody : new byte[0]);

        } catch (WebClientRequestException e) {
            sample.stop(metrics.getProxyLatencyTimer());
            log.error("[PROXY] 502 Connection failed to '{}': {} | requestId={}",
                    downstreamUri, e.getMessage(), requestId);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(("{\"status\":502,\"error\":\"Bad Gateway\","
                            + "\"message\":\"Could not connect to downstream service.\","
                            + "\"target\":\"" + downstreamUri + "\","
                            + "\"request_id\":\"" + requestId + "\"}").getBytes());

        } catch (WebClientResponseException e) {
            sample.stop(metrics.getProxyLatencyTimer());
            log.warn("[PROXY] Downstream returned {}: {} | requestId={}",
                    e.getStatusCode().value(), e.getMessage(), requestId);
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAsByteArray());

        } catch (Exception e) {
            sample.stop(metrics.getProxyLatencyTimer());
            log.error("[PROXY] 500 Unexpected error: {} | requestId={}", e.getMessage(), requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("{\"status\":500,\"error\":\"Internal Server Error\","
                            + "\"message\":\"Unexpected gateway error.\","
                            + "\"request_id\":\"" + requestId + "\"}").getBytes());
        }
    }

    /**
     * Resolves the real client IP, respecting X-Forwarded-For if present
     * (for cases where SentinelPulse is itself behind another proxy).
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
