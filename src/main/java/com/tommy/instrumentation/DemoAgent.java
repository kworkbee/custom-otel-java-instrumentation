package com.tommy.instrumentation;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import net.bytebuddy.agent.builder.AgentBuilder;
import com.tommy.instrumentation.instrument.ServletInstrumentation;
import com.tommy.instrumentation.instrument.MicrometerBridgeInstrumentation;

public class DemoAgent {

    private static volatile OpenTelemetry openTelemetry;
    private static volatile Meter meter;

    public static void premain(String agentArgs, Instrumentation inst) {
        System.setProperty("com.tommy.instrumentation.shaded.bytebuddy.experimental", "true");
        System.out.println("[DEMO_AGENT] premain started.");

        // Initialize OTel SDK
        initializeOpenTelemetry();

        // Install ByteBuddy instrumentation agent
        installAgent(inst);
    }

    private static void initializeOpenTelemetry() {
        String endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = "http://localhost:4318";
        }
        if (!endpoint.endsWith("/v1/metrics")) {
            if (endpoint.endsWith("/")) {
                endpoint += "v1/metrics";
            } else {
                endpoint += "/v1/metrics";
            }
        }

        String intervalStr = System.getenv("OTEL_METRIC_EXPORT_INTERVAL");
        long intervalMs = 5000; // default 5s
        if (intervalStr != null && !intervalStr.isEmpty()) {
            try {
                intervalMs = Long.parseLong(intervalStr);
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        System.out.println("[DEMO_AGENT] Initializing OTel SDK. Endpoint: " + endpoint + ", Interval: " + intervalMs + "ms");

        OtlpHttpMetricExporter metricExporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(endpoint)
                .build();

        PeriodicMetricReader metricReader = PeriodicMetricReader.builder(metricExporter)
                .setInterval(Duration.ofMillis(intervalMs))
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build();

        openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build();

        meter = openTelemetry.getMeter("com.tommy.instrumentation.agent");
        System.out.println("[DEMO_AGENT] OTel SDK initialized successfully.");
    }

    public static OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    public static Meter getMeter() {
        return meter;
    }

    private static void installAgent(Instrumentation inst) {
        System.out.println("[DEMO_AGENT] Installing ByteBuddy agent...");
        
        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable throwable) {
                        System.err.println("[BYTEBUDDY_ERROR] Error transforming " + typeName + ": " + throwable.getMessage());
                        throwable.printStackTrace();
                        System.err.flush();
                    }
                });

        // Connect Servlet & Micrometer instrumentation modules
        agentBuilder = ServletInstrumentation.register(agentBuilder);
        agentBuilder = MicrometerBridgeInstrumentation.register(agentBuilder);

        agentBuilder.installOn(inst);
        System.out.println("[DEMO_AGENT] ByteBuddy agent installed successfully.");
    }
}
