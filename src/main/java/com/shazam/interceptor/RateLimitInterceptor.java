package com.shazam.interceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

import com.shazam.service.ratelimiters.TokenBucket;
import com.shazam.service.ratelimiters.FixedWindow;
import com.shazam.service.ratelimiters.LeakingBucket;
import com.shazam.service.ratelimiters.RateLimiter;
import com.shazam.service.ratelimiters.SlidingWindowCounter;
import com.shazam.service.ratelimiters.SlidingWindowLog;
import com.shazam.utils.NetworkUtils;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Value("${scheduler.type:token_bucket}")
    public String schedulerType;

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
    
    // Map<String, TokenBucket> userBucketMap = new ConcurrentHashMap<>();
    Map<String, RateLimiter> userLimiterMap = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,@NonNull Object handler) throws Exception{
        boolean result = false;
        String ipAddress = NetworkUtils.getClientIp(request);
        if (userLimiterMap.containsKey(ipAddress)){
            RateLimiter limiter = userLimiterMap.get(ipAddress);
            result = limiter.handleRequest(request);
        } else {
            RateLimiter limiter = createLimiter();
            if (limiter.hasScheduler()){
                limiter.startScheduler();
            }
            userLimiterMap.put(ipAddress, limiter);
            result = limiter.handleRequest(request);
        }
        if (!result) {
            response.setStatus(429);
        }
        return result;
    }

    public RateLimiter createLimiter() throws IllegalArgumentException{
        switch (schedulerType) {
            case "fixed_window":
                return new FixedWindow(fixedWindowInterval, fixedWindowNoOfRequests);
            case "token_bucket":
                return new TokenBucket(tokenBucketSize, tokenBucketInterval, tokenBucketRefillSize);
            case "leaking_bucket":
                return new LeakingBucket(leakingBucketQueueSize, leakingBucketInterval, leakingBucketNoOfRequests);
            case "sliding_window_counter":
                return new SlidingWindowCounter(slidingWindowCounterInterval, slidingWindowCounterNoOfRequests);
            case "sliding_window_log":
                return new SlidingWindowLog(slidingWindowLogInterval, slidingWindowLogNoOfRequests);
            default:
                throw new IllegalArgumentException("valid scheduler type not provided");
        }
    }
}
