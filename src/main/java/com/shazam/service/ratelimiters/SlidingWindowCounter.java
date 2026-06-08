package com.shazam.service.ratelimiters;

import com.shazam.model.Request;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SlidingWindowCounter implements RateLimiter {
    
    private final long interval;
    private final int noOfRequests;
    private int prevCounter;
    private int currCounter;
    private ScheduledExecutorService scheduler;
    private long windowStart = System.currentTimeMillis();

    /**
     * @param interval time window in milliseconds
     * @param noOfRequests no of requests allowed in an interval
     */
    public SlidingWindowCounter(int interval, int noOfRequests){
        if (interval <= 0 || noOfRequests <=0){
            throw new IllegalArgumentException("Both interval and noOfReqeusts have to be greater than 0");
        }
        this.interval = interval;
        this.noOfRequests = noOfRequests;
        this.prevCounter = 0;
        this.currCounter = 0;
    }

    public synchronized boolean handleRequest(Request request){
        double elapsed = (System.currentTimeMillis() - windowStart) / (double) interval;
        double weight = Math.max(1.0 - elapsed, 0.0);
        double estimate = (prevCounter * weight) + currCounter;
        if (estimate >= noOfRequests){
            return false;
        }
        currCounter++;
        forwardRequest(request);
        return true;
    }

    public void startScheduler(){
        if (scheduler != null){
            throw new IllegalStateException("Scheduler has already started");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::updateCounters, interval, interval, TimeUnit.MILLISECONDS);
    }

    public synchronized void updateCounters(){
        this.prevCounter = currCounter;
        this.currCounter = 0;
        windowStart = System.currentTimeMillis();
    }

    public void stopScheduler(){
        if (scheduler != null){
            scheduler.shutdown();
        }
    }

    private void forwardRequest(Request request){
        //Logic to forward requests
    }



}
