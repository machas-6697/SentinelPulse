package com.sentinelpulse.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ThrottlingServiceTest {

    private ThrottlingService throttlingService;

    @BeforeEach
    void setUp() {
        throttlingService = new ThrottlingService();
        ReflectionTestUtils.setField(throttlingService, "maxGlobalInFlight", 5);
        ReflectionTestUtils.setField(throttlingService, "maxClientInFlight", 2);
    }

    @Test
    void testClientLimitEnforced() {
        String client = "client-test-1";

        assertTrue(throttlingService.tryAcquire(client), "First slot must be acquired");
        assertTrue(throttlingService.tryAcquire(client), "Second slot must be acquired (limit is 2)");
        assertFalse(throttlingService.tryAcquire(client), "Third slot must be throttled (exceeds client limit of 2)");

        throttlingService.release(client);
        assertTrue(throttlingService.tryAcquire(client), "After release, slot must be available again");
        throttlingService.release(client);
        throttlingService.release(client);
    }

    @Test
    void testGlobalLimitEnforced() {
        // Global limit is 5, client limit is 2. 3 different clients acquire slots.
        assertTrue(throttlingService.tryAcquire("c1"));
        assertTrue(throttlingService.tryAcquire("c1"));
        assertTrue(throttlingService.tryAcquire("c2"));
        assertTrue(throttlingService.tryAcquire("c2"));
        assertTrue(throttlingService.tryAcquire("c3"));

        // Global is now at 5. Any further client should be throttled.
        assertFalse(throttlingService.tryAcquire("c4"), "Global limit of 5 reached, must throttle c4");

        throttlingService.release("c1");
        assertTrue(throttlingService.tryAcquire("c4"), "After release, global slot becomes available");
    }

    @Test
    void testConcurrentThrottlingThreadSafety() throws InterruptedException {
        ReflectionTestUtils.setField(throttlingService, "maxGlobalInFlight", 10);
        ReflectionTestUtils.setField(throttlingService, "maxClientInFlight", 10);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger acquiredCount = new AtomicInteger(0);
        AtomicInteger throttledCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (throttlingService.tryAcquire("concurrent-client")) {
                        acquiredCount.incrementAndGet();
                    } else {
                        throttledCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Fire all 20 threads simultaneously
        doneLatch.await();
        executor.shutdown();

        assertEquals(10, acquiredCount.get(), "Exactly 10 threads should acquire slots up to max limit");
        assertEquals(10, throttledCount.get(), "Exactly 10 threads should be throttled");
    }
}
