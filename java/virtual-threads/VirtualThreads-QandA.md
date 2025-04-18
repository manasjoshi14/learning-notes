# Virtual Threads Q&A

This document records questions and answers about virtual threads in Java 21, based on questions and doubts raised by the user while exploring the concept. These Q&A sessions supplement the main README documentation and provide deeper insights into specific aspects of virtual threads.

## Table of Contents

- [Stack Size and Memory Allocation](#stack-size-and-memory-allocation)
- [Carrier Threads](#carrier-threads)
- [Understanding the Carrier Thread and Virtual Thread Diagram](#understanding-the-carrier-thread-and-virtual-thread-diagram)
- [ForkJoinPool Scheduler for Virtual Threads](#forkjoinpool-scheduler-for-virtual-threads)
- [ForkJoinPool Data Structures Clarification](#forkjoinpool-data-structures-clarification)
- [Locality Preference Implementation in Virtual Threads](#locality-preference-implementation-in-virtual-threads)
- [Virtual Threads and Garbage Collection](#virtual-threads-and-garbage-collection)
- [Thread Pinning: Advanced Details](#thread-pinning-advanced-details)
- [Custom Storage Alternatives to ThreadLocal](#custom-storage-alternatives-to-threadlocal)
- [Migrating from ThreadLocal to ScopedValue](#migrating-from-threadlocal-to-scopedvalue)
- [Java 24 and the Future of Thread Pinning (JEP 491)](#java-24-and-the-future-of-thread-pinning-jep-491)

## Stack Size and Memory Allocation

**Q: What does it mean by `~1KB initial stack size` memory footprint? Does it grow, is it some reference of object?**

**A:** The "~1KB initial stack size" refers to the actual memory allocation for each virtual thread's stack when it's first created.

To understand this:

1. **Every thread needs a stack** - Both platform threads and virtual threads need a stack to store local variables, method calls, and execution context.

2. **Platform threads have a fixed stack size** - Traditional platform threads allocate a large, fixed stack (typically 1-2MB) when created. This is allocated from the operating system and can't be easily resized.

3. **Virtual threads use a dynamic stack** - Virtual threads start with a small stack (~1KB) that can grow as needed. This is not just a reference to an object; it's actual memory allocation, but much smaller initially.

4. **Growth mechanism** - When a virtual thread needs more stack space (e.g., for deeper method calls or more local variables), the JVM automatically allocates more memory. This is possible because virtual thread stacks are managed in the Java heap, not by the operating system.

5. **Memory efficiency** - This dynamic sizing is a key reason why you can create millions of virtual threads without running out of memory. If each virtual thread required 2MB like platform threads, creating a million threads would require 2TB of memory!

6. **Continuation-based implementation** - When a virtual thread is unmounted from its carrier thread (e.g., during I/O), its stack state is saved as a continuation. This allows the stack to be efficiently stored and restored.

The small initial footprint combined with dynamic growth provides the efficiency that makes virtual threads practical for high-concurrency applications.

## Carrier Threads

**Q: Can we increase the carrier threads? If it is of fixed size, it can become a bottleneck + I would also want to limit this as I don't want a large number of platform threads to be created**

**A:** Yes, you can control the number of carrier threads used by virtual threads. By default, the scheduler uses a ForkJoinPool with a number of threads equal to the available processors (determined by `Runtime.getRuntime().availableProcessors()`).

Here's how you can control carrier threads:

### 1. Creating a custom scheduler

You can create a custom scheduler with a specific number of carrier threads:

```java
// Create a custom executor with a specific number of carrier threads
int carrierThreadCount = 16; // Set your desired number
ExecutorService customScheduler = Executors.newFixedThreadPool(carrierThreadCount);

// Create an executor that uses this custom scheduler for carrier threads
ExecutorService virtualThreadExecutor = Executors.newThreadPerTaskExecutor(
    Thread.ofVirtual().scheduler(customScheduler).factory()
);
```

### 2. Using system properties

For the default scheduler, you can use the system property:

```java
// Set before JVM starts
System.setProperty("jdk.virtualThreadScheduler.parallelism", "16");
```

### 3. When to adjust carrier threads

- **Increasing carrier threads**: If your virtual threads are doing CPU-intensive work that doesn't block on I/O, having more carrier threads (up to a reasonable limit) can improve throughput.

- **Decreasing carrier threads**: If your application shares the system with other applications and you want to limit CPU usage, you might reduce the number of carrier threads.

### 4. Important considerations

- **Carrier threads are only a bottleneck for CPU-bound tasks**: For I/O-bound tasks (the ideal use case for virtual threads), the carrier thread gets freed when the virtual thread blocks on I/O.

- **Too many carrier threads can reduce performance**: Having too many carrier threads can lead to excessive context switching by the OS.

- **The default is usually sensible**: The default setting (number of available processors) is appropriate for most applications since it matches the actual CPU resources available.

- **Dynamic adjustment**: In complex applications, you might consider dynamically adjusting the number of carrier threads based on system load.

In practice, the number of carrier threads should match the number of CPU cores for optimal performance in most scenarios. Virtual threads are designed to shine in I/O-bound workloads, where carrier threads can efficiently handle many virtual threads by switching between them when they block.

## Understanding the Carrier Thread and Virtual Thread Diagram

**Q: I don't understand this diagram of Carrier Thread 1. Does carrier thread first get mounted to virtual thread A and then mount to virtual thread B? How is this possible?**

**A:** The diagram can be confusing due to the vertical layout. Let me clarify how carrier threads and virtual threads work:

### Correct relationship:
- Virtual threads get mounted on carrier threads (not the other way around)
- A carrier thread can only execute one virtual thread at a time
- The arrows in the diagram represent a sequence or execution flow, not simultaneous mounting

### What the diagram is trying to show:

1. **Carrier Thread 1** is currently executing **Virtual Thread A**
2. **Virtual Thread A** is calling or scheduling **Virtual Thread B** (the arrow between them)
3. Once **Virtual Thread A** completes or yields, **Carrier Thread 1** will execute **Virtual Thread B**

### Better mental model:

Think of carrier threads as workers, and virtual threads as tasks. A worker (carrier thread) can only work on one task (virtual thread) at a time. When the task needs to wait for something (like I/O), the worker puts it aside and picks up another task.

```
┌─────────────────────────────────────────────────────┐
│ JVM                                                 │
│                                                     │
│  ┌─────────────────┐      ┌─────────────────┐       │
│  │   Carrier       │      │    Carrier      │       │
│  │   Thread 1      │      │    Thread 2     │       │
│  └───────┬─────────┘      └────────┬────────┘       │
│          │                         │                │
│          │ (executing)             │ (executing)    │
│          ▼                         ▼                │
│  ┌───────────────┐          ┌──────────────┐        │
│  │   Virtual     │          │   Virtual    │        │
│  │   Thread A    │          │   Thread C   │        │
│  └───────────────┘          └──────────────┘        │
│                                                     │
│  ┌───────────────┐          ┌──────────────┐        │
│  │   Virtual     │          │   Virtual    │        │
│  │   Thread B    │          │   Thread D   │        │
│  │  (waiting in  │          │  (blocked on │        │
│  │    queue)     │          │     I/O)     │        │
│  └───────────────┘          └──────────────┘        │
└─────────────────────────────────────────────────────┘
```

### Execution flow:

1. **At Time T1:**
   - Carrier Thread 1 is executing Virtual Thread A
   - Carrier Thread 2 is executing Virtual Thread C
   - Virtual Thread B is waiting in the queue
   - Virtual Thread D is blocked on I/O

2. **At Time T2 (when Virtual Thread A completes or yields):**
   - Carrier Thread 1 will switch to executing Virtual Thread B
   - Carrier Thread 2 continues with Virtual Thread C
   - Virtual Thread A is either completed or back in queue
   - Virtual Thread D is still blocked on I/O

3. **At Time T3 (when Virtual Thread D's I/O completes):**
   - Virtual Thread D becomes ready to run again
   - It will be scheduled on the next available carrier thread

The key advantage of virtual threads is that when a virtual thread is blocked on I/O, it doesn't block its carrier thread, allowing the carrier thread to execute other virtual threads during that time.

## ForkJoinPool Scheduler for Virtual Threads

**Q: Can you tell me more about ForkJoinPool scheduler? Fairness, strategy, preemption, what kind of datastructures are used, worst case scenarios etc.**

**A:** The ForkJoinPool scheduler is a sophisticated work-stealing scheduler that powers virtual threads in Java. Let's dive into its key aspects:

### 1. Core Architecture

- **Work-Stealing Design**: ForkJoinPool implements a work-stealing algorithm where idle threads can "steal" tasks from busy threads' queues
- **Special Configuration**: For virtual threads, Java uses a custom ForkJoinPool that's separate from the common pool used for parallel streams
- **Default Size**: By default, it creates as many carrier threads as there are available processors

### 2. Data Structures

- **Deques (Double-Ended Queues)**: Each worker thread maintains a deque of tasks
  - The thread pushes and pops from its own deque at one end
  - Other threads can steal work from the opposite end
- **Submission Queue**: A central queue for tasks submitted from external sources
- **Continuation Queue**: Special handling for continuations (suspended virtual threads)
- **Wait Queue**: For virtual threads that are blocked but need to be eventually rescheduled
  
### 3. Scheduling Strategy

- **FIFO for New Tasks**: New virtual threads are generally scheduled in a first-in, first-out manner
- **Priority for Unblocked Tasks**: Virtual threads that become unblocked (after I/O) may get priority to maintain responsiveness
- **Locality Preference**: The scheduler tries to resume a virtual thread on the same carrier thread where it ran before (when possible) for cache affinity
- **Load Balancing**: The work-stealing algorithm automatically balances load across carrier threads

### 4. Fairness

- **Cooperative Scheduling**: Virtual threads are cooperatively scheduled (they're not preempted forcibly)
- **Fairness Mechanism**: The scheduler includes mechanisms to prevent starvation:
  - Long-running virtual threads periodically yield to allow others to run
  - The scheduler may adjust priorities based on how long tasks have been waiting
- **Not Strictly Fair**: It's not strictly fair like a round-robin scheduler, as work-stealing prioritizes throughput

### 5. Preemption and Yielding

- **No True Preemption**: Unlike OS threads, virtual threads aren't preemptively scheduled by default
- **Yielding Points**:
  - Virtual threads yield at blocking operations (I/O, locks, etc.)
  - They also yield after running for some time (approximately 1ms) to prevent monopolization
  - Additional yielding occurs at method returns and backward branches in loops
- **Manual Yields**: You can call `Thread.yield()` to explicitly suggest a scheduling point

### 6. Potential Worst-Case Scenarios

- **CPU-Bound Virtual Threads**: If all virtual threads are compute-intensive with no I/O or yielding points, they'll behave more like a thread pool with size equal to available processors
- **Thread Pinning**: As covered earlier, synchronized blocks can pin virtual threads to carrier threads, reducing concurrency
- **Very Short Tasks**: If tasks are extremely short-lived, the scheduling overhead might dominate
- **Excessive Continuations**: If threads constantly block and unblock, the overhead of saving and restoring continuations can add up
- **Memory Pressure**: Creating millions of virtual threads without bounds can still cause memory issues if their combined stack space becomes too large

### 7. Monitoring and Tuning

- **JFR Events**: Java Flight Recorder includes events for virtual thread scheduling
- **JDK System Properties**:
  - `jdk.virtualThreadScheduler.parallelism`: Control number of carrier threads
  - `jdk.virtualThreadScheduler.maxPoolSize`: Maximum pool size
  - `jdk.virtualThreadScheduler.minRunnable`: Minimum number of runnable carrier threads maintained

### 8. Comparison with OS Schedulers

- **Cooperative vs. Preemptive**: OS schedulers are preemptive, while the virtual thread scheduler is cooperative
- **Context Switch Cost**: Virtual thread context switches are much cheaper than OS thread context switches
- **Scheduling Overhead**: The scheduler has more information about task relationships and can make better decisions than the OS scheduler

### 9. Evolution

The ForkJoinPool scheduler for virtual threads has been refined through multiple Java releases. The implementation in Java 21 is the result of extensive testing and optimization based on real-world usage patterns from preview versions.

### 10. Practical Advice

- In most cases, trust the default scheduler configuration
- Focus on making your virtual threads yield appropriately during long computations
- Avoid thread pinning with synchronized blocks
- For extreme performance tuning, consider using JFR to identify scheduling bottlenecks

The ForkJoinPool scheduler is a sophisticated piece of engineering that balances fairness, throughput, and responsiveness. For most applications, it "just works" and doesn't require tuning, which is part of the appeal of virtual threads.

## ForkJoinPool Data Structures Clarification

**Q: The datastructures you have mentioned in ForkJoinPool scheduler, are these specific to virtual threads custom fork join pool implementation or this is how ForkJoinPool works?**

**A:** The core data structures I mentioned are part of the standard ForkJoinPool implementation with some additions specific to virtual threads:

### Standard ForkJoinPool Data Structures (Since Java 7)
- **Work-Stealing Deques**: These double-ended queues are fundamental to all ForkJoinPool implementations, not just for virtual threads. Each worker thread maintains its own deque.
- **Submission Queue**: The central queue for tasks submitted from external sources is also standard in all ForkJoinPool implementations.

### Virtual Thread Specific Enhancements
- **Continuation Handling**: The mechanism for storing and managing continuations (suspended virtual threads) is specific to the virtual thread scheduler implementation.
- **Specialized Queues**: The specific implementation details for handling blocked virtual threads and their resumption are unique to the virtual thread scheduler.

In essence, the virtual thread scheduler builds upon the proven work-stealing architecture of ForkJoinPool that has been part of Java since Java 7, but extends it with specialized mechanisms to handle the unique requirements of virtual threads, particularly around continuation management.

The standard ForkJoinPool was designed for fork/join parallelism (dividing a task into smaller subtasks), while the virtual thread scheduler adapts this architecture for a different use case: efficiently managing many concurrent, potentially blocking tasks.

So while the fundamental work-stealing algorithm and core data structures are similar, the virtual thread scheduler has been specifically optimized for the characteristics and requirements of virtual threads.

## Locality Preference Implementation in Virtual Threads

**Q: How does the ForkJoinPool of virtual threads implement locality preference?**

**A:** The ForkJoinPool scheduler for virtual threads implements locality preference through several mechanisms designed to maximize CPU cache efficiency:

### 1. Thread Local Affinity Tracking

- The scheduler maintains information about which carrier thread last executed a particular virtual thread.
- This affinity information is stored within the virtual thread's metadata.

### 2. Preferential Scheduling

When a virtual thread becomes runnable again (e.g., after blocking on I/O completes):

- The scheduler first attempts to schedule it on the same carrier thread that previously ran it.
- If that carrier thread is busy, the scheduler may either queue the virtual thread for that specific carrier or allow work-stealing to occur.

### 3. Continuation Handoff

- When a virtual thread blocks, its continuation (the saved execution state) may be associated with the carrier thread that was running it.
- Upon unblocking, this association helps guide the scheduler to preferentially resume the virtual thread on the same carrier.

### 4. Balance with Load Distribution

The locality preference is balanced against the need for load distribution:

- If keeping locality would create significant load imbalance, the work-stealing mechanism takes precedence.
- The scheduler dynamically adjusts the strength of locality preference based on system load.

### 5. Implementation Details

At the implementation level, locality preference works through:

- **Worker Thread IDs**: Each carrier thread has a unique ID within the ForkJoinPool.
- **Continuation Metadata**: When a virtual thread is parked or blocked, this ID can be recorded in its continuation metadata.
- **Queue Selection**: When unparking, the scheduler can use this ID to try to submit the continuation to the same worker's queue.

### 6. Benefits of Locality

This locality preference provides several performance benefits:

- **CPU Cache Efficiency**: Improved cache hit rates for both data and instructions
- **Reduced Cache Coherence Traffic**: Less inter-core communication on multi-core systems
- **TLB (Translation Lookaside Buffer) Efficiency**: Better reuse of memory address translations

### 7. Adaptive Behavior

The strength of locality preference is not fixed but adapts based on:

- Current system load
- Observed performance metrics
- Queue lengths across the different carrier threads

This adaptive approach ensures that while locality is preferred, it doesn't come at the expense of overall system throughput or responsiveness in high-load situations.

It's worth noting that locality preference is a performance optimization rather than a guarantee. The scheduler prioritizes correct execution and load balancing over strict locality when necessary.

## Virtual Threads and Garbage Collection

**Q: `Executors.newVirtualThreadPerTaskExecutor()` creates a new virtual thread for every task. Do we depend on garbage collector to clean up old virtual threads?**

**A:** Yes, terminated virtual threads are cleaned up by the garbage collector, and this is by design.

### How Virtual Thread Lifecycle and Cleanup Works

1. **Creation**: When you use `Executors.newVirtualThreadPerTaskExecutor()`, each submitted task gets its own newly created virtual thread.

2. **Termination**: When a virtual thread completes its task (or throws an uncaught exception), it terminates.

3. **Resource Release**: Upon termination, a virtual thread releases most of its resources:
   - Its stack memory is freed
   - Its continuation state is cleared
   - Any thread-local variables are cleared

4. **Garbage Collection**: The virtual thread object itself becomes eligible for garbage collection once there are no references to it.

### Designed for Efficiency

This "create and discard" approach is intentional and works well for virtual threads because:

1. **Lightweight Creation**: Virtual threads are extremely cheap to create (~1KB initial memory) compared to platform threads (~2MB).

2. **No Pooling Needed**: Unlike platform threads, there's no need to recycle virtual threads through a thread pool.

3. **Low GC Pressure**: Terminated virtual threads are very small objects after their resources are released, creating minimal garbage collection pressure.

4. **Simpler Programming Model**: This model eliminates complex thread pool management logic and potential thread leaks.

### Implementation Details

The JVM implements special handling for virtual threads to ensure efficient cleanup:

- The thread's continuation (which can be large) is released immediately upon termination, not waiting for GC
- Thread-local storage is cleared proactively
- Native resources associated with the thread are released promptly
- The actual thread object that remains for GC is very small

### Practical Implications

This design choice has several implications for developers:

1. You can create millions of virtual threads without worrying about manual cleanup
2. There's no need to implement complex thread pooling for virtual threads
3. Short-lived tasks work very well with this model
4. The GC overhead is minimal compared to the benefits of the simplified programming model

In summary, depending on garbage collection for virtual thread cleanup is a deliberate design choice that aligns perfectly with the "lightweight threads" philosophy. It makes concurrent programming significantly simpler while maintaining excellent performance characteristics.

## Thread Pinning: Advanced Details

**Q: I want to understand thread pinning in more detail. What is about synchronized methods that cause pinning? What other methods cause this pinning etc. Give more advanced details.**

**A:** Thread pinning is a critical concept for optimizing virtual thread performance. Let's explore it in depth:

### The Core Mechanism of Thread Pinning

When a virtual thread is pinned, it **cannot be unmounted** from its carrier thread. This prevents the carrier thread from executing other virtual threads, effectively reducing the benefits of virtual threading.

### Why Synchronized Causes Pinning

The reason `synchronized` blocks and methods cause pinning is deeply tied to the JVM's implementation:

1. **Monitor Implementation**: Java's `synchronized` uses a low-level construct called a "monitor" that is tied to the executing platform thread.

2. **JVM Biased Locking**: The JVM uses an optimization called "biased locking" for `synchronized` that assumes a lock is typically repeatedly acquired by the same thread. This optimization directly ties the lock to the carrier thread's identity.

3. **Thread Identity Preservation**: During synchronized blocks, the JVM needs to maintain a consistent thread identity. If a virtual thread could be unmounted during a synchronized block and later mounted on a different carrier thread, the monitor ownership would be lost.

4. **JNI (Java Native Interface) Dependency**: The implementation of `synchronized` has dependencies on native code that assumes a 1:1 relationship between a Java thread and its underlying OS thread.

### Technical Implementation Detail

At the bytecode level, `synchronized` blocks generate `monitorenter` and `monitorexit` instructions that operate directly on the executing thread's frame, which assumes continuity of the executing platform thread.

### Other Constructs That Cause Pinning

Beyond synchronized, several other Java constructs can cause thread pinning:

1. **Native Methods**: Any native method call pins a virtual thread because native code might access thread-local state directly from the platform thread.

2. **`Object.wait()` and `Object.notify()`**: These methods are built on the same monitor mechanism as `synchronized` and therefore also cause pinning.

3. **Legacy I/O Operations**: Certain blocking operations in `java.io` that don't use the newer NIO APIs can cause pinning.

4. **`ThreadLocal` With Carrier Thread Inheritance**: When a `ThreadLocal` is marked to inherit to carrier threads, it can cause pinning in certain operations.

5. **JNI Critical Regions**: Code in JNI critical regions pins virtual threads.

6. **Thread.sleep(0)**: Calling `Thread.sleep(0)` (zero timeout) will pin the virtual thread but not unmount it.

7. **Some JVM Intrinsics**: Certain optimized JVM intrinsic operations pin virtual threads temporarily.

### Code Examples That Cause Pinning

```java
// Example 1: Synchronized method (causes pinning)
public synchronized void pinnedMethod() {
    // The entire method pins the virtual thread
    processData();
}

// Example 2: Synchronized block (causes pinning)
public void alsoPin() {
    synchronized (this) {
        // This block pins the virtual thread
        processData();
    }
}

// Example 3: Object wait/notify (causes pinning)
public void waitOperation() {
    synchronized (lock) {
        try {
            lock.wait(); // Pins the thread
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

// Example 4: Native method calls (cause pinning)
public void nativeMethodCall() {
    System.loadLibrary("native-lib");
    nativeMethod(); // This pins the virtual thread
}
```

### Detection and Monitoring of Pinning

Java provides tools to identify pinning issues:

1. **JDK Flight Recorder (JFR)**: JFR events can track when virtual threads are pinned:
   ```java
   // Enable JFR monitoring for pinned virtual threads
   java -XX:StartFlightRecording=settings=profile,duration=120s,filename=recording.jfr MyApp
   ```

2. **Thread Dumps**: Thread dumps will mark virtual threads that are pinned.

3. **Programmatic Detection**: You can use the experimental API:
   ```java
   if (VirtualThread.currentCarrierThread().getState() == State.PINNED) {
       // Thread is currently pinned
   }
   ```

### Alternative Non-Pinning Mechanisms

For each pinning construct, there's usually a non-pinning alternative:

| Pinning Construct | Non-Pinning Alternative |
|-------------------|-------------------------|
| `synchronized` | `java.util.concurrent.locks.ReentrantLock` |
| `Object.wait/notify` | `java.util.concurrent.locks.Condition` |
| Standard I/O | NIO (New I/O) APIs |
| `ThreadLocal` | `ScopedValue` or custom storage |
| Native methods | JVM-friendly wrappers that unpin appropriately |

### Example of Replacing Synchronized with ReentrantLock

```java
// Pinning version with synchronized
public synchronized void increment() {
    count++;
}

// Non-pinning version with ReentrantLock
private final ReentrantLock lock = new ReentrantLock();
public void increment() {
    lock.lock();
    try {
        count++;
    } finally {
        lock.unlock();
    }
}
```

### Performance Impact of Pinning

Pinning can severely impact performance in high-concurrency scenarios:

1. **Scalability Ceiling**: A pinned virtual thread prevents its carrier thread from running other virtual threads, effectively reducing your maximum concurrency to the number of available carrier threads.

2. **Cascading Contention**: In the worst case, pinning can create a "convoy effect" where many virtual threads queue up behind a few pinned threads, cascading into system-wide delays.

3. **Quantitative Impact**: Testing shows that with just 20% of virtual threads getting pinned, overall system throughput can drop by 70-80% in I/O-bound workloads.

### Advanced Pinning Scenarios

1. **Partial Method Pinning**: Some methods pin only for part of their execution. For example, `java.nio.channels.FileChannel.map()` pins only during the actual memory mapping.

2. **Conditional Pinning**: Some operations pin only under certain conditions (like buffer capacity or specific flags).

3. **Transitive Pinning**: A virtual thread can become indirectly pinned when it calls methods that in turn call pinning operations.

4. **Temporary Unmounting**: Some operations cause temporary unmounting, but then remount the virtual thread on the same carrier thread, which isn't technically pinning but can have similar effects.

### Design Guidance for Advanced Applications

1. **Audit Third-Party Libraries**: Many libraries were written before virtual threads and may have pinning patterns.

2. **Benchmark With Pinning in Mind**: Create performance tests that specifically measure the impact of potential pinning points.

3. **Consider Custom Synchronization**: For ultra-high-performance applications, custom synchronization mechanisms designed specifically for virtual threads might be warranted.

4. **Profile Different Lock Options**: Different lock implementations (StampedLock, ReadWriteLock, etc.) have different performance characteristics with virtual threads.

5. **Batch Processing in Synchronized Regions**: If synchronized blocks are unavoidable, processing multiple items per synchronized block can reduce the pinning impact.

Virtual thread pinning is one of the most important aspects to understand when optimizing for performance. By identifying and eliminating pinning points, you can achieve dramatically better scalability in high-concurrency applications.

## Custom Storage Alternatives to ThreadLocal

**Q: How do you suggest changing ThreadLocal with a custom storage?**

**A:** While Java 21 introduces `ScopedValue` as the preferred alternative to `ThreadLocal` for virtual threads, you can also implement custom storage solutions that avoid pinning. Here are several approaches for custom storage:

### 1. Thread ID-Based Map

The simplest approach is to use a thread-safe map with virtual thread IDs as keys:

```java
public class CustomThreadLocal<T> {
    // Thread-safe map to store values by virtual thread ID
    private static final ConcurrentHashMap<Long, Object> THREAD_DATA = new ConcurrentHashMap<>();
    
    // Store value for current thread
    public void set(T value) {
        THREAD_DATA.put(Thread.currentThread().threadId(), value);
    }
    
    // Get value for current thread
    @SuppressWarnings("unchecked")
    public T get() {
        return (T) THREAD_DATA.get(Thread.currentThread().threadId());
    }
    
    // Remove when done to prevent memory leaks
    public void remove() {
        THREAD_DATA.remove(Thread.currentThread().threadId());
    }
}
```

#### Key Considerations:
- You **must** call `remove()` when the thread is done to prevent memory leaks
- This approach is simple but lacks the automatic cleanup of ThreadLocal
- Thread IDs in Java are reused, so ensure proper cleanup timing

### 2. Context-Based Approach with Cleanup Registration

A more robust approach includes cleanup registration:

```java
public class ThreadContext<T> {
    private final ConcurrentHashMap<Long, T> contexts = new ConcurrentHashMap<>();
    
    public void set(T value) {
        long threadId = Thread.currentThread().threadId();
        contexts.put(threadId, value);
        
        // Optional: Register automatic cleanup for virtual threads
        if (Thread.currentThread().isVirtual()) {
            VirtualThreads.onCompletion(() -> contexts.remove(threadId));
        }
    }
    
    public T get() {
        return contexts.get(Thread.currentThread().threadId());
    }
    
    public void clear() {
        contexts.remove(Thread.currentThread().threadId());
    }
}

// Helper class (hypothetical - you would need to implement this)
class VirtualThreads {
    public static void onCompletion(Runnable action) {
        // Implementation could use Thread.onSpinWait or other mechanisms
        // to register a callback when the virtual thread completes
        Thread current = Thread.currentThread();
        // This is simplified; actual implementation would be more complex
        if (current.isVirtual()) {
            current.setUncaughtExceptionHandler((t, e) -> {
                action.run();
                // Re-throw if there was a previous handler
            });
        }
    }
}
```

### 3. Try-With-Resources Pattern for Automatic Cleanup

Use the try-with-resources pattern to ensure cleanup:

```java
public class ContextHolder<T> implements AutoCloseable {
    private final long threadId;
    private final ConcurrentHashMap<Long, T> contextMap;
    
    public ContextHolder(ConcurrentHashMap<Long, T> contextMap, T value) {
        this.contextMap = contextMap;
        this.threadId = Thread.currentThread().threadId();
        contextMap.put(threadId, value);
    }
    
    @Override
    public void close() {
        contextMap.remove(threadId);
    }
    
    public T get() {
        return contextMap.get(threadId);
    }
}

// Usage example
public void processRequest(Request request) {
    ConcurrentHashMap<Long, UserSession> sessionMap = new ConcurrentHashMap<>();
    
    try (ContextHolder<UserSession> session = new ContextHolder<>(sessionMap, getUserSession(request))) {
        // Code that needs the session
        UserSession userSession = session.get();
        processWithSession(userSession);
    } // Automatically cleans up at end of scope
}
```

### 4. Weak References for Automatic Cleanup

Using weak references can help with automatic cleanup through garbage collection:

```java
public class WeakThreadLocal<T> {
    private final ConcurrentHashMap<Long, WeakReference<T>> threadMap = new ConcurrentHashMap<>();
    
    public void set(T value) {
        threadMap.put(Thread.currentThread().threadId(), new WeakReference<>(value));
    }
    
    public T get() {
        WeakReference<T> ref = threadMap.get(Thread.currentThread().threadId());
        return (ref != null) ? ref.get() : null;
    }
    
    // Periodically clean stale entries
    public void cleanStaleEntries() {
        for (Iterator<Map.Entry<Long, WeakReference<T>>> it = threadMap.entrySet().iterator(); 
             it.hasNext();) {
            if (it.next().getValue().get() == null) {
                it.remove();
            }
        }
    }
}
```

### 5. Combined Approach with ScopedValue (Java 21+)

For Java 21+, you can combine custom storage with ScopedValue for optimal performance:

```java
public class HybridThreadStorage<T> {
    // For short-lived operations within a call chain
    private final ScopedValue<T> scopedStorage = ScopedValue.newInstance();
    
    // For longer-term storage
    private final ConcurrentHashMap<Long, T> threadMap = new ConcurrentHashMap<>();
    
    // Use scoped value for contained operations
    public <R> R withValue(T value, Supplier<R> operation) {
        return ScopedValue.where(scopedStorage, value).get(operation);
    }
    
    // Use thread map for longer-term storage
    public void setThreadValue(T value) {
        threadMap.put(Thread.currentThread().threadId(), value);
    }
    
    public T get() {
        // Try scoped value first, fall back to thread map
        T scopedValue = scopedStorage.orElse(null);
        return scopedValue != null ? scopedValue : 
               threadMap.get(Thread.currentThread().threadId());
    }
    
    public void remove() {
        threadMap.remove(Thread.currentThread().threadId());
    }
}
```

### Performance Considerations

1. **Memory Overhead**: Custom storage solutions typically have higher memory overhead than ThreadLocal
2. **Cleanup Complexity**: Ensuring proper cleanup is more complex with custom solutions
3. **Access Speed**: Map-based lookups are generally slower than native ThreadLocal access
4. **Scalability**: ConcurrentHashMap scales well but may become a contention point with millions of threads

### When to Use Custom Storage vs. ScopedValue

- Use **ScopedValue** (Java 21+) when:
  - The context is needed only within a well-defined call chain
  - You control the entire call hierarchy
  - You need the best performance

- Use **Custom Storage** when:
  - You need thread-local state across method calls not in the same chain
  - You're working with code that expects thread-local semantics but can't be refactored
  - You need to retrofit legacy code for virtual threads

In most new code targeting Java 21+, ScopedValue is the preferred solution, with custom storage as a fallback for more complex scenarios or legacy integration.

## Migrating from ThreadLocal to ScopedValue

**Q: How do we move from ThreadLocal to ScopedValues?**

**A:** Migrating from ThreadLocal to ScopedValue requires a shift in how you think about thread-local state. Here's a comprehensive guide to make this transition:

### Understanding the Fundamental Differences

Before migrating, understand the key differences:

| ThreadLocal | ScopedValue |
|-------------|-------------|
| Mutable state that can be changed anytime | Immutable state bound to a specific execution scope |
| Thread-specific | Scope-specific (independent of thread) |
| Uses `set()`, `get()` methods | Uses `where()` and binding lambdas |
| Can cause pinning with virtual threads | Designed for virtual threads - no pinning |
| Requires explicit cleanup | Automatic cleanup when scope exits |
| Any part of code can modify the value | Values can only be set at scope creation |

### Step 1: Identify ThreadLocal Usage Patterns

First, categorize your ThreadLocal usage:

1. **Request-scoped data**: Information needed throughout a request lifecycle
2. **Transaction-scoped data**: Values needed for a database transaction
3. **Authentication context**: User or security context information 
4. **Operation context**: Metadata about current operation

### Step 2: Define Your ScopedValue

Replace each ThreadLocal with a ScopedValue:

```java
// Before: ThreadLocal
private static final ThreadLocal<UserContext> USER_CONTEXT = new ThreadLocal<>();

// After: ScopedValue
private static final ScopedValue<UserContext> USER_CONTEXT = ScopedValue.newInstance();
```

### Step 3: Change Access Patterns

The biggest change is in how you access and modify the values:

```java
// Before: ThreadLocal pattern
UserContext context = new UserContext(user);
USER_CONTEXT.set(context);
try {
    processRequest();
} finally {
    USER_CONTEXT.remove(); // Cleanup required
}

// Access anywhere in call chain
void someMethod() {
    UserContext ctx = USER_CONTEXT.get();
    // Use context...
}

// After: ScopedValue pattern
UserContext context = new UserContext(user);
ScopedValue.where(USER_CONTEXT, context).run(() -> {
    processRequest();
}); // Automatic cleanup

// Access anywhere in call chain
void someMethod() {
    UserContext ctx = USER_CONTEXT.get(); // Same access, different implementation
    // Use context...
}
```

### Step 4: Handle Nested Scopes

ScopedValue supports nested scopes with the innermost binding taking precedence:

```java
// Outer scope
ScopedValue.where(REQUEST_ID, "outer-request").run(() -> {
    // REQUEST_ID.get() returns "outer-request" here
    
    // Inner scope
    ScopedValue.where(REQUEST_ID, "inner-request").run(() -> {
        // REQUEST_ID.get() returns "inner-request" here
    });
    
    // Back to outer scope
    // REQUEST_ID.get() returns "outer-request" again
});
```

### Step 5: Handle Multiple ScopedValues

For setting multiple values, use the `whereXXX` methods:

```java
// Before: Multiple ThreadLocals
USER_CONTEXT.set(userCtx);
TRANSACTION_ID.set(txId);
REQUEST_ID.set(reqId);

// After: Multiple ScopedValues
ScopedValue.where(USER_CONTEXT, userCtx)
          .where(TRANSACTION_ID, txId)
          .where(REQUEST_ID, reqId)
          .run(() -> {
              processRequest();
          });
```

### Step 6: Adapt For Return Values

For methods returning values:

```java
// Before: ThreadLocal with return value
UserContext ctx = USER_CONTEXT.set(new UserContext(user));
try {
    return processRequestWithResult();
} finally {
    USER_CONTEXT.remove();
}

// After: ScopedValue with return value
return ScopedValue.where(USER_CONTEXT, new UserContext(user))
                 .get(() -> processRequestWithResult());
```

### Common Migration Patterns

#### Pattern 1: Request Filter/Interceptor

```java
// Before: ThreadLocal in web filter
public class ContextFilter implements Filter {
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        UserContext ctx = extractUserContext(req);
        USER_CONTEXT.set(ctx);
        try {
            chain.doFilter(req, res);
        } finally {
            USER_CONTEXT.remove();
        }
    }
}

// After: ScopedValue in web filter
public class ContextFilter implements Filter {
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        UserContext ctx = extractUserContext(req);
        ScopedValue.where(USER_CONTEXT, ctx)
                  .run(() -> {
                      try {
                          chain.doFilter(req, res);
                      } catch (Exception e) {
                          throw new RuntimeException(e);
                      }
                  });
    }
}
```

#### Pattern 2: Transaction Manager

```java
// Before: ThreadLocal in transaction manager
public class TransactionManager {
    public <T> T executeInTransaction(Supplier<T> operation) {
        Transaction tx = beginTransaction();
        CURRENT_TX.set(tx);
        try {
            T result = operation.get();
            tx.commit();
            return result;
        } catch (Exception e) {
            tx.rollback();
            throw e;
        } finally {
            CURRENT_TX.remove();
        }
    }
}

// After: ScopedValue in transaction manager
public class TransactionManager {
    public <T> T executeInTransaction(Supplier<T> operation) {
        Transaction tx = beginTransaction();
        try {
            return ScopedValue.where(CURRENT_TX, tx)
                            .get(() -> {
                                T result = operation.get();
                                tx.commit();
                                return result;
                            });
        } catch (Exception e) {
            tx.rollback();
            throw e;
        }
    }
}
```

### Handling Special Cases

#### 1. Inherited Initial Values

If you used `ThreadLocal.withInitial()`:

```java
// Before: ThreadLocal with initial value
private static final ThreadLocal<RequestStats> STATS = 
    ThreadLocal.withInitial(() -> new RequestStats());

// After: ScopedValue approach
private static final ScopedValue<RequestStats> STATS = ScopedValue.newInstance();

// Supply a default when accessing
RequestStats getStats() {
    return STATS.orElse(new RequestStats());
}
```

#### 2. Modifying Values Within a Scope

Since ScopedValue is immutable, you need to rebind for changes:

```java
// Before: Modifying ThreadLocal
void incrementCounter() {
    Counter counter = COUNTER.get();
    counter.increment();
    // No need to set again since we modified the object
}

// After: For mutable objects, modify the object state
void incrementCounter() {
    Counter counter = COUNTER.get();
    counter.increment(); // This works if Counter is mutable
}

// After: For immutable objects, create a new scope
void updateImmutableValue() {
    String currentValue = IMMUTABLE_VALUE.get();
    String newValue = currentValue + "-updated";
    
    // Need to rebind, which affects only the new scope
    ScopedValue.where(IMMUTABLE_VALUE, newValue)
              .run(() -> continueProcesing());
}
```

#### 3. Adapting Existing Libraries

For libraries that expect ThreadLocal:

```java
// Bridge adapter for libraries expecting ThreadLocal
public class ScopedValueThreadLocalAdapter<T> {
    private final ThreadLocal<T> threadLocal = new ThreadLocal<>();
    private final ScopedValue<T> scopedValue;
    
    public ScopedValueThreadLocalAdapter(ScopedValue<T> scopedValue) {
        this.scopedValue = scopedValue;
    }
    
    public T get() {
        // Try ScopedValue first, fall back to ThreadLocal
        return scopedValue.isBound() ? scopedValue.get() : threadLocal.get();
    }
    
    public void set(T value) {
        threadLocal.set(value);
        // Note: Cannot set ScopedValue here, must use ScopedValue.where()
    }
    
    public void remove() {
        threadLocal.remove();
    }
}
```

### Performance Considerations

1. **Reduced Pinning**: ScopedValue eliminates the virtual thread pinning caused by ThreadLocal
2. **Memory Efficiency**: ScopedValue typically uses less memory as values are automatically cleaned up
3. **Access Speed**: ScopedValue access can be faster in virtual thread environments
4. **Creation Overhead**: There's a small overhead when creating many nested scopes

### Migration Strategy for Large Codebases

For large existing applications:

1. **Incremental Approach**: Migrate one ThreadLocal at a time
2. **Start at Boundaries**: Begin with application entry points like filters and controllers
3. **Create Adapters**: Use adapter patterns during transition phase
4. **Test Thoroughly**: ScopedValue behavior differs from ThreadLocal in subtle ways

### Best Practices

1. **Keep values immutable**: Prefer immutable objects for ScopedValue to avoid confusion
2. **Structure code for clear scopes**: Design code with clear scope boundaries
3. **Document rebinding**: When a value needs to change, document the rebinding pattern
4. **Add bound checks**: Use `isBound()` to check if a ScopedValue is available
5. **Consider default values**: Use `orElse()` or `orElseThrow()` for better error handling

ScopedValue represents a significant improvement over ThreadLocal for virtual thread applications, providing cleaner semantics, automatic cleanup, and improved performance. While the migration requires some rethinking of how thread-local state is managed, the benefits for virtual thread applications are substantial.

## Java 24 and the Future of Thread Pinning (JEP 491)

**Q: I see some talks about fixing thread pinning issue in Java 24. Can you give me more details on this?**

**A:** Java 24 introduces a major improvement to virtual threads through JEP 491 (Synchronize Virtual Threads without Pinning), which fundamentally transforms how `synchronized` blocks work with virtual threads, eliminating most pinning scenarios.

### The Problem JEP 491 Solves

In Java 21, when a virtual thread enters a `synchronized` block or method and then attempts to block (e.g., on I/O or with `Thread.sleep()`), it remains "pinned" to its carrier thread. This creates a significant limitation:

```java
synchronized void getData() {
    byte[] result = socket.getInputStream().read(buffer); // Virtual thread pinned here!
    // Process result...
}
```

In the above code, the carrier thread cannot be reused by other virtual threads while the I/O operation is pending, which severely restricts scalability.

### How Java 24 Fixes It

JEP 491 completely reimplements how monitors (the mechanism behind `synchronized`) work with virtual threads. The major improvements include:

1. **Virtual Threads Can Unmount Inside Synchronized Blocks**: When a virtual thread blocks within a `synchronized` section, it can now unmount from its carrier thread, allowing the carrier to run other virtual threads.

2. **Monitor Ownership Tied to Virtual Threads**: Monitor ownership is now associated with the virtual thread itself rather than the carrier thread, maintaining correct synchronization semantics.

3. **Transparent Improvements**: These changes are completely transparent to your code - you don't need to change anything to benefit from them.

### Code That No Longer Pins

With Java 24, the following constructs no longer cause pinning:

1. **`synchronized` methods and blocks**: 
   ```java
   synchronized void process() {
       // Blocking I/O or Thread.sleep() no longer causes pinning!
       networkService.sendRequest();
   }
   ```

2. **`Object.wait()` and `Object.notify()`**:
   ```java
   synchronized (queue) {
       while (queue.isEmpty()) {
           queue.wait(); // Virtual thread unmounts, no pinning!
       }
       // Process queue item
   }
   ```

### Remaining Pinning Cases

A few rare scenarios will still cause pinning in Java 24:

1. **Native Methods and JNI Critical Regions**: Calls to native code still pin virtual threads
   
2. **Class Initialization Blocks**: Blocking inside static initializers
   
3. **Symbolic Reference Resolution**: Blocking during class loading

These remaining cases are relatively uncommon in application code and may be addressed in future JDK versions.

### Performance Impact

JEP 491's improvements have a dramatic effect on virtual thread scalability:

- Applications using `synchronized` blocks can now scale to hundreds of thousands or millions of virtual threads
- No need to rewrite existing code that uses `synchronized` to use `ReentrantLock`
- Better throughput for I/O-bound applications with synchronized blocks

### Monitoring in Java 24

Since most `synchronized`-related pinning is eliminated, monitoring tools have been updated:

- The `jdk.VirtualThreadPinned` JFR event now only triggers for the remaining pinning causes
- The `jdk.tracePinnedThreads` system property has been removed as it's no longer relevant for most pinning scenarios

### Migration Advice for Java 24

If you're moving to Java 24, consider the following:

1. **Revert Back to `synchronized`**: If you previously converted code to use `ReentrantLock` specifically to avoid pinning, you can consider reverting to `synchronized` for better readability (though both approaches work well now).

2. **Monitor for Remaining Pinning**: Use JFR to identify if your application has any remaining pinning points from native methods or class initialization.

3. **Keep Java Updated**: Future versions will likely address the remaining pinning scenarios.

Java 24's improvements to virtual threads make them substantially more practical for real-world applications, allowing developers to use familiar synchronization constructs without performance penalties. 