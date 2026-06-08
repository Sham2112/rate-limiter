package com.shazam.service.ratelimiters;

import com.shazam.model.Request;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;

public class FixedWindow implements RateLimiter {
    
    private int interval;
    private int noOfRequests;
    private final AtomicInteger counter = new AtomicInteger(0);
    private ScheduledExecutorService scheduler;

    public FixedWindow(int interval, int noOfRequests){
        if (interval <=0 || noOfRequests <=0){
            throw new IllegalArgumentException("Both interval and noOfRequests must be greater than 0");
        }
        this.interval = interval;
        this.noOfRequests = noOfRequests;
    }

    public void resetCounter(){
        this.counter.set(0);
    }

    public boolean handleRequest(Request request){
        int current;
        do {
            current = counter.get();
            if (current >= noOfRequests) return false;
        } while (!counter.compareAndSet(current, current+1));
        forwardRequest(request);
        return true;
    }


    private void forwardRequest(Request request){
        //LOGIC TO FORWARD REQUESTS
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
        scheduler.scheduleAtFixedRate(this::resetCounter, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void stop(){
        if (scheduler != null){
            scheduler.shutdown();
        }
    }


}
