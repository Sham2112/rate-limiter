package com.shazam.model;

import com.shazam.model.types.SchedulerTypes;

public class LimiterConfig {

    private SchedulerTypes type;
    private int bucketSize;
    private int interval;
    private int refillSize;
    private int noOfRequests;

    public SchedulerTypes getType() {
        return type;
    }
    public void setType(SchedulerTypes type) {
        this.type = type;
    }
    public int getBucketSize() {
        return bucketSize;
    }
    public void setBucketSize(int bucketSize) {
        this.bucketSize = bucketSize;
    }
    public int getInterval() {
        return interval;
    }
    public void setInterval(int interval) {
        this.interval = interval;
    }
    public int getRefillSize() {
        return refillSize;
    }
    public void setRefillSize(int refillSize) {
        this.refillSize = refillSize;
    }
    public int getNoOfRequests() {
        return noOfRequests;
    }
    public void setNoOfRequests(int noOfRequests) {
        this.noOfRequests = noOfRequests;
    }
    
}
