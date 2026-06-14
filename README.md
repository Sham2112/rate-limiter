# Rate Limiter / API Gateway

A lightweight API gateway, built on **Spring (no Spring Boot, no auto-configuration)**,
that sits in front of an API and enforces **rate limiting** with a choice of classic
algorithms (token bucket, leaking bucket, fixed / sliding window). It's evolving toward
a standalone gateway with request logging and authentication as cross-cutting concerns
(Spring AOP) and a Redis-backed, distributed counter so limits hold across instances.

The point of building it on plain Spring — wiring `DispatcherServlet`, interceptors,
and configuration by hand instead of leaning on Boot's magic — is to work directly with
the framework internals rather than around them.

### What this project demonstrates

- **Spring framework fundamentals without Boot** — manual `@Configuration` / Java config,
  `DispatcherServlet`, `HandlerInterceptor`, embedded Tomcat, and explicit dependency wiring.
- **Rate limiting algorithms** — five implementations (token bucket, leaking bucket, fixed
  window, sliding window log, sliding window counter) behind one interface, hot-swappable
  via config.
- **Cross-cutting concerns with Spring AOP** *(roadmap)* — request/response logging, timing,
  and authentication as aspects; a custom `@RateLimit` annotation woven by an `@Around` advice.
- **Redis integration** *(roadmap)* — distributed counters via atomic Lua scripts so the
  limit is enforced in aggregate behind a load balancer, not per-instance.
- **System-design awareness** — gateway/reverse-proxy architecture, bounded in-memory state
  (Caffeine cache with TTL + eviction), graceful shutdown, and infrastructure-level concerns
  (fail-open vs fail-closed, hop-by-hop headers, client-IP spoofing).

## Architecture

```
                      ┌─────────────────────────── Gateway (Spring, no Boot) ───────────────────────────┐
                      │                                                                                  │
   client ──request──▶│  DispatcherServlet ─▶ RateLimitInterceptor ──allowed──▶ (forward to upstream) ──┼──▶ upstream API
                      │                            │                                                     │
                      │                            └─ over limit ─▶ 429 + Retry-After ──────────────────┼──▶ client
                      │                                                                                  │
                      │   per-client limiter state:                                                      │
                      │     • now:     Caffeine cache (in-memory, TTL + size-bounded)                    │
                      │     • roadmap: Redis + Lua  (shared across instances)                            │
                      │                                                                                  │
                      │   cross-cutting (roadmap, Spring AOP): request logging · auth · metrics          │
                      └──────────────────────────────────────────────────────────────────────────────────┘
```

**Today:** the gateway runs as a `HandlerInterceptor` in front of a Spring MVC app.
Each request is keyed by client IP, checked against that client's `RateLimiter`
(one of five algorithms, chosen by config), and either passes through or is rejected
with **429**. Limiter state lives in a bounded Caffeine cache.

