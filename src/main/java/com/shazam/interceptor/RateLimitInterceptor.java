package com.shazam.interceptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;

import com.shazam.service.ratelimiters.TokenBucket;
import com.shazam.service.ratelimiters.FixedWindow;
import com.shazam.service.ratelimiters.LeakingBucket;
import com.shazam.service.ratelimiters.RateLimiter;
import com.shazam.service.ratelimiters.SlidingWindowCounter;
import com.shazam.service.ratelimiters.SlidingWindowLog;
import com.shazam.model.LimiterConfig;
import com.shazam.model.Route;
import com.shazam.model.RouteConfigs;
import com.shazam.model.types.SchedulerTypes;
import com.shazam.utils.NetworkUtils;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Value("${cache.size:1000}")
    private int cacheSize;

    @Value("${cache.expireAfter:10}")
    private int expireAfter;

    @Value("${scheduler.retryAfter}")
    private String retryAfter;

    @Autowired
    private RouteConfigs routeConfigs;

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    Cache <String, RateLimiter> cache;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,@NonNull Object handler) throws Exception{
        String ipAddress = NetworkUtils.getClientIp(request);
        Route route = NetworkUtils.selectRoute(request, routeConfigs);
        if (route == null){
            log.info("blocked status=404 reason=no-route method={} path={} client={}",
                    request.getMethod(), request.getRequestURI(), ipAddress);
            response.setStatus(404);
            return false;
        }
        // Stash the matched route so the LoggingAspect can report it for forwarded requests.
        request.setAttribute("routeId", route.getId());
        String key = ipAddress + "|" + route.getId();

        RateLimiter limiter = cache.get(key, i -> {
            log.debug("cache creating limiter key={}", i);
            RateLimiter l = createLimiter(route.getLimiter());
            if (l.hasScheduler()){
                l.startScheduler();
            }
            return l;
        });

        boolean result = limiter.handleRequest(request);
        if (!result) {
            log.info("blocked status=429 reason=rate-limited route={} method={} path={} client={}",
                    route.getId(), request.getMethod(), request.getRequestURI(), ipAddress);
            response.setStatus(429);
            response.setHeader("Retry-After", retryAfter);
        }
        return result;
    }

    private RateLimiter createLimiter(LimiterConfig limiterConfig) throws IllegalArgumentException{
        if (limiterConfig == null){
            throw new IllegalArgumentException("Could not determine limiter config from URL");
        }

        switch (limiterConfig.getType()) {
            case SchedulerTypes.fixed_window:
                return new FixedWindow(limiterConfig.getInterval(), limiterConfig.getNoOfRequests());
            case SchedulerTypes.token_bucket:
                return new TokenBucket(limiterConfig.getBucketSize(), limiterConfig.getInterval(), limiterConfig.getRefillSize());
            case SchedulerTypes.leaking_bucket:
                return new LeakingBucket(limiterConfig.getBucketSize(), limiterConfig.getInterval(), limiterConfig.getNoOfRequests());
            case SchedulerTypes.sliding_window_counter:
                return new SlidingWindowCounter(limiterConfig.getInterval(), limiterConfig.getNoOfRequests());
            case SchedulerTypes.sliding_window_log:
                return new SlidingWindowLog(limiterConfig.getInterval(), limiterConfig.getNoOfRequests());
            default:
                throw new IllegalArgumentException("valid scheduler type not provided");
        }
    }

    @PostConstruct
    private void createCache(){
        this.cache = Caffeine.newBuilder()
            .expireAfterAccess(expireAfter, TimeUnit.MINUTES)
            .scheduler(Scheduler.systemScheduler()) 
            .evictionListener((String key, RateLimiter limiter, RemovalCause cause) -> {
                log.debug("cache evicting limiter key={} cause={}", key, cause);
                if (limiter != null ) limiter.stopScheduler();
            })
            .maximumSize(cacheSize)
            .build();
    }
    

    @PreDestroy
    private void cleanup() {
        log.info("Shutting down rate limiter schedulers...");

        for (Map.Entry<String, RateLimiter> entry : cache.asMap().entrySet()) {
            RateLimiter limiter = entry.getValue();
            limiter.stopScheduler(); // no-op for limiters without a scheduler
        }
    }
}
