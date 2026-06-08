package com.shazam.service.ratelimiters;
import com.shazam.model.Request;

public interface RateLimiter {
    
    public boolean handleRequest(Request request);

}
