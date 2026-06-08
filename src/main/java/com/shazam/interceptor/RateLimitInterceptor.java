package com.shazam.interceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import org.springframework.stereotype.Component;
import com.shazam.service.ratelimiters.TokenBucket;
import com.shazam.utils.NetworkUtils;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {


    @Value("${tokenbucket.bucketSize}")
    private long bucketSize;

    @Value("${tokenbucket.interval}")
    private long interval;
    
    @Value("${tokenbucket.refillSize}")
    private long refillSize;

    Map<String, TokenBucket> userBucketMap = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,@NonNull Object handler) throws Exception{
        boolean result = false;
        String ipAddress = NetworkUtils.getClientIp(request);
        if (userBucketMap.containsKey(ipAddress)){
            TokenBucket tokenBucket = userBucketMap.get(ipAddress);
            result = tokenBucket.handleRequest(request);
            System.out.println("[RateLimiter] ip=" + ipAddress + " bucketSize=" + bucketSize + " tokensLeft" + tokenBucket.getTokensLeft());
        } else {
            TokenBucket tokenBucket = new TokenBucket(bucketSize, interval, refillSize);
            userBucketMap.put(ipAddress, tokenBucket);
            result = tokenBucket.handleRequest(request);
            System.out.println("[RateLimiter] initialized ip=" + ipAddress + " bucketSize=" + bucketSize + " tokensLeft" + tokenBucket.getTokensLeft());
        }
        if (!result) {
            response.setStatus(429);
        }
        return result;
    }
}
