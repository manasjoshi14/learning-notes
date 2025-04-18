package com.example.app.virtualthreads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates the basics of Virtual Threads with realistic workloads.
 */
public class BasicVirtualThreadExample {

    private static final Logger logger = LoggerFactory.getLogger(BasicVirtualThreadExample.class);
    private static final int NUM_TASKS = 10_000;
    private static final long TASK_DURATION_MS = 10;

    /**
     * Run all the basic virtual thread demonstrations.
     */
    public static void runAllExamples() {
        logger.info("=== Basic Virtual Thread Examples ===");
        
        // Example 1: Different ways to create virtual threads
        createVirtualThreadsExamples();
        
        // Example 2: Compare performance of virtual threads vs platform threads
        compareVirtualAndPlatformThreads();
        
        logger.info("=== End of Basic Virtual Thread Examples ===");
    }

    /**
     * Demonstrates different ways to create virtual threads.
     */
    private static void createVirtualThreadsExamples() {
        logger.info("---- Different Ways to Create Virtual Threads ----");
        
        // Method 1: Using Thread.ofVirtual()
        Thread vThread = Thread.ofVirtual()
                .name("example-vthread")
                .start(() -> {
                    logger.info("Running in virtual thread (Thread.ofVirtual): {}",
                            Thread.currentThread().isVirtual());
                });
        
        try {
            vThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Method 2: Using Thread.startVirtualThread()
        Thread.startVirtualThread(() -> {
            logger.info("Running in virtual thread (Thread.startVirtualThread): {}",
                    Thread.currentThread().isVirtual());
        });
        
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Method 3: Using Executors.newVirtualThreadPerTaskExecutor()
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                logger.info("Running in virtual thread (newVirtualThreadPerTaskExecutor): {}",
                        Thread.currentThread().isVirtual());
            });
        }
        
        logger.info("All virtual thread creation examples completed");
    }

    /**
     * Compares the performance of virtual threads and platform threads.
     */
    private static void compareVirtualAndPlatformThreads() {
        logger.info("---- Comparing Virtual Threads vs Platform Threads ----");
        
        // Run with virtual threads
        Instant start = Instant.now();
        runWithVirtualThreads();
        Duration virtualThreadDuration = Duration.between(start, Instant.now());
        
        // Run with platform threads
        start = Instant.now();
        runWithPlatformThreads();
        Duration platformThreadDuration = Duration.between(start, Instant.now());
        
        logger.info("Performance comparison:");
        logger.info("  Virtual Threads: {} tasks in {}ms", NUM_TASKS, virtualThreadDuration.toMillis());
        logger.info("  Platform Threads: {} tasks in {}ms", NUM_TASKS, platformThreadDuration.toMillis());
        logger.info("  Speedup factor: {}x", (double) platformThreadDuration.toMillis() / virtualThreadDuration.toMillis());
    }
    
    /**
     * Runs a number of tasks using virtual threads.
     */
    private static void runWithVirtualThreads() {
        logger.info("Running {} tasks with virtual threads", NUM_TASKS);
        AtomicInteger completed = new AtomicInteger(0);
        
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Runnable> tasks = createTasks(completed);
            tasks.forEach(executor::submit);
        }
        
        logger.info("Completed {} tasks with virtual threads", completed.get());
    }

    /**
     * Runs a number of tasks using platform threads.
     */
    private static void runWithPlatformThreads() {
        logger.info("Running {} tasks with platform threads", NUM_TASKS);
        AtomicInteger completed = new AtomicInteger(0);
        
        // Use a fixed thread pool of 200 threads
        try (ExecutorService executor = Executors.newFixedThreadPool(200)) {
            List<Runnable> tasks = createTasks(completed);
            tasks.forEach(executor::submit);
        }
        
        logger.info("Completed {} tasks with platform threads", completed.get());
    }

    /**
     * Creates a list of tasks that simulate blocking I/O operations.
     */
    private static List<Runnable> createTasks(AtomicInteger counter) {
        List<Runnable> tasks = new ArrayList<>(NUM_TASKS);
        
        for (int i = 0; i < NUM_TASKS; i++) {
            tasks.add(() -> {
                try {
                    // Simulate a blocking I/O operation
                    Thread.sleep(TASK_DURATION_MS);
                    counter.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        
        return tasks;
    }
} 