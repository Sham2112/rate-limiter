package com.shazam.service.ratelimiters;

import com.shazam.model.Request;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;

public class SlidingWindowLog implements RateLimiter {
    
    private long interval;
    private Queue<Request> requestQueue;

    /**
     * @param interval window length in milliseconds
     * @param noOfRequests no of requests that should be allowed in a window
     */
    public SlidingWindowLog(int interval, int noOfRequests){
        if (interval <=0 || noOfRequests <=0){
            throw new IllegalArgumentException("Both interval and noOfRequests must be greater than 0");
        }
        this.interval = interval;
        requestQueue = new LinkedBlockingDeque<>(noOfRequests);
    }

    // forwards requests
    private void forwardRequest(Request request){
        //logic to forward reqeusts
    }

    /**
     * @param request - request to either allow through or reject
     * @return true if no of requests received in the last interval milliseconds is less than noOfRequests, false otherwise
     */
    public synchronized boolean handleRequest(Request request){
        discardOldRequests();
        if (requestQueue.offer(request)){
            forwardRequest(request);
            return true;
        }
        return false;
    }

    /*
        discards requests from the queue if their timestamp is before the interval
    */
    private void discardOldRequests(){
        Instant windowStart = Instant.now().minusMillis(interval);
        while(true){
            Request request = requestQueue.peek();
            if (request == null || request.time.isAfter(windowStart)){
                break;
            }
            requestQueue.poll();
        }
    }
}
