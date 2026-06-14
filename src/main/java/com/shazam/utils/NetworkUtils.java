package com.shazam.utils;

import com.shazam.model.Route;
import com.shazam.model.RouteConfigs;
import java.util.List;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;
import jakarta.servlet.http.HttpServletRequest;

public class NetworkUtils {

    private static final PathPatternParser parser = new PathPatternParser();

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

    public static Route selectRoute(HttpServletRequest request, RouteConfigs routeConfigs){
        String requestURI = request.getRequestURI();
        for(Route route: routeConfigs.getRoutes()){
            List<String> paths = route.getPaths();
            for(String path: paths){
                if (parser.parse(path).matches(PathContainer.parsePath(requestURI))){
                    return route;
                }
            }
        }
        return null;
    }
}
