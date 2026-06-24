package com.shazam.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * Exposes the gateway's request metrics collected by {@code LoggingAspect}.
 */
@RestController
public class MetricsController {

    private final MeterRegistry registry;

    public MetricsController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/gateway/metrics")
    public List<Map<String, Object>> metrics() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Timer timer : registry.find("gateway.requests").timers()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tags", timer.getId().getTags().stream()
                    .collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
            row.put("count", timer.count());
            row.put("totalTimeMs", timer.totalTime(TimeUnit.MILLISECONDS));
            row.put("maxMs", timer.max(TimeUnit.MILLISECONDS));
            out.add(row);
        }
        return out;
    }
}
