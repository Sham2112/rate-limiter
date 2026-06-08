package com.shazam.controller;

import com.shazam.model.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {
    
    @GetMapping("/api/info")
    public String infoApi(HttpServletRequest request){
        String clientIp = request.getRemoteAddr(); 
        String userAgent = request.getHeader("User-Agent");
        System.out.println("IP: " + clientIp + ", Browser: " + userAgent);
        return "0.0.1";
    }
    
    @PostMapping
    public String testPost(@RequestBody User user){
        System.out.println("test user");
        return "Added";
    }
}
