package com.sentinelpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * SentinelPulseApplication — Spring Boot entry point.
 *
 * @EnableAsync activates Spring's asynchronous method execution support,
 * required for @Async("webhookExecutor") in WebhookDispatchService to
 * route dispatch tasks onto the dedicated webhook-worker thread pool.
 *
 * Without @EnableAsync, @Async annotations are silently ignored and
 * all dispatch would block on the HTTP request thread — defeating the
 * entire async architecture.
 */
@SpringBootApplication
@EnableAsync
public class SentinelPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelPulseApplication.class, args);
    }
}
