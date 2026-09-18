package com.sentinelpulse.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * ExecutorConfig — Dedicated thread pool for async webhook dispatch.
 *
 * WHY A SEPARATE THREAD POOL?
 *   SentinelPulse must never block HTTP request threads while dispatching
 *   webhooks. Dispatch involves network I/O, retry delays (2s→4s→8s backoff),
 *   and can take up to ~15 seconds per subscriber in the worst case.
 *   If this happened on the HTTP request thread, the caller would wait
 *   for the full retry chain — unacceptable for an edge gateway.
 *
 * HOW IT WORKS:
 *   - @Async("webhookExecutor") in WebhookDispatchService offloads dispatch
 *     tasks to this pool immediately after a request is accepted (202).
 *   - The HTTP thread is freed instantly; retries happen in the background.
 *
 * POOL SETTINGS (configured in application.yml):
 *   core-pool-size  — threads always alive and ready for dispatch
 *   max-pool-size   — burst ceiling when many events arrive simultaneously
 *   queue-capacity  — tasks waiting if all threads are busy
 *
 * REJECTION POLICY:
 *   CallerRunsPolicy — if the queue is full and all threads are busy,
 *   the calling thread runs the task itself rather than dropping it.
 *   This guarantees no webhook is silently lost at the executor level.
 *
 * GRACEFUL SHUTDOWN:
 *   waitForTasksToCompleteOnShutdown=true ensures in-flight retries
 *   finish before the JVM exits. awaitTerminationSeconds=30 is the
 *   maximum wait time before forceful shutdown.
 */
@Configuration
public class ExecutorConfig {

    @Value("${sentinelpulse.executor.core-pool-size}")
    private int corePoolSize;

    @Value("${sentinelpulse.executor.max-pool-size}")
    private int maxPoolSize;

    @Value("${sentinelpulse.executor.queue-capacity}")
    private int queueCapacity;

    @Value("${sentinelpulse.executor.thread-name-prefix}")
    private String threadNamePrefix;

    /**
     * Creates and initializes the webhook dispatch thread pool.
     *
     * Named "webhookExecutor" — this name is referenced in:
     *   @Async("webhookExecutor") in WebhookDispatchService
     *   @Qualifier("webhookExecutor") in SentinelMetrics (for active thread gauge)
     *
     * @return a configured and initialized ThreadPoolTaskExecutor
     */
    @Bean(name = "webhookExecutor")
    public ThreadPoolTaskExecutor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);

        // CallerRunsPolicy: never drop tasks silently — run on calling thread if pool is full
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Graceful shutdown: wait up to 30s for in-flight webhook deliveries to complete
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }
}
