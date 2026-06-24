package com.shazam.interceptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.shazam.model.LimiterConfig;
import com.shazam.model.Route;
import com.shazam.model.RouteConfigs;
import com.shazam.model.types.SchedulerTypes;
import com.shazam.utils.NetworkUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Enforces each route's rate limit using Redis + Lua, so the limit holds in aggregate across
 * every gateway instance. The Lua scripts run the whole check-and-update atomically on the
 * Redis server; this class only picks the right script and builds its keys/args.
 *
 * (The in-memory limiters under service.ratelimiters are kept for reference but are no longer
 * used on the request path.)
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    @Value("${scheduler.retryAfter}")
    private String retryAfter;

    private final RouteConfigs routeConfigs;
    private final StringRedisTemplate redis;
    private final Map<SchedulerTypes, RedisScript<Long>> scripts;

    public RateLimitInterceptor(RouteConfigs routeConfigs, StringRedisTemplate redis,
                                Map<SchedulerTypes, RedisScript<Long>> rateLimitScripts) {
        this.routeConfigs = routeConfigs;
        this.redis = redis;
        this.scripts = rateLimitScripts;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        String client = NetworkUtils.getClientIp(request);
        Route route = NetworkUtils.selectRoute(request, routeConfigs);
        if (route == null) {
            log.info("blocked status=404 reason=no-route method={} path={} client={}",
                    request.getMethod(), request.getRequestURI(), client);
            response.setStatus(404);
            return false;
        }
        // Stash the matched route so the LoggingAspect can report it for forwarded requests.
        request.setAttribute("routeId", route.getId());

        if (!allow(route.getId(), client, route.getLimiter())) {
            log.info("blocked status=429 reason=rate-limited route={} method={} path={} client={}",
                    route.getId(), request.getMethod(), request.getRequestURI(), client);
            response.setStatus(429);
            response.setHeader("Retry-After", retryAfter);
            return false;
        }
        return true;
    }

    /** Runs the route's algorithm in Redis. Returns true if the request is admitted. */
    private boolean allow(String routeId, String client, LimiterConfig cfg) {
        RedisScript<Long> script = scripts.get(cfg.getType());
        String base = "rl:" + routeId + ":" + client;
        long now = System.currentTimeMillis();
        try {
            Long allowed = switch (cfg.getType()) {
                case fixed_window ->
                    // ARGV: limit, ttl_ms (= window; the TTL is what resets the window)
                    redis.execute(script, List.of(base),
                            str(cfg.getNoOfRequests()), str(cfg.getInterval()));

                case token_bucket -> {
                    double refillPerMs = (double) cfg.getRefillSize() / cfg.getInterval();
                    // ARGV: capacity, refill_rate (tokens/ms), ttl_ms
                    yield redis.execute(script, List.of(base),
                            str(cfg.getBucketSize()), Double.toString(refillPerMs),
                            str(bucketTtlMs(cfg.getBucketSize(), cfg.getRefillSize(), cfg.getInterval())));
                }

                case leaking_bucket ->
                    // ARGV: capacity, leak_interval_ms, leak_amount, ttl_ms
                    redis.execute(script, List.of(base),
                            str(cfg.getBucketSize()), str(cfg.getInterval()), str(cfg.getNoOfRequests()),
                            str(bucketTtlMs(cfg.getBucketSize(), cfg.getNoOfRequests(), cfg.getInterval())));

                case sliding_window_log -> {
                    String token = now + ":" + UUID.randomUUID();
                    // ARGV: limit, window, request_token, ttl_ms
                    yield redis.execute(script, List.of(base),
                            str(cfg.getNoOfRequests()), str(cfg.getInterval()), token, str(cfg.getInterval()));
                }

                case sliding_window_counter -> {
                    long windowId = now / cfg.getInterval();
                    String curr = base + ":" + windowId;
                    String prev = base + ":" + (windowId - 1);
                    // KEYS: curr_window_key, prev_window_key  |  ARGV: limit, window
                    yield redis.execute(script, List.of(curr, prev),
                            str(cfg.getNoOfRequests()), str(cfg.getInterval()));
                }
            };
            return allowed != null && allowed == 1L;
        } catch (Exception e) {
            // Fail open: if Redis is unreachable, allow traffic rather than break the gateway.
            // (Return false here instead to fail closed and enforce the limit at the cost of availability.)
            log.warn("rate-limit check failed route={} client={}, allowing (fail-open): {}",
                    routeId, client, e.toString());
            return true;
        }
    }

    private static String str(long value) {
        return Long.toString(value);
    }

    /**
     * Idle TTL for the bucket algorithms: long enough that an active bucket's state survives,
     * but the key is reclaimed after inactivity. ~2x the time to fully refill/drain.
     */
    private static long bucketTtlMs(long capacity, long ratePerInterval, long intervalMs) {
        long rate = Math.max(1, ratePerInterval);
        return Math.max(intervalMs, (capacity / rate) * intervalMs) * 2;
    }
}
