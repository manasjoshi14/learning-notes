package com.example.app.virtualthreads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A load testing utility demonstrating the benefits of virtual threads.
 */
public class HttpLoadTester {
    private static final Logger logger = LoggerFactory.getLogger(HttpLoadTester.class);
    private static final String BASE_URL = "http://localhost:8080";
    private static final int DEFAULT_CONNECTION_COUNT = 100;
    private static final int DEFAULT_REQUEST_COUNT = 500;

    /**
     * Stores test results for comparison.
     */
    static class TestResult {
        final String clientType;
        final long durationMs;
        final int successCount;
        final int errorCount;
        final double requestsPerSecond;

        TestResult(String clientType, long durationMs, int successCount, int errorCount) {
            this.clientType = clientType;
            this.durationMs = durationMs;
            this.successCount = successCount;
            this.errorCount = errorCount;
            this.requestsPerSecond = (successCount + errorCount) / (durationMs / 1000.0);
        }
    }

    /**
     * Runs the load test, sending many concurrent requests to the HTTP server.
     */
    public static void runLoadTest(int connectionCount, int requestCount) {
        logger.info("Starting load test with {} concurrent connections, {} total requests", 
                connectionCount, requestCount);
        
        // Create clients
        HttpClient virtualThreadClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        HttpClient platformThreadClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(connectionCount))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        // Run tests
        TestResult virtualThreadResult = runTestWithClient("Virtual Threads", virtualThreadClient, requestCount);
        sleepSeconds(2);
        TestResult platformThreadResult = runTestWithClient("Platform Threads", platformThreadClient, requestCount);
        
        // Display comparison
        printComparisonResults(virtualThreadResult, platformThreadResult);
    }
    
    /**
     * Runs the test with the specified client.
     */
    private static TestResult runTestWithClient(String clientType, HttpClient client, int requestCount) {
        logger.info("Running test with {}", clientType);
        
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger errorCounter = new AtomicInteger(0);
        
        // Record start time
        Instant start = Instant.now();
        
        // Send requests concurrently
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            String endpoint = i % 3 == 0 ? "/api/slow" : "/api/hello";
            URI uri = URI.create(BASE_URL + endpoint);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .build();
            
            CompletableFuture<Void> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            successCounter.incrementAndGet();
                        } else {
                            errorCounter.incrementAndGet();
                        }
                        return response;
                    })
                    .thenAccept(response -> {
                        // Process completed - nothing to do here
                    })
                    .exceptionally(e -> {
                        errorCounter.incrementAndGet();
                        return null;
                    });
            
            futures.add(future);
        }
        
        // Wait for all requests to complete
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        
        try {
            allDone.get(2, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("Error waiting for requests to complete", e);
        }
        
        // Calculate results
        Duration duration = Duration.between(start, Instant.now());
        long durationMs = duration.toMillis();
        
        logger.info("{} test completed in {} ms", clientType, durationMs);
        
        return new TestResult(
                clientType,
                durationMs,
                successCounter.get(),
                errorCounter.get()
        );
    }
    
    /**
     * Print a comparison of the test results.
     */
    private static void printComparisonResults(TestResult virtualThreadResult, TestResult platformThreadResult) {
        double speedupFactor = (double) platformThreadResult.durationMs / virtualThreadResult.durationMs;
        
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== HTTP LOAD TEST RESULTS ===\n");
        sb.append("Virtual Threads: ").append(virtualThreadResult.successCount)
          .append(" successful requests in ").append(virtualThreadResult.durationMs).append("ms ")
          .append("(").append(String.format("%.2f", virtualThreadResult.requestsPerSecond)).append(" req/sec)\n");
        
        sb.append("Platform Threads: ").append(platformThreadResult.successCount)
          .append(" successful requests in ").append(platformThreadResult.durationMs).append("ms ")
          .append("(").append(String.format("%.2f", platformThreadResult.requestsPerSecond)).append(" req/sec)\n");
        
        sb.append("\nVirtual threads were ").append(String.format("%.2fx", speedupFactor)).append(" faster\n");
        
        logger.info(sb.toString());
    }
    
    /**
     * Sleep for the specified number of seconds.
     */
    private static void sleepSeconds(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Main method to run the load tester.
     */
    public static void main(String[] args) {
        HttpServerExample server = new HttpServerExample();
        
        try {
            // Start the server
            server.startServer();
            logger.info("Server started for load testing.");
            
            // Determine test parameters
            int connectionCount = DEFAULT_CONNECTION_COUNT;
            int requestCount = DEFAULT_REQUEST_COUNT;
            
            if (args.length >= 2) {
                try {
                    connectionCount = Integer.parseInt(args[0]);
                    requestCount = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid arguments, using defaults");
                }
            }
            
            // Run the load test
            runLoadTest(connectionCount, requestCount);
            
            // Keep server running briefly for manual inspection
            logger.info("Test completed. Press Ctrl+C to exit.");
            sleepSeconds(10);
            
        } catch (IOException e) {
            logger.error("Error starting server", e);
        } finally {
            server.stopServer();
        }
    }
} 