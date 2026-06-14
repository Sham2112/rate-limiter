package com.shazam.model;

import java.util.List;

public class Route {
    private String id;
    private String upstream;
    private List<String> paths;
    private List<String> methods;
    private LimiterConfig limiter;
    
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getUpstream() {
        return upstream;
    }
    public void setUpstream(String upstream) {
        this.upstream = upstream;
    }
    public List<String> getPaths() {
        return paths;
    }
    public void setPaths(List<String> paths) {
        this.paths = paths;
    }
    public List<String> getMethods(){
        return methods;
    }
    public void setMethods(List<String> methods){
        this.methods = methods;
    }
    public LimiterConfig getLimiter() {
        return limiter;
    }
    public void setLimiter(LimiterConfig limiter) {
        this.limiter = limiter;
    }
}