**Roadmap:** the interceptor becomes a config-driven reverse proxy that *forwards*
allowed requests to user-defined upstreams; limiter state moves to **Redis** (atomic
Lua scripts) so limits hold across multiple instances behind a load balancer; and
**Spring AOP** aspects add request logging, authentication, and metrics as
cross-cutting concerns. See the [implementation guide](#implementation-guide-config-driven-rate-limiting-proxy).

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

## Roadmap

The features below are the planned progression toward a full gateway. They're
ordered roughly by how much each strengthens the project as a portfolio piece —
each one demonstrates a distinct, recruiter-visible competency.

- **Cross-cutting concerns with Spring AOP.** Add `@Aspect` advice for request /
  response logging, latency metrics, and authentication — the textbook use of AOP,
  applied to the gateway's own handler/service beans. Demonstrates understanding of
  framework internals (proxies, pointcuts, advice), not just annotations.
- **AOP-driven `@RateLimit` annotation.** A custom `@RateLimit(limit, window)`
  annotation woven by an `@Around` advice, as an elegant alternative to the
  interceptor for annotated endpoints. Pairs naturally with the Redis counter below.
- **Basic authentication.** Validate an API key / `Authorization` header before a
  request is forwarded (as an aspect or a filter), and key rate limits per API key
  rather than only per IP.
- **Distributed, Redis-backed counters.** Replace per-instance in-memory state with
  Redis + atomic Lua scripts so the limit holds in aggregate behind a load balancer.
  Ties the rate-limit state to a real datastore. See
  [Implementation Guide: Config-driven rate-limiting proxy](#implementation-guide-config-driven-rate-limiting-proxy).
- **Standalone reverse-proxy gateway.** Move from in-process interceptor to a
  config-driven proxy (YAML routes → upstreams) that forwards allowed requests and
  returns the upstream response — the "API gateway" framing. Covered in the same
  implementation guide.
- **XML config variant.** Provide an XML-based `applicationContext.xml` wiring
  alongside the Java config, to showcase classic Spring configuration depth.
- **Per-route / per-user limits.** Different limits per endpoint or per API key,
  driven by the route config.

### Engineering notes (smaller cleanups)

- **Reduce threads per client.** Each scheduler-based limiter (`LeakingBucket`,
  `FixedWindow`, `SlidingWindowCounter`) still runs its own daemon thread. The cache
  bounds the count and stops threads on eviction, so it's no longer a leak — but a
  shared `ScheduledExecutorService`, or computing resets lazily on read (the way
  `TokenBucket` does), would scale better. The Redis migration retires this entirely
  (TTL replaces the reset threads).
- **Swap `System.out.println` for a real logger** (SLF4J) once the AOP logging
  aspect lands.

---

## Implementation Guide: Config-driven rate-limiting proxy

Goal: turn this from an in-process `HandlerInterceptor` into a standalone
**reverse proxy** that sits between clients and one or more upstream servers. A
user writes a YAML file describing routes — each route has an upstream URL, the
endpoint(s) it applies to, and a rate-limiter config — and the proxy applies the
right limiter per route, forwards allowed requests to the upstream, and shares
limit state across instances via Redis.

Example of the config shape you're aiming to support:

```yaml
routes:
  - id: search-api
    upstream: https://backend-a.internal:8443
    paths: ["/search/**"]
    methods: ["GET"]
    limiter:
      type: token_bucket
      bucketSize: 50
      interval: 1000
      refillSize: 50
  - id: checkout-api
    upstream: https://backend-b.internal:9000
    paths: ["/checkout", "/cart/**"]
    limiter:
      type: fixed_window
      noOfRequests: 10
      interval: 1000
```

> This is a big rearchitecture. Do it in **two phases**: first build the
> config-driven proxy keeping your existing in-memory limiters (proves the
> proxying + routing), then swap the limiter state to Redis (makes it
> distributed). Don't attempt both at once.

### Topics to learn

**A. Reverse-proxy / gateway fundamentals**

1. **What a reverse proxy does** — receive a client request, pick an upstream,
   relay the request, then stream the upstream's response back. You become an
   intermediary, not the origin server.
2. **Forwarding HTTP client** — pick one to relay requests:
   - `RestClient` (Spring 6.1+, synchronous) — closest to your current MVC stack.
   - `WebClient` (reactive) — better for high-concurrency I/O-bound proxying.
   - `java.net.http.HttpClient` (JDK built-in) or Apache HttpClient.
   Learn connection pooling, connect/read timeouts, and streaming request/response
   bodies (don't buffer large payloads fully in memory).
3. **Hop-by-hop vs end-to-end headers** (RFC 7230 §6.1) — headers like
   `Connection`, `Keep-Alive`, `Transfer-Encoding`, `TE`, `Upgrade` must **not** be
   forwarded; everything else generally should. You also rewrite `Host` and add
   `X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Host`.
4. **A catch-all entry point** — instead of `@GetMapping("/api/info")`, you need a
   handler that receives *any* path/method. In Spring MVC that's a catch-all
   `@RequestMapping("/**")` controller or a servlet `Filter`; learn how to read the
   raw request (method, URI, query string, headers, body) and write a raw response.
5. **(Off-the-shelf alternative)** — **Spring Cloud Gateway** already does all of
   this (routing predicates, filters, even a Redis `RequestRateLimiter`). It's
   reactive/Boot-centric, so it doesn't fit your "pure Spring, build it myself"
   path — but study it to see how the production version is structured.

**B. Config-driven routing (YAML)**

6. **Parsing YAML into objects** — `jackson-dataformat-yaml` (Jackson `ObjectMapper`
   with `YAMLFactory`) or SnakeYAML. Since you're non-Boot, you load and bind the
   file yourself (Boot's auto-binding isn't available). Map the YAML to a `RouteDef`
   list with nested `LimiterConfig`.
7. **Request → route matching** — given an incoming method + path, find the matching
   route. Learn Spring's `PathPattern`/`PathPatternParser` (or `AntPathMatcher`) for
   `/**` glob matching, and define precedence (most-specific wins) when multiple
   routes could match.
8. **Config validation & startup binding** — validate on load (unknown limiter
   type, missing upstream, bad URL) and fail fast. Optionally support hot-reload
   (watch the file and rebuild routes without a restart) — `WatchService`.

**C. Distributed rate limiting with Redis + Lua**

9. **Why a shared store** — with N proxy instances behind a load balancer, each
   client's counters must live in one place both instances read/write, or the
   effective limit becomes N× the configured one.
10. **Redis client** — Spring Data Redis with Lettuce (default) or Jedis. Learn
    `RedisTemplate`/`StringRedisTemplate`, `RedisConnectionFactory`, and
    `DefaultRedisScript` for running Lua.
11. **Atomicity via Lua** — the check-and-update (read count → compare to limit →
    increment → set TTL) is a read-modify-write; across instances it races. A Lua
    script executes **atomically** on the Redis server, so the whole decision is one
    indivisible step. Learn `EVAL` / `EVALSHA` / `SCRIPT LOAD`.
12. **Each algorithm in Redis** (this replaces your in-memory implementations):
    - *Fixed window:* `INCR` a key, set `EXPIRE` on first hit; reject when it exceeds
      the limit. (Redis TTL replaces your reset scheduler entirely.)
    - *Sliding window log:* a sorted set — `ZADD` the timestamp, `ZREMRANGEBYSCORE`
      to trim old entries, `ZCARD` to count — all inside one Lua script.
    - *Token / leaking bucket:* a hash holding `tokens` + `lastRefill`; Lua computes
      the refill from elapsed time and decrements.
13. **Use Redis server time, not app time** — call `TIME` inside the Lua script (or
    pass a single timestamp) so all instances agree on "now" regardless of clock
    skew between app servers.
14. **Key design & TTL** — keys like `rl:{routeId}:{clientKey}`. TTLs make Redis
    self-clean (the distributed equivalent of the Caffeine eviction you just built —
    no unbounded growth).

**D. Refactoring the limiter abstraction**

15. **Stateless limiters.** Today a `RateLimiter` is a stateful object with its own
    counters and scheduler thread. In the distributed model the state lives in Redis,
    so a limiter becomes a thin component: `boolean allow(String key, LimiterConfig
    cfg)` that runs the right Lua script. The per-limiter scheduler threads disappear
    (Redis TTL does the resetting) — which also retires the "reduce threads per
    client" TODO.

### Implementation steps

**Phase 1 — config-driven proxy (in-memory limiters):**

1. Add a YAML parser dependency; define `RouteDef` / `LimiterConfig` POJOs and load
   the file at startup into a `RouteTable` bean.
2. Replace the demo controller with a catch-all handler (or servlet `Filter`) that
   receives every request.
3. Build a route matcher: incoming method+path → matching `RouteDef` (404/no-route
   handling for misses).
4. Apply the existing limiter for that route (key by client IP + route id). On
   reject, return 429 + `Retry-After`.
5. On allow, forward to the route's upstream with your HTTP client: copy method,
   filtered headers, query, and body; add `X-Forwarded-*`; relay the upstream's
   status, headers, and body back to the client.
6. Test with two upstreams and two routes, each with a different limiter, to prove
   per-route configs work.

**Phase 2 — make it distributed (Redis):**

7. Stand up Redis (Docker), add the Spring Data Redis dependency, configure the
   connection.
8. Write one Lua script per algorithm; load each as a `DefaultRedisScript`.
9. Reimplement the `RateLimiter` interface as a Redis-backed, stateless `allow(key,
   cfg)` that `EVALSHA`s the script with the route's params.
10. Swap the proxy's limiter call to the Redis-backed version. Run two app instances
    behind a simple load balancer (or just two ports) and confirm the limit holds in
    aggregate, not per-instance.

### Gotchas to watch for

- **Don't trust client-supplied `X-Forwarded-For` for the client key.** As the edge
  proxy you're now the one who *sets* it. A client can spoof it to dodge limits or
  poison another client's bucket — derive the client key from the real socket
  address (or a trusted, validated forwarded header), and overwrite `X-Forwarded-For`
  before forwarding.
- **Hop-by-hop headers** — forwarding `Connection`/`Transfer-Encoding` etc. breaks
  responses. Strip them in both directions.
- **Redis is now a hard dependency / SPOF.** Decide fail-open (allow on Redis error,
  prioritize availability) vs fail-closed (reject, prioritize the limit) and make it
  explicit. A circuit breaker (Resilience4j) and a local fallback are worth knowing.
- **Latency** — every request now adds a Redis round-trip. Co-locate Redis, reuse a
  pooled connection, and keep the Lua script to a single round-trip (no chatty
  multi-command sequences).
- **Clock skew** — never mix app-server time with Redis-stored timestamps; pick one
  clock (Redis `TIME`).
- **EVALSHA cache misses** — Redis may evict a loaded script (`NOSCRIPT`); handle the
  fallback to `EVAL` + reload.
- **Don't become an open proxy** — only forward to upstreams that appear in your
  config; never to an arbitrary client-controlled URL.
- **Streaming** — for large request/response bodies, stream rather than buffering, or
  you'll blow up memory under load.
