package com.example.app.virtualthreads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates the use of virtual threads in a simple HTTP server.
 */
public class HttpServerExample {

    private static final Logger logger = LoggerFactory.getLogger(HttpServerExample.class);
    private static final int PORT = 8080;
    private HttpServer server;
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger slowRequests = new AtomicInteger(0);
    private final AtomicInteger fastRequests = new AtomicInteger(0);

    /**
     * Starts the HTTP server with virtual threads handling incoming requests.
     */
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        
        // Register endpoints
        server.createContext("/api/hello", new HelloHandler(activeRequests, totalRequests, fastRequests));
        server.createContext("/api/slow", new SlowHandler(activeRequests, totalRequests, slowRequests));
        server.createContext("/api/stats", new StatsHandler());
        
        // Set the executor to use virtual threads - one per request
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        
        server.start();
        
        logger.info("HTTP Server started on port {} using virtual threads", PORT);
        logger.info("Available endpoints: /api/hello, /api/slow, /api/stats");
    }

    /**
     * Stops the HTTP server.
     */
    public void stopServer() {
        if (server != null) {
            server.stop(0);
            logger.info("HTTP Server stopped");
            printStatistics();
        }
    }
    
    /**
     * Prints current server statistics.
     */
    public void printStatistics() {
        logger.info("Server Statistics: {} total requests ({} fast, {} slow), {} active", 
                totalRequests.get(), fastRequests.get(), slowRequests.get(), activeRequests.get());
    }

    /**
     * A simple handler that responds immediately.
     */
    static class HelloHandler implements HttpHandler {
        private final AtomicInteger activeRequests;
        private final AtomicInteger totalRequests;
        private final AtomicInteger fastRequests;
        
        public HelloHandler(AtomicInteger activeRequests, AtomicInteger totalRequests, 
                AtomicInteger fastRequests) {
            this.activeRequests = activeRequests;
            this.totalRequests = totalRequests;
            this.fastRequests = fastRequests;
        }
        
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int active = activeRequests.incrementAndGet();
            try {
                String response = "Hello from Virtual Thread! Active requests: " + active;
                exchange.sendResponseHeaders(200, response.length());
                
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                
                totalRequests.incrementAndGet();
                fastRequests.incrementAndGet();
            } finally {
                activeRequests.decrementAndGet();
            }
        }
    }

    /**
     * A handler that simulates a slow processing operation.
     */
    static class SlowHandler implements HttpHandler {
        private final AtomicInteger activeRequests;
        private final AtomicInteger totalRequests;
        private final AtomicInteger slowRequests;
        
        public SlowHandler(AtomicInteger activeRequests, AtomicInteger totalRequests,
                AtomicInteger slowRequests) {
            this.activeRequests = activeRequests;
            this.totalRequests = totalRequests;
            this.slowRequests = slowRequests;
        }
        
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int active = activeRequests.incrementAndGet();
            try {
                // Simulate a slow database query or external API call
                try {
                    Thread.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                String response = "Slow response from Virtual Thread after 2 seconds! Active: " + active;
                exchange.sendResponseHeaders(200, response.length());
                
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                
                totalRequests.incrementAndGet();
                slowRequests.incrementAndGet();
            } finally {
                activeRequests.decrementAndGet();
            }
        }
    }
    
    /**
     * A handler that provides server statistics.
     */
    class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder response = new StringBuilder();
            response.append("Server Statistics:\n\n");
            response.append("Total requests: ").append(totalRequests.get()).append("\n");
            response.append("Fast requests: ").append(fastRequests.get()).append("\n");
            response.append("Slow requests: ").append(slowRequests.get()).append("\n");
            response.append("Active requests: ").append(activeRequests.get()).append("\n");
            
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length());
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.toString().getBytes());
            }
        }
    }

    /**
     * Demonstrates how to start and use the HTTP server.
     */
    public static void runExample() {
        logger.info("=== HTTP Server with Virtual Threads Example ===");
        
        HttpServerExample example = new HttpServerExample();
        
        try {
            example.startServer();
            
            // Run for a short period to allow manual testing
            logger.info("Server will run for 30 seconds. Send requests to test it.");
            try {
                TimeUnit.SECONDS.sleep(30);
                example.printStatistics();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            example.stopServer();
            
        } catch (IOException e) {
            logger.error("Error running HTTP server", e);
        }
        
        logger.info("=== End of HTTP Server Example ===");
    }
} 