# rate-limiter

Spring-only (non-Boot) rate limiter project to practice rate limiting algorithms.

## Overview

A `HandlerInterceptor` (`RateLimitInterceptor`) sits in front of every request,
looks up a per-client `RateLimiter` (keyed by client IP), and returns **HTTP 429**
(with a `Retry-After` header) when the client exceeds its allowance.

Five algorithms are implemented behind a common `RateLimiter` interface:

- Token Bucket
- Leaking Bucket
- Fixed Window
- Sliding Window Log
- Sliding Window Counter

Per-client limiters are held in a bounded Caffeine cache, so memory and scheduler
threads stay bounded even as client IPs churn (see [How the limiter cache works](#how-the-limiter-cache-works)).

## Configuration

Everything is driven by `src/main/resources/application.properties` — no code
changes needed to switch algorithms or tune limits. Select the algorithm with:

```properties
scheduler.type=token_bucket   # token_bucket | leaking_bucket | fixed_window | sliding_window_counter | sliding_window_log
scheduler.retryAfter=2        # value sent in the Retry-After header on 429s
```

An invalid `scheduler.type` fails fast at startup (it's bound to the
`SchedulerTypes` enum). Each algorithm reads its own properties (e.g.
`tokenbucket.bucketSize`, `fixedWindow.noOfRequests`); if one is omitted the
interceptor falls back to a built-in default (~100 requests/sec) via the
`${property:default}` syntax on its `@Value` fields.

Cache knobs:

```properties
cache.size=1000        # max distinct clients tracked (hard cap)
cache.expireAfter=1    # idle TTL in minutes; an idle client's limiter is evicted after this
```

## How the limiter cache works

`RateLimitInterceptor` keeps limiters in a `Cache<String, RateLimiter>` (Caffeine):

- **`cache.get(ip, fn)`** atomically gets-or-creates the limiter, so a limiter is
  built and its scheduler started **exactly once** per IP, even under concurrent
  first-requests.
- **`maximumSize(cache.size)`** caps the number of tracked clients, defending
  against a flood of unique IPs.
- **`expireAfterAccess(cache.expireAfter)`** reclaims limiters for clients that
  went idle. The timer resets on every request, so active clients are never
  evicted. (You need both this and the size cap: TTL handles idle clients, the cap
  bounds a flood of active ones.)
- **`evictionListener` → `stopScheduler()`** shuts down a limiter's daemon thread
  when its entry is evicted, so scheduler threads don't outlive their limiter.
- **`Scheduler.systemScheduler()`** makes time-based expiry prompt. Without it
  Caffeine only does expiry maintenance during cache operations, so an idle entry
  (and its thread) could linger until the next request touched the cache — which
  would undermine the thread cleanup. Keep this wired.
- **`@PreDestroy`** stops every remaining scheduler on shutdown.

## Running & Testing

```
mvn package -DskipTests
java -jar target/my-rate-limiter-0.0.1-SNAPSHOT.jar
python src\main\resources\test_script\test_rate_limiter.py
```

The test script identifies callers via an `X-Forwarded-For` header (so it can
simulate distinct clients) and runs three HTTP-asserted checks: rate limiting,
recovery after the limiter refills, and per-client isolation.

Cache eviction + thread cleanup can't be asserted over HTTP (every algorithm also
recovers on its own), so it's confirmed via server-log lines. Set
`cache.expireAfter=1`, then run with `--evict`:

```
python src\main\resources\test_script\test_rate_limiter.py --evict
```

Watch the console for `[Cache] creating limiter …` and, ~60s later with no traffic,
`[Cache] evicting limiter … cause=EXPIRED` — the eviction firing without any
request is the proof that idle limiters (and their threads) are reclaimed.

> The `[Cache] …` `System.out.println` lines in `RateLimitInterceptor` are test
> instrumentation — fine to drop or move to a real logger.

---

## TODO / Future work

- **Reduce threads per client.** Each scheduler-based limiter (`LeakingBucket`,
  `FixedWindow`, `SlidingWindowCounter`) still runs its own daemon thread. The
  cache now bounds the count and stops threads on eviction, so it's no longer a
  leak — but it's still one thread per tracked client. A shared
  `ScheduledExecutorService`, or computing resets lazily on read (the way
  `TokenBucket` already does), would scale better.
- **Per-route / per-user limits.** Limits are global and keyed only by IP. Support
  different limits per endpoint or per API key.
- **Distributed rate limiting.** State is in-memory and per-instance, so limits
  don't hold across multiple app instances behind a load balancer. Move counters to
  a shared store (e.g. Redis with atomic Lua scripts) for a real deployment.
- **Actually forward requests.** The `forwardRequest(...)` / `leak(...)` hooks in
  the limiters are stubs — wire them to real downstream handling if the
  leaking-bucket queue semantics are meant to be exercised end to end.
