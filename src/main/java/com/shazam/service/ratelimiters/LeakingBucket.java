package com.shazam.service.ratelimiters;

import com.shazam.model.Request;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
Leaking Bucket algo implementaion for rate limiting
Requests are added to a queue and at a fixed interval a certain number of requests are sent to the server
*/
public class LeakingBucket implements RateLimiter {

    private Queue<Request> requestQueue;
    // private long queueSize;
    private long interval;
    private long noOfRequests;
    private ScheduledExecutorService scheduler;

    /**
     * @param queueSize the size of the queue
     * @param interval interval after which noOfRequests are sent to the server (in milliseconds)
     * @param noOfRequests no of requests to send to the server
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
    public boolean handleRequest(Request request){
        return requestQueue.offer(request);
    }

    /**
     * sends noOfRequests requests to the server
     * should be called at the every interval
     */
    public void sendRequests(){
        long n = noOfRequests;
        while (n>0 && requestQueue.size()>0){ 
            Request request = requestQueue.poll();
            n--;
            //TODO: add logic to forward request
        }
    }

    /**
     * Creates a scheduler that calls sendReqeust() at a fixed interval
     */
    public void startRequestsScheduler(){
        if (scheduler != null){
            throw new IllegalStateException("Scheduler has already started");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sendRequests, 0, interval, TimeUnit.MILLISECONDS);
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
