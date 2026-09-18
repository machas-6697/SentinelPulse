package com.sentinelpulse.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * SentinelPulse custom Micrometer metric definitions.
 *
 * All metrics are prefixed "sentinelpulse_" and scraped by Prometheus
 * via /actuator/prometheus. Grafana pulls from Prometheus to render dashboards.
 *
 * Metrics registered here:
 *
 *  COUNTERS (monotonically increasing)
 *  ─────────────────────────────────────────────────────────────────
 *  sentinelpulse_requests_total              — every inbound request
 *  sentinelpulse_requests_rejected_auth      — 401 rejections (bad API key)
 *  sentinelpulse_requests_rejected_ratelimit — 429 rejections (token bucket empty)
 *  sentinelpulse_webhook_dispatched_total    — every dispatch attempt (all outcomes)
 *  sentinelpulse_webhook_dispatch_failed     — payloads exhausted to DLQ
 *
 *  TIMERS (latency distribution — p50, p95, p99, max)
 *  ─────────────────────────────────────────────────────────────────
 *  sentinelpulse_proxy_latency_seconds       — full round-trip proxy duration
 *
 *  GAUGES (point-in-time snapshots)
 *  ─────────────────────────────────────────────────────────────────
 *  sentinelpulse_dlq_depth                  — current Redis dlq:webhooks list length
 *  sentinelpulse_active_webhook_threads     — active threads in webhook executor pool
 */
@Component
public class SentinelMetrics {

    // ── Counters ──────────────────────────────────────────────────────────────

    /** Total inbound requests intercepted by the edge proxy. */
    private final Counter requestsTotal;

    /** Requests rejected at the AuthFilter (invalid or missing X-API-Key). */
    private final Counter requestsRejectedAuth;

    /** Requests rejected at the RateLimitFilter (token bucket exhausted → 429). */
    private final Counter requestsRejectedRateLimit;

    /** Requests rejected due to in-flight concurrency throttling (HTTP 429). */
    private final Counter requestsRejectedThrottled;

    /** Total webhook dispatch attempts fired to subscriber endpoints. */
    private final Counter webhookDispatchedTotal;

    /** Dispatch attempts that exhausted all retries and were pushed to DLQ. */
    private final Counter webhookDispatchFailed;

    // ── Timers ────────────────────────────────────────────────────────────────

    /** End-to-end proxy forwarding latency: from SentinelPulse receiving the request
     *  to receiving the downstream service's response. */
    private final Timer proxyLatency;

    // ── Constructor ───────────────────────────────────────────────────────────

    public SentinelMetrics(MeterRegistry registry,
                           RedisTemplate<String, String> redisTemplate,
                           Executor webhookExecutor) {

        // Counters
        this.requestsTotal = Counter.builder("sentinelpulse_requests_total")
                .description("Total inbound HTTP requests intercepted by SentinelPulse")
                .tag("component", "edge-proxy")
                .register(registry);

        this.requestsRejectedAuth = Counter.builder("sentinelpulse_requests_rejected_auth")
                .description("Requests rejected by AuthFilter due to invalid X-API-Key (HTTP 401)")
                .tag("component", "auth-filter")
                .tag("reason", "invalid_api_key")
                .register(registry);

        this.requestsRejectedRateLimit = Counter.builder("sentinelpulse_requests_rejected_ratelimit")
                .description("Requests rejected by RateLimitFilter due to token bucket exhaustion (HTTP 429)")
                .tag("component", "rate-limit-filter")
                .tag("reason", "token_bucket_empty")
                .register(registry);

        this.requestsRejectedThrottled = Counter.builder("sentinelpulse_requests_rejected_throttled")
                .description("Requests rejected due to in-flight concurrency throttling (HTTP 429)")
                .tag("component", "throttling-filter")
                .tag("reason", "concurrency_limit_exceeded")
                .register(registry);

        this.webhookDispatchedTotal = Counter.builder("sentinelpulse_webhook_dispatched_total")
                .description("Total webhook dispatch attempts fired to subscriber endpoints")
                .tag("component", "webhook-dispatch")
                .register(registry);

        this.webhookDispatchFailed = Counter.builder("sentinelpulse_webhook_dispatch_failed")
                .description("Webhook payloads that exhausted all retries and were pushed to DLQ")
                .tag("component", "webhook-dispatch")
                .tag("outcome", "dead_letter")
                .register(registry);

        // Timer
        this.proxyLatency = Timer.builder("sentinelpulse_proxy_latency_seconds")
                .description("End-to-end proxy forwarding latency in seconds")
                .tag("component", "proxy-forward")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        // Gauges — observe live state from external sources

        // DLQ depth: reads the current length of the dlq:webhooks Redis List
        Gauge.builder("sentinelpulse_dlq_depth", redisTemplate, rt -> {
            try {
                Long size = rt.opsForList().size("dlq:webhooks");
                return size != null ? size.doubleValue() : 0.0;
            } catch (Exception e) {
                return 0.0;
            }
        })
        .description("Current number of payloads sitting in the Dead-Letter Queue (dlq:webhooks)")
        .tag("component", "dlq")
        .register(registry);

        // Active webhook threads: reads from the webhook executor pool
        Gauge.builder("sentinelpulse_active_webhook_threads", webhookExecutor, exec -> {
            if (exec instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor tpte) {
                return (double) tpte.getActiveCount();
            } else if (exec instanceof ThreadPoolExecutor tpe) {
                return (double) tpe.getActiveCount();
            }
            return 0.0;
        })
        .description("Number of threads currently active in the webhook dispatch executor pool")
        .tag("component", "webhook-executor")
        .register(registry);
    }

    // ── Public increment/record methods ───────────────────────────────────────

    public void incrementRequestsTotal()            { requestsTotal.increment(); }
    public void incrementRejectedAuth()             { requestsRejectedAuth.increment(); }
    public void incrementRejectedRateLimit()        { requestsRejectedRateLimit.increment(); }
    public void incrementRejectedThrottling()       { requestsRejectedThrottled.increment(); }
    public void incrementWebhookDispatched()        { webhookDispatchedTotal.increment(); }
    public void incrementWebhookDispatchFailed()    { webhookDispatchFailed.increment(); }

    /** Returns the proxy latency Timer for use with Timer.Sample in ProxyController. */
    public Timer getProxyLatencyTimer()             { return proxyLatency; }
}
