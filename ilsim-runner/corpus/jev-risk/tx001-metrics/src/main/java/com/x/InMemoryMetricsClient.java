package com.x;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InMemoryMetricsClient {
    private final MeterRegistry registry;

    public void increment(String name) {
        registry.counter(name).increment();
    }
}
