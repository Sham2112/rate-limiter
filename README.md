# rate-limiter

Spring-only (non-Boot) rate limiter project to practice rate limiting algorithms.

## Overview

A `HandlerInterceptor` (`RateLimitInterceptor`) sits in front of every request,
looks up a per-client `RateLimiter` (keyed by IP), and returns **HTTP 429** when
the client exceeds its allowance.

Five algorithms are implemented behind a common `RateLimiter` interface:

- Token Bucket
- Leaking Bucket
- Fixed Window
- Sliding Window Log
- Sliding Window Counter

## Configuration

Everything is driven by `src/main/resources/application.properties` — no code
changes needed to switch algorithms or tune limits. Select the algorithm with:

```properties
scheduler.type=token_bucket   # token_bucket | leaking_bucket | fixed_window | sliding_window_counter | sliding_window_log
```

Each algorithm reads its own properties (e.g. `tokenbucket.bucketSize`,
`fixedWindow.noOfRequests`). If a property is omitted, the interceptor falls back
to a built-in default (~100 requests/sec) via the `${property:default}` syntax on
its `@Value` fields.

## Running & Testing

```
mvn package -DskipTests
java -jar target/my-rate-limiter-0.0.1-SNAPSHOT.jar
python src\main\resources\test_script\test_rate_limiter.py
```

---

## TODO / Known Limitations

### Design notes (current trade-offs)

- **Unbounded limiter map.** `userLimiterMap` adds one entry per unique client IP
  and never removes them. Under real traffic this grows without bound (memory
  leak). Add eviction — an LRU/TTL cache (e.g. Caffeine) or a periodic sweep of
  idle entries.
- **One scheduler thread per client.** Each scheduler-based limiter
  (`LeakingBucket`, `FixedWindow`, `SlidingWindowCounter`) spawns its own daemon
  thread on creation. One thread per IP does not scale. Options: share a single
  `ScheduledExecutorService` across all limiters, or drop background threads
  entirely and reset lazily on read (the way `TokenBucket` already computes refill
  on demand).

### Improvements

- **Make limiter creation atomic.** `preHandle` does `containsKey` → `get` → `put`,
  which is not atomic. Two concurrent first-requests from the same IP can both miss
  and both build a limiter; only one wins the `put`, but the loser's scheduler
  thread is started and then leaked. Replace with `computeIfAbsent` so creation +
  scheduler start happen exactly once per key.
- **Fail fast on bad `scheduler.type`.** An invalid type currently only throws on
  the first request (per IP). Validate once at startup (`@PostConstruct`) and parse
  into an enum instead of switching on a raw `String`.
- **Wire up shutdown.** `LeakingBucket.stop()` and
  `SlidingWindowCounter.stopScheduler()` are never called. Shut schedulers down on
  eviction and on context close (`@PreDestroy`) so threads don't outlive their
  limiter. Also standardize the stop-method name across implementations (currently
  `stop()` vs `stopScheduler()`) — consider adding it to the `RateLimiter` interface.
- **Add a `Retry-After` header** on 429 responses so clients know when to retry.
- **Per-route / per-user limits.** Limits are currently global and keyed only by IP.
  Support different limits per endpoint or per API key.
- **Distributed rate limiting.** State is in-memory and per-instance, so limits
  don't hold across multiple app instances behind a load balancer. Move counters to
  a shared store (e.g. Redis with atomic Lua scripts) for a real deployment.
- **Actually forward requests.** The `forwardRequest(...)` / `sendRequests(...)`
  hooks in the limiters are stubs — wire them to real downstream handling if the
  leaking-bucket queue semantics are meant to be exercised end to end.
