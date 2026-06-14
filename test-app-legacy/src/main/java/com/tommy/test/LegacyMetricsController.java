package com.tommy.test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller with Micrometer custom metrics for verifying that
 * OTel Javaagent correctly bridges application-defined Micrometer metrics.
 *
 * <p>Provides the same metric names as the modern petclinic version
 * so the verification script can use identical queries for all JDK versions.
 */
@RestController
public class LegacyMetricsController {

    private final Counter ownerSearchCounter;
    private final Counter visitCreatedCounter;
    private final Timer apiResponseTimer;
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    public LegacyMetricsController(MeterRegistry meterRegistry) {
        this.ownerSearchCounter = Counter.builder("petclinic.owners.search.count")
                .description("Number of owner search operations")
                .tag("application", "petclinic")
                .register(meterRegistry);

        this.visitCreatedCounter = Counter.builder("petclinic.visits.created.count")
                .description("Number of visit creation operations")
                .tag("application", "petclinic")
                .register(meterRegistry);

        this.apiResponseTimer = Timer.builder("petclinic.api.response.time")
                .description("API response time")
                .tag("application", "petclinic")
                .register(meterRegistry);

        Gauge.builder("petclinic.active.sessions", activeSessions, AtomicInteger::get)
                .description("Number of currently active sessions")
                .tag("application", "petclinic")
                .register(meterRegistry);
    }

    /**
     * Main page — simulates traffic for javaagent common metrics.
     */
    @GetMapping("/")
    public Map<String, String> home() {
        Map<String, String> result = new HashMap<>();
        result.put("status", "ok");
        result.put("application", "test-app-legacy");
        return result;
    }

    /**
     * Simulates owner search — triggers ownerSearchCounter and apiResponseTimer.
     */
    @GetMapping("/owners")
    public Map<String, Object> searchOwners(@RequestParam(defaultValue = "") String lastName) {
        ownerSearchCounter.increment();
        activeSessions.incrementAndGet();

        try {
            return apiResponseTimer.record(() -> {
                Map<String, Object> result = new HashMap<>();
                result.put("query", lastName);
                result.put("results", 0);
                result.put("message", "Owner search executed (legacy test app)");
                return result;
            });
        } finally {
            activeSessions.decrementAndGet();
        }
    }

    /**
     * Simulates owner find page.
     */
    @GetMapping("/owners/find")
    public Map<String, String> findOwners() {
        Map<String, String> result = new HashMap<>();
        result.put("page", "findOwners");
        return result;
    }

    /**
     * Simulates vet listing — triggers general HTTP traffic.
     */
    @GetMapping("/vets.html")
    public Map<String, String> vets() {
        Map<String, String> result = new HashMap<>();
        result.put("vets", "[]");
        return result;
    }

    /**
     * Simulates visit creation — triggers visitCreatedCounter.
     */
    @GetMapping("/visits/create")
    public Map<String, String> createVisit() {
        visitCreatedCounter.increment();
        Map<String, String> result = new HashMap<>();
        result.put("status", "visit_created");
        return result;
    }
}
