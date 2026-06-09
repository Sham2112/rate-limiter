package com.shazam.service.ratelimiters;

import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpServletRequest;

/*
Leaking Bucket algo implementaion for rate limiting (meter / admission-control model).

Each admitted request raises the bucket's fill level (offer into the queue). A scheduled
"leak" drains noOfRequests entries every interval, freeing that much capacity. If a request
arrives when the bucket is full it is rejected. The request itself is served immediately by
the normal request pipeline — the queue is only the accounting that decides admission, not a
buffer of work to replay later.
*/
public class LeakingBucket implements RateLimiter {

    private Queue<HttpServletRequest> requestQueue;
    private long interval;
    private long noOfRequests;
    private ScheduledExecutorService scheduler;

    /**
     * @param queueSize the size of the queue
     * @param interval interval after which noOfRequests are freed from the queue
     * @param noOfRequests no of requests to free up in the queue
     * @throws IllegalArgumentException if any of the input params is less than or equal to 0 
     */
    public LeakingBucket(int queueSize, int interval, int noOfRequests){
        if (queueSize <=0 || interval <=0 || noOfRequests <= 0){
            throw new IllegalArgumentException("Neither of queueSize, interval, noOfRequests can be equal to or lesser than 0");
        }
        requestQueue = new LinkedBlockingQueue<>(queueSize);
        this.interval = interval;
        this.noOfRequests = noOfRequests;
    }

    /**
     * 
     * @param request request received from client
     * @return true if request was added to the queue, false if queue was already full
     */
    public boolean handleRequest(HttpServletRequest request){
        return requestQueue.offer(request);
    }

    // Frees up requests to be added to the queue
    public void leak(){
        long n = noOfRequests;
        while (n > 0 && requestQueue.poll() != null){
            n--;
        }
    }

    public boolean hasScheduler(){
        return true;
    }

    /**
     * Creates a scheduler that calls leak() at a fixed interval
     */
    public void startScheduler(){
        if (scheduler != null){
            throw new IllegalStateException("Scheduler has already started");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::leak, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the scheduler
     */
    public void stop(){
        if (scheduler != null){
            scheduler.shutdown();
        }
    }
}
