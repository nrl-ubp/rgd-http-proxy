package com.ubp.rgd.proxy.services;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code ubp_proxy_forward} per-(method, uri) min/max/avg duration metrics.
 */
class ProxyForwardMetricsTest {

    private ProxyService proxyService;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        proxyService = new ProxyService();
        registry = new SimpleMeterRegistry();
        proxyService.metricsRegistry = registry;
    }

    private double gauge(String method, String uri, String stat) {
        Gauge g = registry.find("ubp_proxy_forward")
                .tags(Tags.of("method", method, "uri", uri, "stat", stat))
                .gauge();
        assertNotNull(g, "Expected gauge ubp_proxy_forward{method=" + method + ", uri=" + uri
                + ", stat=" + stat + "}");
        return g.value();
    }

    @Test
    void testDurationStatsComputesMinMaxAvg() {
        ProxyService.DurationStats stats = new ProxyService.DurationStats();
        stats.record(10);
        stats.record(20);
        stats.record(30);

        assertEquals(10.0, stats.getMin());
        assertEquals(30.0, stats.getMax());
        assertEquals(20.0, stats.getAvg());
    }

    @Test
    void testEmptyDurationStatsIsZero() {
        ProxyService.DurationStats stats = new ProxyService.DurationStats();
        assertEquals(0.0, stats.getMin());
        assertEquals(0.0, stats.getMax());
        assertEquals(0.0, stats.getAvg());
    }

    @Test
    void testGaugesRegisteredPerMethodAndUri() {
        proxyService.recordForwardDuration("GET", "/persons", 10);
        proxyService.recordForwardDuration("GET", "/persons", 30);
        proxyService.recordForwardDuration("POST", "/persons", 100);

        // GET /persons -> min 10, max 30, avg 20
        assertEquals(10.0, gauge("GET", "/persons", "min"));
        assertEquals(30.0, gauge("GET", "/persons", "max"));
        assertEquals(20.0, gauge("GET", "/persons", "avg"));

        // POST /persons -> single sample 100
        assertEquals(100.0, gauge("POST", "/persons", "min"));
        assertEquals(100.0, gauge("POST", "/persons", "max"));
        assertEquals(100.0, gauge("POST", "/persons", "avg"));
    }

    @Test
    void testGaugesRegisteredOncePerKeyAndKeepUpdating() {
        proxyService.recordForwardDuration("GET", "/accounts", 50);
        proxyService.recordForwardDuration("GET", "/accounts", 150);

        // Exactly three series for this key (min/max/avg), not re-registered on the second call.
        long series = registry.find("ubp_proxy_forward")
                .tags(Tags.of("method", "GET", "uri", "/accounts"))
                .gauges().size();
        assertEquals(3, series);

        assertEquals(50.0, gauge("GET", "/accounts", "min"));
        assertEquals(150.0, gauge("GET", "/accounts", "max"));
        assertEquals(100.0, gauge("GET", "/accounts", "avg"));
    }
}
