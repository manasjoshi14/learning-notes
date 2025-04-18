package com.example.app.virtualthreads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Demonstrates thread pinning issues with virtual threads and how to avoid them.
 * 
 * Thread pinning occurs when a virtual thread is "pinned" to its carrier thread,
 * preventing the carrier thread from executing other virtual threads.
 */
public class ThreadPinningExample {
    
    private static final Logger logger = LoggerFactory.getLogger(ThreadPinningExample.class);
    private static final int NUM_TASKS = 1_000;
    private static final int ITERATIONS_PER_TASK = 5;
    private static final long BLOCKING_TIME_MS = 10; // Time to sleep inside critical section
    private static final long WORK_TIME_MS = 1; // Time to sleep outside critical section
    
    /**
     * Runs the thread pinning examples.
     */
    public static void runExample() {
        logger.info("=== Thread Pinning Example ===");
        
        // Limit carrier threads to make pinning more evident
        System.setProperty("jdk.virtualThreadScheduler.parallelism", "4");
        logger.info("Running with 4 carrier threads to make pinning more evident");
        
        // Warm-up run to eliminate JIT effects
        warmupRun();
        
        // Example 1: Demonstrating the pinning problem with synchronized
        demonstratePinningWithSynchronized();
        
        // Example 2: Showing the solution using ReentrantLock
        demonstrateSolutionWithReentrantLock();
        
        // Reset system property
        System.clearProperty("jdk.virtualThreadScheduler.parallelism");
        
        logger.info("=== End of Thread Pinning Example ===");
    }
    
    /**
     * Warms up the JVM to eliminate JIT compilation effects on timing.
     */
    private static void warmupRun() {
        Counter synchronizedCounter = new SynchronizedCounter();
        Counter lockCounter = new ReentrantLockCounter();
        
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) {
                executor.submit(() -> {
                    for (int j = 0; j < 5; j++) {
                        synchronizedCounter.increment();
                        lockCounter.increment();
                    }
                });
            }
        }
    }
    
    /**
     * Demonstrates the thread pinning problem using synchronized methods.
     */
    private static void demonstratePinningWithSynchronized() {
        logger.info("---- Thread Pinning with Synchronized ----");
        logger.info("This demonstrates how synchronized methods cause pinning");
        
        Counter synchronizedCounter = new SynchronizedCounter();
        
        Instant start = Instant.now();
        
        // Run tasks with synchronized counter
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < NUM_TASKS; i++) {
                executor.submit(() -> {
                    for (int j = 0; j < ITERATIONS_PER_TASK; j++) {
                        try {
                            Thread.sleep(WORK_TIME_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        
                        // Increment with synchronized - causes pinning
                        synchronizedCounter.increment();
                    }
                });
            }
        }
        
        Duration duration = Duration.between(start, Instant.now());
        double synchronizedDuration = duration.toMillis();
        logger.info("Synchronized version completed in {}ms", synchronizedDuration);
        logger.info("Final count: {}", synchronizedCounter.getCount());
    }
    
    /**
     * Demonstrates how to avoid thread pinning by using java.util.concurrent locks.
     */
    private static void demonstrateSolutionWithReentrantLock() {
        logger.info("---- Avoiding Thread Pinning with ReentrantLock ----");
        logger.info("This demonstrates how ReentrantLock avoids pinning");
        
        Counter lockCounter = new ReentrantLockCounter();
        
        Instant start = Instant.now();
        
        // Run tasks with ReentrantLock counter
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < NUM_TASKS; i++) {
                executor.submit(() -> {
                    for (int j = 0; j < ITERATIONS_PER_TASK; j++) {
                        try {
                            Thread.sleep(WORK_TIME_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        
                        // Increment with ReentrantLock - no pinning
                        lockCounter.increment();
                    }
                });
            }
        }
        
        Duration duration = Duration.between(start, Instant.now());
        double lockDuration = duration.toMillis();
        logger.info("ReentrantLock version completed in {}ms", lockDuration);
        logger.info("Final count: {}", lockCounter.getCount());
        
        // Tips for avoiding pinning
        logger.info("\nBest practices to avoid thread pinning:");
        logger.info("1. Use java.util.concurrent locks instead of synchronized");
        logger.info("2. Use Condition instead of Object.wait/notify");
        logger.info("3. Avoid native methods with virtual threads");
        logger.info("4. Avoid nested synchronized blocks");
        logger.info("5. Use JFR events or -Djdk.tracePinnedThreads to detect pinning");
    }

    /**
     * Common interface for counters.
     */
    interface Counter {
        void increment();
        long getCount();
    }
    
    /**
     * Counter that uses synchronized for thread safety - causes pinning.
     */
    static class SynchronizedCounter implements Counter {
        private long count = 0;
        private double dummy = 0;
        
        @Override
        public synchronized void increment() {
            count++;
            // Do some calculations to simulate work
            for (int i = 0; i < 100; i++) {
                dummy += Math.sin(count) * Math.cos(count);
            }
            
            // Sleep to simulate blocking I/O or long-running critical section
            try {
                Thread.sleep(BLOCKING_TIME_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        @Override
        public synchronized long getCount() {
            return count;
        }
    }
    
    /**
     * Counter that uses ReentrantLock for thread safety - avoids pinning.
     */
    static class ReentrantLockCounter implements Counter {
        private final ReentrantLock lock = new ReentrantLock();
        private long count = 0;
        private double dummy = 0;
        
        @Override
        public void increment() {
            lock.lock();
            try {
                count++;
                // Do the exact same calculations as SynchronizedCounter
                for (int i = 0; i < 100; i++) {
                    dummy += Math.sin(count) * Math.cos(count);
                }
                
                // Sleep to simulate blocking I/O
                try {
                    Thread.sleep(BLOCKING_TIME_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                lock.unlock();
            }
        }
        
        @Override
        public long getCount() {
            lock.lock();
            try {
                return count;
            } finally {
                lock.unlock();
            }
        }
    }
} 