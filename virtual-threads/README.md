_Note: This project was generated in Cursor using Claude 3.7 Sonnet. The aim was to learn about virtual threads in detail, combining theoretical explanations with practical code examples._

---

# Understanding Virtual Threads in Java 21

A practical guide to Java's lightweight concurrency solution with hands-on examples and theory.

> **[📚 Check out our Virtual Threads Q&A document](VirtualThreads-QandA.md)** - This document records questions and answers about virtual threads in Java 21, based on questions and doubts raised by the user while exploring the concept. These Q&A sessions supplement the main README documentation and provide deeper insights into specific aspects of virtual threads.
>
> **[🔬 Explore Advanced Virtual Threads Internals](VirtualThreads-Advanced.md)** - A technical deep dive into the JVM implementation architecture of virtual threads, including continuation mechanics, memory management internals, scheduler implementation, hardware interactions, and low-level optimization techniques. This document examines the core engineering decisions and trade-offs made in the JDK implementation.

## Table of Contents

1. [Introduction to Virtual Threads](#introduction-to-virtual-threads)
2. [Why Virtual Threads?](#why-virtual-threads)
3. [Platform Threads vs. Virtual Threads](#platform-threads-vs-virtual-threads)
4. [How Virtual Threads Work](#how-virtual-threads-work)
5. [Getting Started with Virtual Threads](#getting-started-with-virtual-threads)
6. [Thread-Per-Request Model](#thread-per-request-model)
7. [Thread Pools with Virtual Threads](#thread-pools-with-virtual-threads)
8. [Building a Scalable HTTP Server](#building-a-scalable-http-server)
9. [Database Operations with Virtual Threads](#database-operations-with-virtual-threads)
10. [Performance Benchmarks](#performance-benchmarks)
11. [Advanced Topics](#advanced-topics)
    - [Thread Pinning and Blocking](#thread-pinning-and-blocking)
    - [Structured Concurrency](#structured-concurrency)
    - [Debugging Virtual Threads](#debugging-virtual-threads)
    - [Controlling Carrier Threads](#controlling-carrier-threads)
12. [Common Pitfalls and Best Practices](#common-pitfalls-and-best-practices)
13. [Resources and Further Reading](#resources-and-further-reading)
14. [**Virtual Threads Q&A Document**](VirtualThreads-QandA.md)
15. [**Advanced Virtual Threads Internals**](VirtualThreads-Advanced.md)

## Introduction to Virtual Threads

Virtual threads are lightweight threads that significantly reduce the effort of writing, maintaining, and observing high-throughput concurrent applications. Introduced as a preview feature in Java 19 and fully supported in Java 21, virtual threads are part of Project Loom.

Virtual threads are implemented in user space (the JVM) rather than as operating system resources. They aim to dramatically reduce the cost of thread creation and context switching, making it practical to represent each task with its own thread.

## Why Virtual Threads?

The traditional approach to scalable applications in Java has been reactive programming or asynchronous code using CompletableFuture, callbacks, and complex frameworks. These approaches solve the problem of thread efficiency but at the cost of:

- Increased code complexity
- Harder debugging
- Difficult error propagation
- Compromised observability

Virtual threads solve the thread efficiency problem without sacrificing the simplicity of the thread-per-request model.

## Platform Threads vs. Virtual Threads

| Characteristic | Platform Threads | Virtual Threads |
|----------------|------------------|-----------------|
| Implementation | OS-level resources | JVM-managed threads |
| Memory footprint | ~2MB stack size (configurable) | ~1KB initial stack size |
| Creation cost | High (OS operation) | Low (Java object) |
| Context switching | Managed by OS scheduler | Managed by JVM scheduler |
| Scaling | Limited by system resources | Can scale to millions |
| API | java.lang.Thread | Same Thread API |
| Use case | CPU-intensive tasks | I/O-bound tasks |

### Visual Comparison

#### Original Diagram

```
┌───────────────────────────────────────────┐
│ JVM                                       │
│                                           │
│  ┌─────────────┐    ┌─────────────┐       │
│  │ Carrier     │    │ Carrier     │       │
│  │ Thread 1    │    │ Thread 2    │       │
│  └─────────────┘    └─────────────┘       │
│        │                  │               │
│  ┌─────▼─────┐      ┌─────▼─────┐         │
│  │ Virtual   │      │ Virtual   │         │
│  │ Thread A  │      │ Thread C  │         │
│  └─────┬─────┘      └───────────┘         │
│        │                                  │
│  ┌─────▼─────┐      ┌───────────┐         │
│  │ Virtual   │      │ Virtual   │         │
│  │ Thread B  │      │ Thread D  │ (waiting│
│  └───────────┘      │           │  for I/O)
│                     └───────────┘         │
└───────────────────────────────────────────┘
```

#### Enhanced Comparison Diagram

```
┌─────────────────────────────────────┐    ┌─────────────────────────────────────┐
│         Platform Threads            │    │          Virtual Threads            │
├─────────────────────────────────────┤    ├─────────────────────────────────────┤
│                                     │    │                                     │
│  ┌───────┐  ┌───────┐  ┌───────┐   │    │              JVM                    │
│  │ Java  │  │ Java  │  │ Java  │   │    │  ┌───────────────────────────────┐  │
│  │ Thread│  │ Thread│  │ Thread│   │    │  │     Scheduler (ForkJoinPool)  │  │
│  └───┬───┘  └───┬───┘  └───┬───┘   │    │  └───────────────┬───────────────┘  │
│      │          │          │       │    │                  │                  │
│  ┌───▼───┐  ┌───▼───┐  ┌───▼───┐   │    │          ┌───────▼───────┐          │
│  │  OS   │  │  OS   │  │  OS   │   │    │          │ Carrier Threads          │
│  │Thread │  │Thread │  │Thread │   │    │          │  (OS Threads)            │
│  └───────┘  └───────┘  └───────┘   │    │          └───────┬───────┘          │
│                                     │    │                  │                  │
│  1:1 Mapping                        │    │          ┌───────▼───────┐          │
│  Limited by OS resources            │    │          │                          │
│  Heavy context switching            │    │    ┌─────┴──┐┌─────┐┌────┴───┐      │
│                                     │    │    │Virtual ││Virt.││Virtual │      │
│                                     │    │    │Thread 1││Thr.2││Thread n│      │
└─────────────────────────────────────┘    │    └────────┘└─────┘└────────┘      │
                                           │    M:N Mapping (many:few)           │
                                           │    Limited mainly by memory         │
                                           │    Lightweight context switching    │
                                           └─────────────────────────────────────┘
```

## How Virtual Threads Work

Virtual threads are implemented by the JVM (Java Virtual Machine) using a technique called **continuation-based concurrency**. Here's how they work under the hood:

### Carrier Threads

Each virtual thread runs on top of a platform thread, which is called a **carrier thread**. The number of carrier threads is typically equal to the number of available CPU cores. These carrier threads are managed by a ForkJoinPool scheduler by default.

### Mounting and Unmounting

Virtual threads can be **mounted** on a carrier thread to execute code and then **unmounted** when they block on I/O operations. This is the key to their efficiency:

1. When running, a virtual thread is mounted on a carrier thread
2. When blocked on I/O, the virtual thread is unmounted from the carrier thread
3. The carrier thread is then free to run other virtual threads
4. When the I/O operation completes, the virtual thread is scheduled to be mounted again

This mounting and unmounting process happens transparently to the application code.

### Continuations

Virtual threads are built on top of a low-level mechanism called **continuations**, which capture the execution state of a thread. When a virtual thread is unmounted, its continuation (stack trace, local variables, etc.) is stored in memory.

### Memory Efficiency

Virtual threads achieve their memory efficiency by:

1. Starting with a small initial stack size (~1KB vs ~2MB for platform threads)
2. Growing their stack dynamically as needed
3. Sharing the memory of carrier threads when mounted

A typical Java application can easily support millions of virtual threads, whereas platform threads would be limited to a few thousand before exhausting system resources.

### Thread Scheduling

The ForkJoinPool scheduler manages which virtual threads run on which carrier threads. This is different from platform threads, which are scheduled by the operating system.

## Getting Started with Virtual Threads

This project includes several examples of virtual threads in action. To run the examples, make sure you have Java 21 installed.

### Building and Running the Project

```bash
# Build the project
./gradlew build

# Run the application with default example (Basic Virtual Threads)
./gradlew run

# Run with a specific example using command-line arguments
./gradlew run --args="basic"     # Run basic virtual threads example
./gradlew run --args="http"      # Run HTTP server example
./gradlew run --args="pinning"   # Run thread pinning example
./gradlew run --args="loadtest"  # Run HTTP load test example
./gradlew run --args="database"  # Run database operations example
./gradlew run --args="all"       # Run all examples
```

When running interactively (outside of Gradle), you'll see a menu with different examples you can run:

1. Basic Virtual Thread Examples
2. HTTP Server Example
3. Thread Pinning Example
4. HTTP Load Test
5. Database Operations Example
0. Exit

Simply enter the number of the example you want to run and follow the prompts.

The basic example demonstrates fundamental operations with virtual threads, including performance comparisons. In our tests, virtual threads showed significantly faster execution compared to platform threads for I/O-bound tasks.

### Project Structure

```
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/app/
│   │   │       ├── Application.java                     # Main entry point with menu
│   │   │       └── virtualthreads/                      # Virtual threads examples
│   │   │           ├── BasicVirtualThreadExample.java   # Basic usage examples
│   │   │           ├── HttpServerExample.java           # Thread-per-request HTTP server
│   │   │           ├── ThreadPinningExample.java        # Thread pinning demonstration
│   │   │           ├── HttpLoadTester.java              # Load testing utility
│   │   │           └── DatabaseOperationsExample.java   # Database operations with virtual threads
```

## Thread-Per-Request Model

The thread-per-request model assigns one thread to each incoming request, handling it from beginning to end. With platform threads, this approach doesn't scale well because threads are expensive. With virtual threads, we can efficiently use this simpler programming model.

Our `HttpServerExample` demonstrates this concept by creating a simple HTTP server that uses a virtual thread for each incoming request:

```java
// Set the executor to use virtual threads - one per request
server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
```

When you run this example (option 2 in the menu), the server will start and listen on port 8080. You can access it using:

- http://localhost:8080/api/hello - For a quick response
- http://localhost:8080/api/slow - For a response with a 2-second delay to simulate I/O
- http://localhost:8080/api/stats - For server statistics

## Thread Pools with Virtual Threads

While virtual threads don't generally need to be pooled (since they're cheap to create), there are cases where you might want to limit concurrency or organize threads.

The `BasicVirtualThreadExample` demonstrates how to create an executor service that uses virtual threads:

```java
// Creating an executor that creates a new virtual thread for each task
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

This example compares the performance of platform threads and virtual threads when executing many I/O-bound tasks.

## Building a Scalable HTTP Server

The `HttpServerExample` demonstrates how to create a scalable HTTP server using virtual threads. This is particularly useful for I/O-bound tasks where platform threads are inefficient.

The example includes:
- A fast endpoint that responds immediately
- A slow endpoint that simulates a 2-second I/O delay
- A statistics endpoint that shows request counts and active threads
- A throughput testing feature to measure performance

The interactive options allow you to:
1. View server statistics
2. Run throughput tests with configurable concurrency
3. Keep the server running for a specific duration with periodic stats

## Database Operations with Virtual Threads

The `DatabaseOperationsExample` demonstrates how to use virtual threads for database operations. This example:

- Creates an in-memory database simulation using ConcurrentHashMap
- Simulates network latency with configurable delays
- Compares platform threads (fixed pool of 50) vs virtual threads
- Performs 1,000 concurrent read operations
- Shows theoretical performance differences

When you run this example, you'll see how virtual threads can handle many more concurrent database operations efficiently, especially when operations involve I/O waits.

In our tests, virtual threads completed database operations significantly faster than platform threads, demonstrating why virtual threads are ideal for I/O-bound database operations.

## Performance Benchmarks

The project includes a load testing utility that demonstrates the performance difference between virtual threads and platform threads in an HTTP server scenario.

The `HttpLoadTester` class performs these tests:

1. Starts an HTTP server that uses the thread-per-request model
2. Creates two HTTP clients:
   - One using virtual threads for unlimited concurrency
   - One using platform threads with a fixed thread pool
3. Sends hundreds of requests to both fast and slow endpoints
4. Compares the performance results

When you run this example (option 4 in the menu or `loadtest` command-line argument), you'll see a direct comparison of throughput and response times between virtual threads and platform threads.

These tests demonstrate that virtual threads handle concurrent connections more efficiently than platform threads, particularly for I/O-bound operations. With larger loads and more complex scenarios, the difference would be even more pronounced.

## Advanced Topics

### Thread Pinning and Blocking

The `ThreadPinningExample` demonstrates an important concept: thread pinning. This occurs when a virtual thread executes code that prevents it from being unmounted from its carrier thread, such as synchronized blocks or methods.

The example shows the performance difference between using:

1. `synchronized` methods - which cause pinning
2. `ReentrantLock` - which allows proper unmounting

You'll see a significant performance difference, especially when running many virtual threads, as pinned threads negate the benefits of virtual threading.

The example also provides best practices for avoiding thread pinning in your applications.

### Structured Concurrency

Structured concurrency is another feature introduced alongside virtual threads (though still in preview in Java 21). It allows organizing related asynchronous tasks in a parent-child relationship, ensuring that tasks started in a given scope complete before the scope ends.

While not fully demonstrated in this project, structured concurrency can be implemented using the `StructuredTaskScope` API:

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Future<String> user = scope.fork(() -> fetchUser(userId));
    Future<Integer> order = scope.fork(() -> fetchOrder(orderId));
    
    scope.join();          // Wait for all tasks to complete
    scope.throwIfFailed(); // Propagate errors
    
    // Both tasks completed successfully
    processUserAndOrder(user.resultNow(), order.resultNow());
}
```

### Debugging Virtual Threads

Debugging virtual threads can be different from debugging platform threads because there can be many more of them. Here are some tips:

1. Use thread names to identify virtual threads in logs
2. Use thread dumps (but be aware they can be much larger with virtual threads)
3. Use JFR (Java Flight Recorder) events for virtual thread lifecycle events
4. Set `-Djdk.virtualThreadScheduler.showStacks=true` to debug scheduling issues
5. Watch for the 'pinned' state in thread dumps to identify pinning issues

### Controlling Carrier Threads

By default, the number of carrier threads equals the number of available processors. However, you can control this and other virtual thread behaviors through system properties:

- **Configure carrier thread count**: 
  ```
  -Djdk.virtualThreadScheduler.parallelism=16
  ```

- **Configure carrier thread priority**:
  ```
  -Djdk.virtualThreadScheduler.priority=3
  ```
  
- **Monitor pinned virtual threads** (Java 21 only, removed in Java 24):
  ```
  -Djdk.tracePinnedThreads=full
  ```

- **Create a custom scheduler**:
  ```java
  // Create a custom executor with 24 carrier threads
  Executor myScheduler = Executors.newFixedThreadPool(24);
  ThreadFactory factory = Thread.ofVirtual().scheduler(myScheduler).factory();
  
  // Create virtual threads using this custom scheduler
  Thread vthread = factory.newThread(() -> { /* task */ });
  ```

These settings can be important for fine-tuning performance in production environments.

## Common Pitfalls and Best Practices

Based on the examples in this project, here are some best practices when working with virtual threads:

1. **Avoid thread pinning**: Use `java.util.concurrent` locks (like `ReentrantLock`) instead of `synchronized` blocks/methods.

2. **Don't pool virtual threads**: Since virtual threads are cheap to create, prefer creating a new virtual thread for each task rather than pooling them.

3. **Use virtual threads for I/O-bound tasks**: Virtual threads shine when waiting for I/O, network calls, or database operations.

4. **Keep platform threads for CPU-intensive tasks**: For CPU-bound work, platform threads are still appropriate.

5. **Be careful with ThreadLocal**: Heavy use of ThreadLocal can increase memory usage with many virtual threads.

6. **Check for thread-affinity libraries**: Some libraries require operations to happen on the same thread, which can cause issues with virtual threads that may be mounted on different carrier threads.

7. **Migrate gradually**: For existing applications, consider migrating to virtual threads gradually, starting with well-isolated components.

8. **Consider memory impact**: With millions of virtual threads, memory usage for thread stacks and ThreadLocals can add up. Monitor heap usage when scaling up.

## Resources and Further Reading

A curated list of resources to deepen your understanding of virtual threads:

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [Inside Java Podcast: Virtual Threads](https://inside.java/2020/11/24/podcast-008/)
- [Project Loom: Modern Scalable Concurrency](https://wiki.openjdk.org/display/loom/Main)
- [Java Virtual Threads - Alan Bateman's Presentation](https://www.youtube.com/watch?v=lIq-x_iI-kc)
- [Practical Java 21 Virtual Threads](https://jenkov.com/tutorials/java-concurrency/java-virtual-threads.html) 