package com.shazam.service.ratelimiters;

import jakarta.servlet.http.HttpServletRequest;

/*
Token Bucket algo implementaion for rate limiting\
Tokens are added at a fixed rate (ex: x tokens ever y nanoseconds) to the bucket
For every request check if there are tokens left in bucket and only allow requests if there is
*/
public class TokenBucket implements RateLimiter {
    private long bucketSize;
    private long interval;
    private long tokensLeft;
    private long lastFilled;
    private long refillSize;

    /**
     * @param bucketSize the size of the bucket
     * @param interval interval after which refillSize no of tokens are to be added to the bucket
     * repeatedly (in milliseconds)
     * @param refillSize no of tokens to add at each interval
     * @throws IllegalArgumentException if any of the input params is less than or equal to 0 
     */
    public TokenBucket(long bucketSize, long interval, long refillSize){
        if (bucketSize <= 0 || interval <= 0 || refillSize <= 0) {
            throw new IllegalArgumentException("bucketSize, interval, and refillSize must be positive");
        }
        this.bucketSize = bucketSize;
        this.interval = interval * 1000000;
        this.refillSize = refillSize;
        tokensLeft = bucketSize;
        lastFilled = System.nanoTime();
    }

    /**
     * Adds required no of tokens to bucket based on time since it was last filled.
     * Bucket can't have more than bucketSize no of tokens
     */
    private void addTokens(){
        long now = System.nanoTime();
        long elapsed = now - lastFilled;
        long tokensToAdd = refillSize * (elapsed / interval);
        if (tokensToAdd > 0){
            // instead of starting fresh from now this will set lastFilled to the last exact token boundary
            lastFilled += (elapsed / interval) * interval;
            tokensLeft += tokensToAdd;
            if (tokensLeft > bucketSize){
                tokensLeft = bucketSize;
            }
        }
    }

    public void forwardRequest(HttpServletRequest request){
        //TODO: add logic to forward requests
    }

    public boolean hasScheduler(){
        return false;
    }

    public void startScheduler(){
        //do nothing as TokenBucket doesn't require a scheduler
    }

    /**
    * @return true if there are tokens left in the bucket and reduce by 1. Return false otherwise
    */
    public synchronized boolean handleRequest(HttpServletRequest request){
        addTokens();
        if (tokensLeft > 0){
            tokensLeft--;
            // forwardRequest(request);
            return true;
        }
        return false;
    }

    public long getTokensLeft(){
        return tokensLeft;
    }
}