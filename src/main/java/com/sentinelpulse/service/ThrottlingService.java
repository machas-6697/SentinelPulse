package com.sentinelpulse.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ThrottlingService — In-Flight Concurrency Throttling Layer.
 *
 * Core Mechanic: Throttling
 * Technical Primitives: Concurrency, Thread-Safety, AtomicInteger
 *
 * Architectural Distinction: Rate Limiting vs. Throttling
 * ─────────────────────────────────────────────────────────────────────────────
 * - Rate Limiting (Token-Bucket): Governs the TOTAL VOLUME of requests a client
 *   may send over a time window (e.g., 10 tokens refilled at 2 tokens/sec).
 *
 * - Throttling (In-Flight Concurrency): Governs the SIMULTANEOUS CONCURRENCY of
 *   requests currently being processed by the system or by a specific client.
 *   Even if a client has rate-limit tokens remaining, if they open 20 parallel
 *   connections at the exact same millisecond, throttling prevents worker thread
 *   exhaustion and protects downstream microservices from being overwhelmed.
 *
 * Implementation Details:
 * ─────────────────────────────────────────────────────────────────────────────
 * - Uses AtomicInteger primitives for lock-free, thread-safe counter operations.
 * - Global In-Flight Counter (AtomicInteger): Caps total system concurrency.
 * - Per-Client In-Flight Map (ConcurrentHashMap<String, AtomicInteger>): Caps
 *   simultaneous in-flight requests per client identifier.
 * - tryAcquire() increments counters using CAS operations; if thresholds are
 *   exceeded, it rolls back atomically and returns false.
 * - release() is invoked in a filter finally block to guarantee counter decrements
 *   even if upstream exceptions occur.
 */
@Service
public class ThrottlingService {

    private static final Logger log = LoggerFactory.getLogger(ThrottlingService.class);

    /** Global ceiling for concurrent in-flight requests across the entire gateway. */
    @Value("${sentinelpulse.throttling.max-global-in-flight:100}")
    private int maxGlobalInFlight;

    /** Per-client ceiling for concurrent in-flight requests. */
    @Value("${sentinelpulse.throttling.max-client-in-flight:15}")
    private int maxClientInFlight;

    /** Global counter tracking all currently active in-flight requests. */
    private final AtomicInteger globalInFlight = new AtomicInteger(0);

    /** Thread-safe map tracking active in-flight requests per client_id. */
    private final ConcurrentHashMap<String, AtomicInteger> clientInFlightMap = new ConcurrentHashMap<>();

    /**
     * Attempts to acquire an in-flight execution slot for the client.
     *
     * @param clientId the resolved client identifier
     * @return true if the request is within concurrency limits;
     *         false if the system or client is throttled
     */
    public boolean tryAcquire(String clientId) {
        // 1. Check and increment global concurrency
        int currentGlobal = globalInFlight.incrementAndGet();
        if (currentGlobal > maxGlobalInFlight) {
            globalInFlight.decrementAndGet(); // Rollback immediately
            log.warn("[THROTTLING] Global concurrency limit exceeded: active={}, limit={}",
                    currentGlobal - 1, maxGlobalInFlight);
            return false;
        }

        // 2. Check and increment per-client concurrency
        AtomicInteger clientCounter = clientInFlightMap.computeIfAbsent(clientId, k -> new AtomicInteger(0));
        int currentClient = clientCounter.incrementAndGet();
        if (currentClient > maxClientInFlight) {
            clientCounter.decrementAndGet();  // Rollback client counter
            globalInFlight.decrementAndGet(); // Rollback global counter
            log.warn("[THROTTLING] Client concurrency limit exceeded: client='{}', active={}, limit={}",
                    clientId, currentClient - 1, maxClientInFlight);
            return false;
        }

        log.debug("[THROTTLING] Slot acquired: client='{}' (client_active={}, global_active={})",
                clientId, currentClient, currentGlobal);
        return true;
    }

    /**
     * Releases the in-flight execution slot for the client.
     * Must be called in a finally block to ensure guarantee of release.
     *
     * @param clientId the resolved client identifier
     */
    public void release(String clientId) {
        globalInFlight.decrementAndGet();
        AtomicInteger clientCounter = clientInFlightMap.get(clientId);
        if (clientCounter != null) {
            int remaining = clientCounter.decrementAndGet();
            if (remaining <= 0) {
                clientInFlightMap.remove(clientId, clientCounter);
            }
        }
        log.debug("[THROTTLING] Slot released for client='{}'", clientId);
    }

    /** Returns current global in-flight request count for observability gauges. */
    public int getGlobalInFlightCount() {
        return globalInFlight.get();
    }
}
