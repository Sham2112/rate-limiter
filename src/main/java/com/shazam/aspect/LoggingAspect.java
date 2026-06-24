package com.shazam.aspect;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Cross-cutting observability for forwarded requests: logs and times each request that is
 * admitted past the rate limiter, records a per-route/per-status metric, and translates an
 * upstream/relay failure into a uniform 502 instead of leaking a raw exception.
 *
 * Blocked requests (404 no-route, 429 rate-limited) never reach the controller, so they're
 * logged in {@code RateLimitInterceptor} instead.
 */
@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    private final MeterRegistry metrics;

    public LoggingAspect(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @Around("execution(* com.shazam.controller.ProxyController.relayRequest(..))")
    public Object observeForward(ProceedingJoinPoint pjp) throws Throwable {
        HttpServletRequest request =
            ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString();
        Object routeId = request.getAttribute("routeId");
        String route = (routeId != null) ? routeId.toString() : "unknown";

        long start = System.nanoTime();
        try {
            Object result = pjp.proceed();
            long nanos = System.nanoTime() - start;

            int status = 0, size = 0;
            if (result instanceof ResponseEntity<?> response) {
                status = response.getStatusCode().value();
                if (response.getBody() instanceof byte[] body) {
                    size = body.length;
                }
            }
            log.info("forward method={} path={}{} route={} status={} bytes={} latencyMs={}",
                    method, path, (query != null ? "?" + query : ""), route, status, size, nanos / 1_000_000);
            record(method, route, String.valueOf(status), nanos);
            return result;
        } catch (Throwable t) {
            long nanos = System.nanoTime() - start;
            log.error("forward failed method={} path={} route={} latencyMs={}",
                    method, path, route, nanos / 1_000_000, t);
            record(method, route, "502", nanos);
            byte[] body = ("{\"error\":\"upstream request failed\",\"route\":\"" + route + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }
    }

    private void record(String method, String route, String status, long nanos) {
        Timer.builder("gateway.requests")
                .tag("method", method)
                .tag("route", route)
                .tag("status", status)
                .register(metrics)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
