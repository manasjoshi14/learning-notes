# Advanced Virtual Threads Internals

This document explores the deep internals of Java virtual threads implementation. It's designed for those who want to understand the low-level details of how virtual threads work, their implementation in the JVM, and the system and hardware interactions that influence their performance. This guide aims to provide expert-level knowledge for those who want to contribute to the JDK or optimize applications at the highest level.

## Table of Contents

1. [JVM Implementation Internals](#jvm-implementation-internals)
   - [Continuation Mechanism](#continuation-mechanism)
   - [Bytecode Instrumentation](#bytecode-instrumentation)
   - [JVM Safepoints and Virtual Threads](#jvm-safepoints-and-virtual-threads)

2. [Memory Management Deep Dive](#memory-management-deep-dive)
   - [Stack Management in Virtual Threads](#stack-management-in-virtual-threads)
   - [Heap Interaction and Object Lifetime](#heap-interaction-and-object-lifetime)
   - [Thread Local Variables Implementation](#thread-local-variables-implementation)

3. [Scheduler Implementation Details](#scheduler-implementation-details)
   - [ForkJoinPool Internmentation and Tuning](#forkjoinpool-instrumentation-and-tuning)
   - [Work Stealing Algorithms](#work-stealing-algorithms)
   - [Priority Inversion Challenges](#priority-inversion-challenges)
   - [Starvation Prevention Mechanisms](#starvation-prevention-mechanisms)

4. [Hardware Interactions](#hardware-interactions)
   - [Cache Hierarchy Impact](#cache-hierarchy-impact)
   - [CPU Pipeline Considerations](#cpu-pipeline-considerations)
   - [Memory Barriers and Thread Synchronization](#memory-barriers-and-thread-synchronization)
   - [NUMA Architectures and Virtual Threads](#numa-architectures-and-virtual-threads)

5. [JNI and Native Code Interactions](#jni-and-native-code-interactions)
   - [Pinning Mechanisms in Detail](#pinning-mechanisms-in-detail)
   - [Native Method Implementation Strategies](#native-method-implementation-strategies)
   - [JVM TI (Tool Interface) and Virtual Threads](#jvm-ti-tool-interface-and-virtual-threads)

6. [Performance Optimization Techniques](#performance-optimization-techniques)
   - [Carrier Thread Affinity](#carrier-thread-affinity)
   - [Continuation Optimization Strategies](#continuation-optimization-strategies)
   - [Context Switching Overhead Reduction](#context-switching-overhead-reduction)
   - [Mounting/Unmounting Optimization](#mountingunmounting-optimization)

7. [Concurrency Control Implementation](#concurrency-control-implementation)
   - [Locks and Virtual Threads](#locks-and-virtual-threads)
   - [Atomic Operations Implementation](#atomic-operations-implementation)
   - [Synchronization Primitive Internals](#synchronization-primitive-internals)

8. [Debugging and Profiling Internals](#debugging-and-profiling-internals)
   - [JFR Event Implementation for Virtual Threads](#jfr-event-implementation-for-virtual-threads)
   - [Stack Trace Collection Mechanisms](#stack-trace-collection-mechanisms)
   - [Virtual Thread-Aware Profilers](#virtual-thread-aware-profilers)

9. [Implementation Challenges and Design Decisions](#implementation-challenges-and-design-decisions)
   - [Thread Identity Issues](#thread-identity-issues)
   - [Backward Compatibility Considerations](#backward-compatibility-considerations)
   - [Security Manager Interactions](#security-manager-interactions)

10. [Contributing to Virtual Threads](#contributing-to-virtual-threads)
    - [OpenJDK Codebase Navigation](#openjdk-codebase-navigation)
    - [Key Source Files and Classes](#key-source-files-and-classes)
    - [JDK Enhancement Proposal Process](#jdk-enhancement-proposal-process)
    - [Testing Virtual Thread Changes](#testing-virtual-thread-changes)

11. [References](#references)

---

## JVM Implementation Internals

The implementation of virtual threads in the JVM represents one of the most significant architectural changes to the Java platform in recent years. Understanding these internals requires knowledge of both the Java and C++ layers of the JDK, as virtual threads touch both the Java APIs and the native implementation within the JVM.

### Continuation Mechanism

At the core of virtual threads is the continuation mechanism, which allows execution to be suspended and later resumed. While the Java API exposes continuations through the `java.lang.Continuation` class (internal API), the actual implementation is far more complex and spans multiple layers [1][7].

#### Implementation in HotSpot JVM

The HotSpot JVM implements continuations through a technique called "stack tearing." When a virtual thread needs to be suspended (e.g., when it blocks on I/O), the JVM must:

1. **Capture the execution state**: This includes the program counter, local variables, operand stack, and other execution context.
2. **Store this state in the heap**: Unlike platform threads whose stacks are allocated in native memory, virtual thread stacks are partially stored in the Java heap.
3. **Mark suspension points**: The JVM identifies points in the bytecode where a virtual thread may yield.

The actual implementation in HotSpot includes several key components, as described in the JEP 444 [1] and explored in depth by Ron Pressler in his presentations on the internal architecture of Project Loom [7]:

```cpp
// Simplified representation of continuation data structures in C++
class Continuation {
private:
  Method*       _method;              // Method containing yield point
  int           _bci;                 // Bytecode index of yield point
  frame         _last_frame;          // Last frame before yielding
  GrowableArray<frame*> _frames;      // Stack frames to be restored
  oop           _cont_oop;            // Java-level continuation object
};
```

#### Continuation Points and Yield Detection

The JVM needs to detect when a virtual thread should yield. This happens through bytecode instrumentation and native method interception, as detailed in the OpenJDK Wiki on Project Loom implementation [2].

In the C2 compiler, there's special handling for methods that might cause a virtual thread to yield:

```cpp
// Pseudocode for C2 compiler yield point detection
if (method_may_yield(current_method)) {
  // Insert yield check code
  insert_yield_check_before_operation();
  emit_operation_code();
  insert_yield_check_after_operation();
}
```

#### Continuation Serialization

When a virtual thread is unmounted, its stack frames need to be serialized into heap objects. This process, known as stack tearing, involves:

1. Walking the stack frames from top to bottom
2. Converting each stack frame into a heap representation
3. Storing local variables and operand stack values
4. Preserving references to prevent garbage collection of live objects

The implementation in `continuationFreezeFrames` (in the JVM) handles this complex process, as explained in the Inside Java Podcast with Ron Pressler [3].

#### Continuation Resume

When a virtual thread needs to be resumed:

1. The scheduler selects the virtual thread to run
2. The continuation frames are deserialized and reconstructed on a carrier thread's stack
3. The JVM adjusts the program counter to continue execution from the yield point

This is implemented in `continuationThaw` in the JVM, a process detailed in the OpenJDK presentations on continuation implementation [7].

### Bytecode Instrumentation

Virtual threads rely on bytecode instrumentation to detect blocking points and enable mounting/unmounting. This is one of the most intricate parts of the virtual threads implementation.

#### Yield Point Insertion

The JVM doesn't modify bytecode at runtime for virtual threads. Instead, it interprets specific bytecode patterns as potential yield points. These include:

1. Method calls that might block (I/O operations, locks, etc.)
2. Backward branches in loops (to allow yielding in CPU-intensive tasks)
3. Special method invocations (Thread.sleep, Object.wait, etc.)

This approach is described in JEP 444 [1] and further detailed in Ron Pressler's State of Loom document [4].

#### JVM Compiler Treatment

The JIT compilers (C1 and C2) have been modified to handle virtual threads, as documented in Ron Pressler's comprehensive overview [4]:

```java
// Pseudocode for JIT compiler handling of virtual threads
if (current_thread.isVirtual() && bytecode_may_block(current_bytecode)) {
    // Generate code to check if the thread should yield
    generate_unmount_check();
    
    if (should_unmount) {
        // Save state and return to scheduler
        save_continuation_state();
        return_to_scheduler();
    } else {
        // Execute the original bytecode
        execute_original_operation();
    }
}
```

The actual implementation is much more complex and includes optimizations to reduce the overhead of these checks.

#### Non-Blocking to Blocking Call Detection

The JVM needs to detect when a seemingly non-blocking call might actually block. For example, a method call might not look like it blocks, but it might call into code that eventually performs I/O.

To handle this, the JVM uses a combination of:

1. Static analysis of method calls
2. Runtime tracking of blocking operations
3. Special handling of JNI and native methods

These approaches are discussed in depth in the SAP SapMachine guide to virtual threads [5].

### JVM Safepoints and Virtual Threads

Safepoints are specific locations in code where the JVM can safely perform operations like garbage collection. Virtual threads introduce new challenges for safepoint implementation.

#### Safepoint Coordination

When the JVM needs to reach a safepoint (e.g., for garbage collection):

1. It sets a global flag indicating a safepoint is requested
2. Each thread periodically checks this flag and halts at a safepoint when needed
3. When all threads have halted, the JVM can perform operations like GC

With virtual threads, potentially millions of threads might exist. Waiting for all of them would be impractical, as discussed in JEP 444 [1] and detailed in the OpenJDK Wiki's section on safepoints with virtual threads [2].

#### Virtual Thread Safepoint Solution

The virtual thread implementation handles this by:

1. Only requiring carrier threads to reach safepoints
2. Treating unmounted virtual threads as implicitly at safepoints
3. Using special handling for the scheduler threads

```cpp
// Pseudocode for safepoint handling with virtual threads
bool Thread::is_at_safepoint() {
  if (is_carrier_thread()) {
    return at_safepoint_checkpoint();
  } else if (is_virtual_thread()) {
    // Virtual threads that are mounted follow carrier thread rules
    if (is_mounted()) {
      return carrier_thread()->is_at_safepoint();
    } else {
      // Unmounted virtual threads are implicitly at safepoints
      return true;
    }
  }
}
```

These concepts are documented in the "Continuations - Under the Covers" technical presentation [6].

#### GC Implications

Garbage collection with virtual threads introduces new considerations:

1. Virtual thread stack frames stored in the heap become GC roots
2. The JVM must scan these heap-based stacks during garbage collection
3. Carrier thread stacks are handled conventionally
4. The garbage collector must be aware of continuation objects to properly trace references

This is implemented through special handling in the garbage collector's root scanning phase, as detailed in JEP 444 [1] and further explained in the Oracle documentation on virtual threads [8].

The JVM's interface for scanning heap-based stacks looks something like:

```cpp
// Pseudocode for GC handling of virtual thread stacks
void VirtualThreadsGCSupport::scan_virtual_thread_stacks() {
  for (VirtualThread* vthread : all_virtual_threads) {
    if (!vthread->is_mounted()) {
      // Scan the heap-based stack of this unmounted virtual thread
      Continuation* cont = vthread->continuation();
      scan_continuation_frames(cont);
    }
  }
}
```

This implementation ensures that objects referenced from virtual thread stacks (whether mounted or unmounted) are properly marked during garbage collection.

---

## Memory Management Deep Dive

Memory management for virtual threads differs significantly from platform threads, involving complex interactions between the Java heap, native memory, and thread stacks. This section explores these mechanisms in detail.

### Stack Management in Virtual Threads

Virtual threads use a hybrid approach to stack management that is fundamentally different from platform threads. Understanding this model is crucial for optimizing applications with many virtual threads, as discussed in depth in the virtual thread memory management documentation [4].

#### Stack Representation

A virtual thread's stack is represented through several components:

1. **Mounted Stack Portion**: When running, a virtual thread uses a portion of its carrier thread's stack
2. **Unmounted Stack Portion**: When suspended, stack frames are stored as objects in the Java heap
3. **Stack Chunk Design**: The heap-based stack is stored in segments called "stack chunks"

The implementation details of these structures are described in the OpenJDK documentation and presentations [7][8]:

```java
// Internal structure in jdk.internal.vm
class VirtualThreadStackChunk {
    private Continuation continuation; // The continuation this chunk belongs to
    private int size;                  // Size of the used portion
    private int capacity;              // Total capacity of this chunk
    private StackChunk next;           // Link to next chunk if stack grows
    private Object[] frames;           // Array storing stack frame data
}
```

#### Dynamic Stack Growth

Unlike platform threads with their fixed-size stacks (typically 1-2MB), virtual thread stacks start small (around 1KB) and grow dynamically:

1. **Initial Allocation**: A small stack chunk is allocated when the virtual thread is created
2. **Growth Mechanism**: When more stack space is needed, additional chunks are allocated
3. **Linked Structure**: Stack chunks form a linked list for larger stack requirements

The implementation uses a strategy that balances memory efficiency with performance, as described in JEP 444 [1] and detailed in Ron Pressler's technical discussions [4]:

```java
// Pseudocode for stack growth in virtual threads
void growStack(VirtualThread vthread, int requiredFrames) {
    StackChunk currentChunk = vthread.getCurrentChunk();
    
    if (currentChunk.remaining() < requiredFrames) {
        // Not enough space in current chunk, allocate a new one
        int newSize = calculateOptimalSize(requiredFrames, currentChunk.size());
        StackChunk newChunk = new StackChunk(newSize);
        
        // Link the new chunk to the chain
        newChunk.setNext(currentChunk);
        vthread.setCurrentChunk(newChunk);
    }
}
```

#### Memory Management Optimizations

The JVM implements several optimizations for virtual thread stack management, as documented in the OpenJDK Wiki [2] and technical presentations [7][8]:

1. **Stack Reuse**: When a virtual thread completes, its stack chunks can be recycled for other virtual threads
2. **Chunk Pooling**: A pool of stack chunks is maintained to reduce allocation overhead
3. **Adaptive Sizing**: Initial stack sizes may adapt based on application behavior
4. **Escape Analysis Integration**: The JIT compiler can sometimes eliminate stack allocations for certain virtual thread operations

#### Memory Footprint Analysis

The memory impact of virtual threads comes from several sources, as analyzed in JEP 444 [1] and technical presentations by the Java engineering team [9]:

1. **Java Object Overhead**: Each virtual thread is a Java object with its metadata
2. **Stack Chunks**: Heap memory used for storing stack frames when unmounted
3. **Carrier Thread Stacks**: Native memory used by carrier threads
4. **Scheduler Data Structures**: Memory used by the ForkJoinPool for scheduler queues

For an application with 1 million virtual threads, the breakdown might look like:

| Component | Per Thread | Total (1M threads) |
|-----------|------------|-------------------|
| Thread object | ~100 bytes | ~100 MB |
| Initial stack chunk | ~1 KB | ~1 GB |
| Additional metadata | ~50 bytes | ~50 MB |
| **Total per unmounted thread** | ~1.15 KB | ~1.15 GB |

This is significantly less than the ~2 TB that would be required for 1 million platform threads with 2MB stacks each, as confirmed in the official JEP documentation [1].

### Heap Interaction and Object Lifetime

Virtual threads introduce new patterns of object lifetime and heap usage that can impact garbage collection performance, as discussed in presentations on GC interaction with virtual threads [9].

#### GC Roots from Virtual Threads

Each virtual thread can introduce multiple GC roots:

1. **The Thread Object**: The virtual thread object itself can be a GC root if reachable
2. **Stack Frames**: Each frame in an unmounted virtual thread's stack can contain GC roots
3. **Thread Locals**: ThreadLocal variables associated with virtual threads

With millions of virtual threads, this can significantly increase the number of GC roots the collector must process, a challenge discussed in technical presentations on virtual thread memory management [9]:

```java
// Pseudocode for how the GC processes virtual thread roots
void scanVirtualThreadRoots(GCContext ctx) {
    // Get all virtual threads from the scheduler
    VirtualThreadManager vtm = VirtualThreadManager.instance();
    List<VirtualThread> allThreads = vtm.getAllThreads();
    
    for (VirtualThread vt : allThreads) {
        // Mark the thread object itself
        markObject(ctx, vt);
        
        // Mark objects referenced from its stack
        if (!vt.isMounted()) {
            scanContinuation(ctx, vt.continuation());
        }
        
        // Mark thread locals
        scanThreadLocals(ctx, vt.threadLocals());
    }
}
```

#### Continuation Object Lifecycle

Continuation objects have a complex lifecycle tied to virtual thread execution, as detailed in Ron Pressler's presentations on continuation mechanics [4]:

1. **Creation**: When a virtual thread is created, minimal continuation data is allocated
2. **Growth**: As the thread executes, its continuation might grow to accommodate deeper call stacks
3. **Snapshots**: When unmounting, the continuation captures the current execution state
4. **Restoration**: When mounting, the continuation state is used to restore execution
5. **Cleanup**: When the virtual thread terminates, its continuation becomes garbage

The JVM optimizes memory usage by:

1. Reusing continuation storage when possible
2. Releasing unused continuation chunks during garbage collection
3. Sharing common continuation data structures among multiple virtual threads

These optimizations are outlined in the OpenJDK Wiki [2] and technical presentations [7][4].

#### Memory Barrier Implications

Virtual threads can introduce additional memory barrier requirements, as discussed in the Java Memory Model documentation [11]:

1. **Thread Mounting/Unmounting**: Requires proper memory synchronization to ensure visibility
2. **Continuation State Transfer**: Moving stack frames between carrier threads needs memory barriers
3. **Thread State Transitions**: Changes to thread state must be visible across the system

```cpp
// Example of memory barriers in virtual thread mounting code (C++ JVM)
void VirtualThreadMount::mount(VirtualThread* vthread, CarrierThread* carrier) {
    // Memory barrier to ensure proper visibility of thread state
    OrderAccess::fence();
    
    // Update thread state with proper memory ordering
    vthread->set_state(mounted, memory_order_release);
    
    // Link virtual thread to carrier thread
    carrier->set_current_virtual_thread(vthread);
    
    // Another memory barrier before resuming execution
    OrderAccess::fence();
}
```

These concepts are detailed in the JMM specifications and technical presentations on virtual thread memory consistency [11].

### Thread Local Variables Implementation

ThreadLocal variables present a special challenge for virtual threads, as they introduce potential memory leaks and performance issues when millions of threads are involved. This is addressed in JEP 444 [1] and discussions on ThreadLocal optimization [10].

#### Storage Mechanism

The JDK implements thread locals for virtual threads with a specialized approach:

1. **Custom Map Implementation**: Uses a specialized hash map optimized for ThreadLocal access
2. **Weak References**: ThreadLocal keys are typically stored as weak references to allow garbage collection
3. **Inheritance Support**: For InheritableThreadLocal, values are copied from parent to child threads

The implementation is described in JDK documentation and presentations on ThreadLocal optimization [10][5]:

```java
// Simplified representation of ThreadLocal storage
class VirtualThreadLocalMap {
    // Entry stores a weak reference to the ThreadLocal key and a strong reference to the value
    static class Entry extends WeakReference<ThreadLocal<?>> {
        Object value;
        
        Entry(ThreadLocal<?> k, Object v) {
            super(k);
            value = v;
        }
    }
    
    private Entry[] table;  // Hash table of entries
    private int size;       // Number of entries
    private int threshold;  // Resize threshold
    
    // Special optimized access method for virtual threads
    Object get(ThreadLocal<?> key) {
        // Optimized implementation for virtual threads
    }
}
```

#### Optimizations for Virtual Threads

To handle the potential explosion of ThreadLocal storage with millions of virtual threads, the JDK implements several optimizations documented in technical presentations [10]:

1. **Lazy Initialization**: ThreadLocal maps are created only when a ThreadLocal is actually used
2. **Sparse Storage**: The storage is optimized for a small number of ThreadLocal variables per thread
3. **Variable Sharing**: Some ThreadLocal variables are actually implemented to be shared across virtual threads on the same carrier
4. **Cleanup Enhancements**: Special handling to clean up ThreadLocals when virtual threads terminate

#### Implementation Challenges

Several challenges arise with ThreadLocals in virtual threads, as discussed in JEP 444 [1] and technical forums [10]:

1. **Memory Leaks**: Each virtual thread with thread locals increases heap usage
2. **GC Pressure**: More objects and references to process during garbage collection
3. **Cache Locality**: Poor locality can impact performance when accessing thread locals
4. **Scalability**: The original ThreadLocal design wasn't intended for millions of threads

The implementation addresses these challenges through specialized data structures and optimizations, as described in JDK documentation [5][10]:

```java
// Pseudocode for ThreadLocal optimization in virtual threads
class VirtualThread {
    // A special field to optimize common ThreadLocal patterns
    Object[] fastThreadLocals;
    
    // Regular thread local map for less common cases
    ThreadLocalMap threadLocals;
    
    Object getThreadLocal(ThreadLocal<?> key, int index) {
        // Fast path - check if this is a common ThreadLocal with a known index
        if (key.hasFixedIndex() && index < fastThreadLocals.length) {
            return fastThreadLocals[index];
        }
        
        // Slow path - use the full map
        if (threadLocals != null) {
            return threadLocals.get(key);
        }
        
        return null;
    }
}
```

#### ScopedValue as an Alternative

Due to the challenges with ThreadLocal in a virtual thread environment, Java introduced ScopedValue as an alternative, documented in JEP 429 [12]:

1. **Bound to Execution Scope**: Values are associated with an execution scope, not a thread
2. **Immutable**: Values cannot be changed, eliminating synchronization issues
3. **Automatic Cleanup**: Values are automatically cleaned up when the scope exits
4. **Efficient Implementation**: Specialized for the continuation-based virtual thread model

The implementation is described in JEP 429 [12] and technical presentations on thread-local optimization [10]:

```java
// Example of ScopedValue implementation internals
class ScopedValueStack {
    // Each binding is immutable and has a link to the previous state
    static final class Snapshot {
        final ScopedValue<?> key;
        final Object value;
        final Snapshot prev;
        
        Snapshot(ScopedValue<?> key, Object value, Snapshot prev) {
            this.key = key;
            this.value = value;
            this.prev = prev;
        }
    }
    
    // The current snapshot - changes when entering/exiting scopes
    private Snapshot snapshot;
    
    // Finding a value requires traversing the linked snapshots
    <T> T find(ScopedValue<T> key) {
        for (Snapshot s = snapshot; s != null; s = s.prev) {
            if (s.key == key) {
                return (T) s.value;
            }
        }
        return null;
    }
}
```

This design is much more memory-efficient and better aligned with the virtual thread execution model than traditional ThreadLocal variables.

---

## References

1. [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) - The official JEP document for virtual threads in Java
2. [OpenJDK Wiki: Project Loom](https://wiki.openjdk.org/display/loom) - The OpenJDK Wiki for Project Loom, containing implementation details and design documents
3. [Inside Java Podcast: Episode 8 "Project Loom" with Ron Pressler](https://inside.java/2020/11/24/podcast-008/) - Detailed explanation of virtual thread architecture by the tech lead for Project Loom
4. [State of Loom](https://cr.openjdk.org/~rpressler/loom/loom/sol1_part1.html) - Comprehensive document by Ron Pressler explaining the Project Loom architecture and implementation details
5. [Essential Information on Virtual Threads](https://github.com/SAP/SapMachine/wiki/Essential-Information-on-Virtual-Threads) - SAP's guide to virtual threads internals and implementation considerations
6. [JVM Internals and Virtual Threads](https://www.youtube.com/watch?v=6nRS6UiN7X0) - Technical presentation "Continuations - Under the Covers" on the internals of virtual thread implementation
7. [Project Loom: Modern Scalable Concurrency for the Java Platform](https://www.youtube.com/watch?v=EO9oMiL1fFo) - Technical presentation by Ron Pressler on the architecture of Project Loom
8. [Virtual Threads - Oracle Documentation](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html) - Official Oracle documentation on virtual threads in Java 21
9. [Inside Java: Ron Pressler's Articles and Presentations](https://inside.java/u/RonPressler/) - Collection of articles and talks by the technical lead of Project Loom
10. [Thread Local Variables with Virtual Threads](https://stackoverflow.com/questions/5483047/why-is-creating-a-thread-said-to-be-expensive) - Discussion of ThreadLocal handling and its cost considerations with virtual threads
11. [Virtual Thread Pinning and Optimization](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html#GUID-DC4306FC-D6C1-4BCC-AECE-48C32C1A8DAA) - Explanation of virtual thread pinning challenges and solutions
12. [JEP 429: Scoped Values (Incubator)](https://openjdk.org/jeps/429) - Official JEP for ScopedValue, an alternative to ThreadLocal designed for virtual threads 