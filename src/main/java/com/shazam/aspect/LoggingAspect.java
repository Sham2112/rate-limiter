package com.shazam.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Logs every request that is admitted past the rate limiter and forwarded to an upstream.
 * Blocked requests (404 no-route, 429 rate-limited) never reach the controller, so they're
 * logged in {@code RateLimitInterceptor} instead.
 */
@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    @Around("execution(* com.shazam.controller.ProxyController.relayRequest(..))")
    public Object logForwardedRequest(ProceedingJoinPoint pjp) throws Throwable {
        HttpServletRequest request =
            ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString();
        Object routeId = request.getAttribute("routeId"); // stashed by RateLimitInterceptor

        long start = System.nanoTime();
        try {
            Object result = pjp.proceed();
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (result instanceof ResponseEntity<?> response) {
                int size = (response.getBody() instanceof byte[] b) ? b.length : 0;
                log.info("forward method={} path={}{} route={} status={} bytes={} latencyMs={}",
                        method, path, (query != null ? "?" + query : ""), routeId,
                        response.getStatusCode().value(), size, ms);
            }
            return result;
        } catch (Throwable t) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.error("forward failed method={} path={} route={} latencyMs={}",
                    method, path, routeId, ms, t); // trailing throwable → stack trace
            throw t;
        }
    }
}
