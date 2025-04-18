package com.example.app.virtualthreads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates using virtual threads for database operations.
 */
public class DatabaseOperationsExample {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseOperationsExample.class);
    private static final int NUM_OPERATIONS = 1000;
    private static final int PLATFORM_THREAD_POOL_SIZE = 50;
    
    // In-memory database simulation
    private static final ConcurrentHashMap<String, User> userDatabase = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<Order>> orderDatabase = new ConcurrentHashMap<>();
    
    /**
     * Runs the database operations example.
     */
    public static void runExample() {
        logger.info("=== Database Operations with Virtual Threads Example ===");
        
        // Initialize our simulated database with sample data
        initializeDatabase();
        
        // Compare platform threads vs virtual threads
        performWithPlatformThreads();
        performWithVirtualThreads();
        
        logger.info("=== End of Database Operations Example ===");
    }
    
    /**
     * Initializes the simulated database with sample data.
     */
    private static void initializeDatabase() {
        logger.info("Initializing simulated database...");
        
        // Create 100 users with 10 orders each
        for (int i = 0; i < 100; i++) {
            String userId = "user-" + UUID.randomUUID();
            userDatabase.put(userId, new User(userId, "User " + i, "user" + i + "@example.com"));
            
            List<Order> userOrders = new ArrayList<>();
            for (int j = 0; j < 10; j++) {
                userOrders.add(new Order("order-" + UUID.randomUUID(), userId, (j+1) * 10.0));
            }
            orderDatabase.put(userId, userOrders);
        }
        
        logger.info("Database initialized with {} users and {} orders", 
                userDatabase.size(), orderDatabase.values().stream().mapToInt(List::size).sum());
    }
    
    /**
     * Performs database operations using a fixed pool of platform threads.
     */
    private static void performWithPlatformThreads() {
        logger.info("---- Performing Database Operations with Platform Threads ----");
        
        ExecutorService executor = Executors.newFixedThreadPool(PLATFORM_THREAD_POOL_SIZE);
        
        AtomicInteger completedOps = new AtomicInteger(0);
        Instant start = Instant.now();
        
        // Submit operations to the executor
        for (int i = 0; i < NUM_OPERATIONS; i++) {
            executor.submit(() -> {
                String randomUserId = getRandomUserId();
                User user = readUserFromDb(randomUserId);
                List<Order> orders = readOrdersFromDb(randomUserId);
                if (user != null) {
                    completedOps.incrementAndGet();
                }
            });
        }
        
        shutdownExecutor(executor);
        
        Duration duration = Duration.between(start, Instant.now());
        logger.info("Platform threads: {} operations completed in {}ms", 
                completedOps.get(), duration.toMillis());
    }
    
    /**
     * Performs database operations using virtual threads.
     */
    private static void performWithVirtualThreads() {
        logger.info("---- Performing Database Operations with Virtual Threads ----");
        
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        
        AtomicInteger completedOps = new AtomicInteger(0);
        Instant start = Instant.now();
        
        // Submit operations to the executor
        for (int i = 0; i < NUM_OPERATIONS; i++) {
            executor.submit(() -> {
                String randomUserId = getRandomUserId();
                User user = readUserFromDb(randomUserId);
                List<Order> orders = readOrdersFromDb(randomUserId);
                if (user != null) {
                    completedOps.incrementAndGet();
                }
            });
        }
        
        shutdownExecutor(executor);
        
        Duration duration = Duration.between(start, Instant.now());
        logger.info("Virtual threads: {} operations completed in {}ms", 
                completedOps.get(), duration.toMillis());
        
        // Show theoretical comparison
        logger.info("With {} platform threads, this would take approximately {}ms theoretically",
                PLATFORM_THREAD_POOL_SIZE,
                duration.toMillis() * (NUM_OPERATIONS / PLATFORM_THREAD_POOL_SIZE));
    }
    
    /**
     * Safely shuts down an executor service and waits for completion.
     */
    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Simulates reading a user from the database with network latency.
     */
    private static User readUserFromDb(String userId) {
        simulateDatabaseLatency();
        return userDatabase.get(userId);
    }
    
    /**
     * Simulates reading orders from the database with network latency.
     */
    private static List<Order> readOrdersFromDb(String userId) {
        simulateDatabaseLatency();
        return orderDatabase.getOrDefault(userId, new ArrayList<>());
    }
    
    /**
     * Gets a random user ID from the database.
     */
    private static String getRandomUserId() {
        List<String> userIds = new ArrayList<>(userDatabase.keySet());
        int randomIndex = (int) (Math.random() * userIds.size());
        return userIds.get(randomIndex);
    }
    
    /**
     * Simulates database network latency.
     */
    private static void simulateDatabaseLatency() {
        try {
            // Simulate network latency and database processing time
            Thread.sleep(50 + (int)(Math.random() * 30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Simple User class for the example.
     */
    static class User {
        final String id;
        final String name;
        final String email;
        
        User(String id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }
    }
    
    /**
     * Simple Order class for the example.
     */
    static class Order {
        final String id;
        final String userId;
        final double amount;
        
        Order(String id, String userId, double amount) {
            this.id = id;
            this.userId = userId;
            this.amount = amount;
        }
    }
} 