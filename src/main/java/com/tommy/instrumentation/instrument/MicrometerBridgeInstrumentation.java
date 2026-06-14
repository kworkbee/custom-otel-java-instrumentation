package com.tommy.instrumentation.instrument;

import com.tommy.instrumentation.DemoAgent;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MicrometerBridgeInstrumentation {

    private static volatile ClassLoader appClassLoader;
    private static final Set<Object> activeRegistries = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, Object> registeredMetrics = new ConcurrentHashMap<>();
    private static final Map<String, Double> lastValues = new ConcurrentHashMap<>();

    public static void setAppClassLoader(ClassLoader cl) {
        if (cl != null) {
            String name = cl.getClass().getName();
            if (appClassLoader == null) {
                if (name.contains("LaunchedURLClassLoader") || 
                    name.contains("AppClassLoader") ||
                    name.contains("TomcatEmbeddedWebappClassLoader") ||
                    name.contains("Tomcat")) {
                    appClassLoader = cl;
                    System.out.println("[MICROMETER_POLLER] Application class loader captured successfully: " + name);
                    System.out.flush();
                }
            }
        }
    }

    public static void registerRegistry(Object registry) {
        if (registry != null) {
            activeRegistries.add(registry);
            System.out.println("[MICROMETER_POLLER] Captured active MeterRegistry: " + registry.getClass().getName());
            System.out.flush();
        }
    }

    public static AgentBuilder register(AgentBuilder agentBuilder) {
        System.out.println("[MICROMETER_INSTRUMENTATION] Registering MeterRegistry constructor transforms...");
        System.out.flush();
        
        // Start periodic polling thread
        startPolling();

        // Intercept MeterRegistry constructor to capture all active registries
        return agentBuilder
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("io.micrometer.core.instrument.MeterRegistry")))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(MeterRegistryAdvice.class)
                                .on(ElementMatchers.isConstructor())));
    }

    public static class MeterRegistryAdvice {
        @Advice.OnMethodExit
        public static void exit(@Advice.This Object registry) {
            try {
                com.tommy.instrumentation.instrument.MicrometerBridgeInstrumentation.registerRegistry(registry);
            } catch (Throwable t) {
                // Ignore
            }
        }
    }

    private static void startPolling() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "micrometer-bridge-poller");
            t.setDaemon(true);
            return t;
        });

        // Poll every 5 seconds (matching export interval)
        scheduler.scheduleAtFixedRate(MicrometerBridgeInstrumentation::pollMetrics, 5, 5, TimeUnit.SECONDS);
        System.out.println("[MICROMETER_POLLER] Micrometer polling thread started.");
        System.out.flush();
    }

    private static void pollMetrics() {
        System.out.println("[MICROMETER_POLLER] pollMetrics thread tick started. Active registries count: " + activeRegistries.size());
        System.out.flush();
        try {
            // Collect from all captured registries
            int count = 0;
            for (Object registry : activeRegistries) {
                try {
                    Collection<?> meters = (Collection<?>) registry.getClass().getMethod("getMeters").invoke(registry);
                    if (meters != null) {
                        for (Object meter : meters) {
                            try {
                                if (processMeter(meter)) {
                                    count++;
                                }
                            } catch (Throwable t) {
                                // Ignore individual meter errors
                            }
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[MICROMETER_POLLER] Error querying registry: " + t.getMessage());
                }
            }

            // Fallback: also check global registry if available
            ClassLoader cl = appClassLoader;
            if (cl != null) {
                try {
                    Class<?> metricsClass = cl.loadClass("io.micrometer.core.instrument.Metrics");
                    Object globalRegistry = metricsClass.getField("globalRegistry").get(null);
                    if (globalRegistry != null) {
                        Collection<?> meters = (Collection<?>) globalRegistry.getClass().getMethod("getMeters").invoke(globalRegistry);
                        if (meters != null) {
                            for (Object meter : meters) {
                                try {
                                    if (processMeter(meter)) {
                                        count++;
                                    }
                                } catch (Throwable t) {
                                    // Ignore individual meter errors
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    // Ignore global registry fallback errors
                }
            }

            System.out.println("[MICROMETER_POLLER] pollMetrics tick completed. Successfully updated " + count + " Micrometer metrics.");
            System.out.flush();
        } catch (Throwable t) {
            System.err.println("[MICROMETER_POLLER] Error polling metrics: " + t.getMessage());
            t.printStackTrace();
            System.err.flush();
        }
    }

    private static boolean processMeter(Object meter) throws Exception {
        // meter.getId() -> Meter.Id
        Object id = meter.getClass().getMethod("getId").invoke(meter);
        String name = (String) id.getClass().getMethod("getName").invoke(id);

        if (name == null) {
            return false;
        }

        // Bridge only petclinic specific metrics or custom metrics to avoid spamming
        if (!name.startsWith("petclinic") && !name.contains("owners") && !name.contains("visits")) {
            return false;
        }

        // meter.measure() -> Iterable<Measurement>
        Iterable<?> measurements = (Iterable<?>) meter.getClass().getMethod("measure").invoke(meter);
        if (measurements == null) {
            return false;
        }

        double value = 0;
        boolean foundVal = false;
        for (Object measurement : measurements) {
            // measurement.getValue() -> double
            Number valNum = (Number) measurement.getClass().getMethod("getValue").invoke(measurement);
            if (valNum != null) {
                value = valNum.doubleValue();
                foundVal = true;
                break; // Use the primary measurement value
            }
        }

        if (!foundVal) {
            return false;
        }

        // id.getTags() -> List<Tag>
        List<?> tags = (List<?>) id.getClass().getMethod("getTags").invoke(id);
        AttributesBuilder attrBuilder = Attributes.builder();
        if (tags != null) {
            for (Object tag : tags) {
                String key = (String) tag.getClass().getMethod("getKey").invoke(tag);
                String val = (String) tag.getClass().getMethod("getValue").invoke(tag);
                if (key != null && val != null) {
                    attrBuilder.put(key, val);
                }
            }
        }
        Attributes attributes = attrBuilder.build();

        // Check if the Micrometer meter is a Counter
        boolean isCounter = name.endsWith("count") || name.endsWith("total") || 
                            meter.getClass().getName().contains("Counter");

        updateOtelMetric(name, value, attributes, isCounter);
        return true;
    }

    private static void updateOtelMetric(final String name, double value, final Attributes attributes, boolean isCounter) {
        final String cacheKey = name + "_" + attributes.toString();
        lastValues.put(cacheKey, value);

        if (!registeredMetrics.containsKey(cacheKey)) {
            synchronized (registeredMetrics) {
                if (!registeredMetrics.containsKey(cacheKey)) {
                    if (isCounter) {
                        // Register dynamic counter callback to OTel SDK
                        DemoAgent.getMeter().counterBuilder(name)
                                .setDescription("Bridged Micrometer counter: " + name)
                                .buildWithCallback(observableMeasurement -> {
                                    Double val = lastValues.get(cacheKey);
                                    if (val != null) {
                                        observableMeasurement.record(val.longValue(), attributes);
                                    }
                                });
                        System.out.println("[MICROMETER_POLLER] Successfully registered OTel Counter for: " + name + " with attributes " + attributes);
                        System.out.flush();
                    } else {
                        // Register dynamic gauge callback to OTel SDK
                        DemoAgent.getMeter().gaugeBuilder(name)
                                .setDescription("Bridged Micrometer gauge: " + name)
                                .buildWithCallback(observableMeasurement -> {
                                    Double val = lastValues.get(cacheKey);
                                    if (val != null) {
                                        observableMeasurement.record(val, attributes);
                                    }
                                });
                        System.out.println("[MICROMETER_POLLER] Successfully registered OTel Gauge for: " + name + " with attributes " + attributes);
                        System.out.flush();
                    }
                    registeredMetrics.put(cacheKey, new Object());
                }
            }
        }
    }
}
