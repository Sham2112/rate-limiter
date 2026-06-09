package com.shazam.service.ratelimiters;
// import com.shazam.model.Request;

import jakarta.servlet.http.HttpServletRequest;

public interface RateLimiter {
    
    public boolean handleRequest(HttpServletRequest request);

    public boolean hasScheduler();

    public void startScheduler();

    public void stopScheduler();
}
