# Redis Server Initialization

This guide explores how Redis initializes its server process, from startup 
to the beginning of the event loop.
> **[📚 Check out our Q&A document](server_initialization_qna.md)** - This document records questions and answers about server initialization, based on questions and doubts raised by the user while exploring the concept. These Q&A sessions supplement the main README documentation and provide deeper insights into specific aspects of server initialization.

## Table of Contents

- [Introduction](#introduction)
- [Key Files](#key-files)
- [Initialization Sequence](#initialization-sequence)
- [Main Function](#main-function)
- [Configuration Initialization](#configuration-initialization)
- [Server Initialization](#server-initialization)
- [Key Data Structures](#key-data-structures)
  - [The Server Structure](#the-server-structure)
  - [Redis Database Structure](#redis-database-structure)
  - [Event Loop](#event-loop)
- [Event Loop Preparation](#event-loop-preparation)
- [Common Issues \& Debugging](#common-issues--debugging)

## Introduction

When Redis starts, it follows a carefully orchestrated initialization sequence to prepare all its subsystems: memory allocation, networking, commands, replication, cluster, and more. Understanding this process provides crucial insight into Redis's architecture and design principles.

This initialization happens primarily in the `main()` function of `server.c` and involves several key initialization functions that set up different parts of the Redis server.

## Key Files

To understand Redis server initialization, you should primarily focus on:

- **src/server.c**: Contains the `main()` function and most initialization code [src/server.c:7210-7811](https://github.com/redis/redis/blob/unstable/src/server.c#L7210-L7811)
- **src/server.h**: Defines the key server and client structures [src/server.h:1689-1724](https://github.com/redis/redis/blob/unstable/src/server.h#L1689-L1724)
- **src/config.c**: Configuration parsing and handling
- **src/ae.c**: Event loop implementation [src/ae.c:46-142](https://github.com/redis/redis/blob/unstable/src/ae.c#L46-L142)

## Initialization Sequence

At a high level, Redis initialization follows this sequence:

1. Parse command line arguments
2. Initialize server configuration with defaults [src/server.c:2113-2260](https://github.com/redis/redis/blob/unstable/src/server.c#L2113-L2260)
3. Load configuration file if specified
4. Set up the event loop [src/ae.c:46-77](https://github.com/redis/redis/blob/unstable/src/ae.c#L46-L77)
5. Create listening sockets [src/server.c:2560-2605](https://github.com/redis/redis/blob/unstable/src/server.c#L2560-L2605)
6. Initialize modules system
7. Initialize data structures, commands, and other subsystems
8. Enter the main event loop [src/ae.c:493-502](https://github.com/redis/redis/blob/unstable/src/ae.c#L493-L502)

Let's examine each step in more detail.

## Main Function

The entry point for Redis is the `main()` function in `server.c`. This function orchestrates the entire initialization process [src/server.c:7210-7811](https://github.com/redis/redis/blob/unstable/src/server.c#L7210-L7811):

```c
int main(int argc, char **argv) {
    struct timeval tv;
    
    /* ... initialization code ... */
    
    // Set up random number generation
    gettimeofday(&tv,NULL);
    srand(time(NULL)^getpid()^tv.tv_usec);
    srandom(time(NULL)^getpid()^tv.tv_usec);
    init_genrand64(((long long) tv.tv_sec * 1000000 + tv.tv_usec) ^ getpid());
    
    // Initialize CRC and hash seed
    crc64_init();
    uint8_t hashseed[16];
    getRandomBytes(hashseed, sizeof(hashseed));
    dictSetHashFunctionSeed(hashseed);
    
    // Check for sentinel mode
    char *exec_name = strrchr(argv[0], '/');
    if (exec_name == NULL) exec_name = argv[0];
    server.sentinel_mode = checkForSentinelMode(argc, argv, exec_name);
    
    // Initialize server configuration with defaults
    initServerConfig();
    
    // Initialize ACL system early
    ACLInit();
    
    // Initialize modules system
    moduleInitModulesSystem();
    
    // Store executable path for potential restarts
    server.executable = getAbsolutePath(argv[0]);
    
    // ... more initialization ...
    
    // Create event loop
    server.el = aeCreateEventLoop(server.maxclients + CONFIG_FDSET_INCR);
    
    // Initialize server
    initServer();
    
    // ... load modules, handle overrides ...
    
    // Start the event loop
    aeMain(server.el);
    
    // Cleanup on exit
    aeDeleteEventLoop(server.el);
    return 0;
}
```

## Configuration Initialization

Before any other initialization occurs, Redis sets up its configuration with default values through `initServerConfig()` in `server.c`. This function [src/server.c:2113-2260](https://github.com/redis/redis/blob/unstable/src/server.c#L2113-L2260):

1. Initializes the `server` global structure with default values
2. Sets up memory limits
3. Configures default networking parameters
4. Sets up default persistence parameters
5. Initializes various feature flags

Let's examine the key parts:

```c
void initServerConfig(void) {
    int j;
    
    // Initialize server structure with zeroes first
    server.hz = CONFIG_DEFAULT_HZ;
    server.timezone = timezone;
    server.port = CONFIG_DEFAULT_SERVER_PORT;
    server.tls_port = CONFIG_DEFAULT_SERVER_TLS_PORT;
    server.bindaddr_count = 0;
    server.unixsocket = NULL;
    
    // ... many other defaults ...
    
    // Set up default replication configuration
    server.masterhost = NULL;
    server.masterport = 6379;
    server.master = NULL;
    
    // Set up persistence defaults
    server.rdb_filename = zstrdup(CONFIG_DEFAULT_RDB_FILENAME);
    server.aof_filename = zstrdup(CONFIG_DEFAULT_AOF_FILENAME);
    
    // ... more defaults ...
    
    // Logging defaults
    server.verbosity = CONFIG_DEFAULT_VERBOSITY;
    server.logfile = zstrdup(CONFIG_DEFAULT_LOGFILE);
    
    // Performance and resource defaults
    server.maxmemory = CONFIG_DEFAULT_MAXMEMORY;
    server.maxmemory_policy = CONFIG_DEFAULT_MAXMEMORY_POLICY;
    
    // ... more initialization ...
}
```

After setting these defaults, if a configuration file is specified, Redis will load and apply those settings using `loadServerConfig()`.

<!-- 🔖 BOOKMARK: Reading progress - Reached here -->
## Server Initialization

After configuration is set up, the most critical initialization function is `initServer()`, which prepares all the core subsystems [src/server.c:2676-2939](https://github.com/redis/redis/blob/unstable/src/server.c#L2676-L2939):

```c
void initServer(void) {
    // Set up signal handlers
    setupSignalHandlers();
    
    // Create shared objects (frequently used Redis objects)
    createSharedObjects();
    
    // Adjust open file limit
    adjustOpenFilesLimit();
    
    // Create event loop if not done yet
    if (!server.el) {
        server.el = aeCreateEventLoop(server.maxclients + CONFIG_FDSET_INCR);
    }
    
    // Allocate databases
    server.db = zmalloc(sizeof(redisDb)*server.dbnum);
    
    // Initialize databases
    for (j = 0; j < server.dbnum; j++) {
        server.db[j].keys = kvstoreCreate(&dbDictType, slot_count_bits, flags | KVSTORE_ALLOC_META_KEYS_HIST);
        server.db[j].expires = kvstoreCreate(&dbExpiresDictType, slot_count_bits, flags);
        server.db[j].hexpires = ebCreate();
        server.db[j].expires_cursor = 0;
        server.db[j].blocking_keys = dictCreate(&keylistDictType);
        server.db[j].blocking_keys_unblock_on_nokey = dictCreate(&objectKeyPointerValueDictType);
        server.db[j].ready_keys = dictCreate(&objectKeyPointerValueDictType);
        server.db[j].watched_keys = dictCreate(&keylistDictType);
        server.db[j].id = j;
        server.db[j].avg_ttl = 0;
    }
    
    // Initialize command table, stats, etc.
    populateCommandTable();
    server.commands_stats = dictCreate(&commandStatsDictType);
    
    // ... more initialization ...
    
    // Initialize networking
    server.clients = listCreate();
    server.clients_to_close = listCreate();
    server.clients_pending_write = listCreate();
    server.clients_pending_read = listCreate();
    
    // ... more networking setup ...
    
    // Create listening sockets
    if (server.port != 0 || server.tls_port != 0) {
        listenToPort();
    }
    
    // ... various subsystem initializations ...
    
    // Register timer for serverCron
    aeCreateTimeEvent(server.el, 1, serverCron, NULL, NULL);
}
```

## Key Data Structures

During initialization, Redis sets up several critical data structures:

### The Server Structure

The most important structure is `redisServer`, defined in `server.h` [src/server.h:1689-1724](https://github.com/redis/redis/blob/unstable/src/server.h#L1689-L1724):

```c
struct redisServer {
    /* General */
    pid_t pid;                  /* Main process pid */
    char *configfile;           /* Configuration file path */
    
    /* Networking */
    int port;                   /* TCP listening port */
    char **bindaddr;            /* Addresses we bind to */
    int bindaddr_count;         /* Number of addresses in bindaddr */
    list *clients;              /* List of active clients */
    
    /* Database */
    redisDb *db;                /* Database array */
    int dbnum;                  /* Number of databases */
    
    /* Command table */
    dict *commands;             /* Command table */
    dict *orig_commands;        /* Original command table */
    
    /* Persistence */
    long long dirty;            /* Changes to DB since last save */
    long long dirty_before_bgsave; /* Used for checking BGSAVE effectiveness */
    
    /* ... many more fields ... */
};
```

### Redis Database Structure

Each database is represented by a `redisDb` structure [src/server.h:1053-1087](https://github.com/redis/redis/blob/unstable/src/server.h#L1053-L1087):

```c
typedef struct redisDb {
    kvstore *keys;              /* The keyspace for this DB */
    kvstore *expires;           /* Timeout of keys with a timeout set */
    ebuckets hexpires;          /* Hash expiration DS */
    dict *blocking_keys;        /* Keys with clients waiting for data */
    dict *blocking_keys_unblock_on_nokey; /* Keys that should unblock when deleted */
    dict *ready_keys;           /* Blocked keys that received a PUSH */
    dict *watched_keys;         /* WATCHED keys for MULTI/EXEC CAS */
    int id;                     /* Database ID */
    long long avg_ttl;          /* Average TTL, just for stats */
    unsigned long expires_cursor; /* Cursor of the active expire cycle */
} redisDb;
```

### Event Loop

Redis's event loop is initialized from `ae.c` [src/ae.c:46-77](https://github.com/redis/redis/blob/unstable/src/ae.c#L46-L77):

```c
aeEventLoop *aeCreateEventLoop(int setsize) {
    aeEventLoop *eventLoop;
    
    // Allocate event loop structure
    eventLoop = zmalloc(sizeof(*eventLoop));
    
    // Allocate space for events and fired events
    eventLoop->events = zmalloc(sizeof(aeFileEvent)*eventLoop->nevents);
    eventLoop->fired = zmalloc(sizeof(aeFiredEvent)*eventLoop->nevents);
    
    // ... more initialization ...
    
    // Initialize state
    eventLoop->setsize = setsize;
    eventLoop->lastTime = time(NULL);
    eventLoop->timeEventHead = NULL;
    eventLoop->timeEventNextId = 0;
    eventLoop->stop = 0;
    eventLoop->maxfd = -1;
    eventLoop->beforesleep = NULL;
    eventLoop->aftersleep = NULL;
    
    // Initialize I/O multiplexing implementation
    if (aeApiCreate(eventLoop) == -1) goto err;
    
    // Initialize all file events to NULL
    for (i = 0; i < setsize; i++)
        eventLoop->events[i].mask = AE_NONE;
    
    return eventLoop;
}
```

## Event Loop Preparation

Before entering the main event loop, Redis registers several critical event handlers:

1. **Server Cron Timer**: Runs periodic tasks like cleaning expired keys [src/ae.c:200-241](https://github.com/redis/redis/blob/unstable/src/ae.c#L200-L241)
2. **Listening Socket Handlers**: Accept new client connections
3. **Connection Handlers**: Manage client I/O

The actual event loop execution happens with [src/ae.c:493-502](https://github.com/redis/redis/blob/unstable/src/ae.c#L493-L502):

```c
void aeMain(aeEventLoop *eventLoop) {
    eventLoop->stop = 0;
    while (!eventLoop->stop) {
        if (eventLoop->beforesleep != NULL)
            eventLoop->beforesleep(eventLoop);
        aeProcessEvents(eventLoop, AE_ALL_EVENTS|
                                   AE_CALL_BEFORE_SLEEP|
                                   AE_CALL_AFTER_SLEEP);
    }
}
```

## Common Issues & Debugging

When debugging Redis initialization issues, focus on these common areas:

1. **Configuration errors**: Check log for "Bad directive" or similar messages
2. **Port binding issues**: Look for "Error accepting" messages
3. **Memory allocation failures**: Check for "Out of memory" errors
4. **Permissions issues**: Check for access denied messages with AOF/RDB files

Use Redis's verbose log level to get more initialization details:

```
redis-server --loglevel verbose
```