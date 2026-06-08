package com.shazam.utils;

import jakarta.servlet.http.HttpServletRequest;

public class NetworkUtils {

    private static final String[] PROXY_HEADERS = {
        "X-Forwarded-For",
        "Proxy-Client-IP",
        "WL-Proxy-Client-IP",
        "HTTP_X_FORWARDED_FOR",
        "HTTP_X_FORWARDED",
        "HTTP_X_CLUSTER_CLIENT_IP",
        "HTTP_CLIENT_IP",
        "FORWARDED"
    };

    public static String getClientIp(HttpServletRequest request) {
        for (String header : PROXY_HEADERS) {
            String ipAddress = request.getHeader(header);
            if (ipAddress != null && !ipAddress.isEmpty() && !"unknown".equalsIgnoreCase(ipAddress)) {
                // X-Forwarded-For can contain a comma-separated list of proxy IPs. 
                // The first element is always the original client IP.
                return ipAddress.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
