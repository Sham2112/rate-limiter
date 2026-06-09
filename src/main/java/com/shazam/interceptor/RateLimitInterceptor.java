package com.shazam.interceptor;

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
import com.shazam.model.types.SchedulerTypes;
import com.shazam.utils.NetworkUtils;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Value("${scheduler.type:token_bucket}")
    private SchedulerTypes schedulerType;

    @Value("${cache.size:1000}")
    private int cacheSize;

    @Value("${cache.expireAfter:10}")
    private int expireAfter;

    @Value("${scheduler.retryAfter}")
    private String retryAfter;

    @Value("${tokenbucket.bucketSize:100}")
    private long tokenBucketSize;

    @Value("${tokenbucket.interval:1000}")
    private long tokenBucketInterval;

    @Value("${tokenbucket.refillSize:100}")
    private long tokenBucketRefillSize;

    @Value("${fixedWindow.interval:1000}")
    private int fixedWindowInterval;

    @Value("${fixedWindow.noOfRequests:100}")
    private int fixedWindowNoOfRequests;

    @Value("${leakingBucket.queueSize:100}")
    private int leakingBucketQueueSize;

    @Value("${leakingBucket.interval:1000}")
    private int leakingBucketInterval;

    @Value("${leakingBucket.noOfRequests:100}")
    private int leakingBucketNoOfRequests;

    @Value("${slidingWindowCounter.interval:1000}")
    private int slidingWindowCounterInterval;

    @Value("${slidingWindowCounter.noOfRequests:100}")
    private int slidingWindowCounterNoOfRequests;

    @Value("${slidingWindowLog.interval:1000}")
    private int slidingWindowLogInterval;

    @Value("${slidingWindowLog.noOfRequests:100}")
    private int slidingWindowLogNoOfRequests;

    Cache <String, RateLimiter> cache;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,@NonNull Object handler) throws Exception{
        String ipAddress = NetworkUtils.getClientIp(request);
        
        RateLimiter limiter = cache.get(ipAddress, i -> {
            System.out.println("[Cache] creating limiter for ip=" + i);
            RateLimiter l = createLimiter();
            if (l.hasScheduler()){
                l.startScheduler();
            }
            return l;
        });

        boolean result = limiter.handleRequest(request);
        if (!result) {
            response.setStatus(429);
            response.setHeader("Retry-After", retryAfter);
        }
        return result;
    }

    private RateLimiter createLimiter() throws IllegalArgumentException{
        switch (schedulerType) {
            case SchedulerTypes.fixed_window:
                return new FixedWindow(fixedWindowInterval, fixedWindowNoOfRequests);
            case SchedulerTypes.token_bucket:
                return new TokenBucket(tokenBucketSize, tokenBucketInterval, tokenBucketRefillSize);
            case SchedulerTypes.leaking_bucket:
                return new LeakingBucket(leakingBucketQueueSize, leakingBucketInterval, leakingBucketNoOfRequests);
            case SchedulerTypes.sliding_window_counter:
                return new SlidingWindowCounter(slidingWindowCounterInterval, slidingWindowCounterNoOfRequests);
            case SchedulerTypes.sliding_window_log:
                return new SlidingWindowLog(slidingWindowLogInterval, slidingWindowLogNoOfRequests);
            default:
                throw new IllegalArgumentException("valid scheduler type not provided");
        }
    }

    @PostConstruct
    private void createCache(){
        this.cache = Caffeine.newBuilder()
            .expireAfterAccess(expireAfter, TimeUnit.MINUTES)
            .scheduler(Scheduler.systemScheduler()) 
            .evictionListener((String ip, RateLimiter limiter, RemovalCause cause) -> {
                System.out.println("[Cache] evicting limiter for ip=" + ip + " cause=" + cause);
                if (limiter != null ) limiter.stopScheduler();
            })
            .maximumSize(cacheSize)
            .build();
    }
    

    @PreDestroy
    private void cleanup() {
        System.out.println("Spring is stopping! Shutting down rate limiter schedulers...");

        for (Map.Entry<String, RateLimiter> entry : cache.asMap().entrySet()) {
            RateLimiter limiter = entry.getValue();
            limiter.stopScheduler(); // no-op for limiters without a scheduler
        }
    }
}
