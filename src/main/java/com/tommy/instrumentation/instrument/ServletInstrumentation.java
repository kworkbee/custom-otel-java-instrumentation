package com.tommy.instrumentation.instrument;

import com.tommy.instrumentation.DemoAgent;
import io.opentelemetry.api.metrics.LongCounter;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class ServletInstrumentation {

    public static volatile LongCounter requestCounter;

    public static LongCounter getCounter() {
        if (requestCounter == null) {
            synchronized (ServletInstrumentation.class) {
                if (requestCounter == null) {
                    requestCounter = DemoAgent.getMeter()
                            .counterBuilder("demo.requests")
                            .setDescription("Number of requests")
                            .setUnit("requests")
                            .build();
                }
            }
        }
        return requestCounter;
    }

    public static AgentBuilder register(AgentBuilder agentBuilder) {
        System.out.println("[SERVLET_INSTRUMENTATION] Registering Servlet transforms...");

        // Instrument javax.servlet.Filter (LTS 8, 11)
        agentBuilder = agentBuilder
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("javax.servlet.Filter")))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(JavaxFilterAdvice.class)
                                .on(ElementMatchers.named("doFilter")
                                        .and(ElementMatchers.takesArguments(3)))));

        // Instrument jakarta.servlet.Filter (LTS 17, 21, 25)
        agentBuilder = agentBuilder
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("jakarta.servlet.Filter")))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> builder
                        .visit(Advice.to(JakartaFilterAdvice.class)
                                .on(ElementMatchers.named("doFilter")
                                        .and(ElementMatchers.takesArguments(3)))));

        return agentBuilder;
    }

    public static class JavaxFilterAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Argument(0) Object request) {
            try {
                com.tommy.instrumentation.instrument.MicrometerBridgeInstrumentation.setAppClassLoader(Thread.currentThread().getContextClassLoader());

                if (request != null && (request.getClass().getName().contains("HttpServletRequest") ||
                    request instanceof javax.servlet.http.HttpServletRequest)) {
                    
                    javax.servlet.http.HttpServletRequest req = (javax.servlet.http.HttpServletRequest) request;
                    String uri = req.getRequestURI();
                    
                    if (uri != null && !uri.startsWith("/actuator")) {
                        System.out.println("[SERVLET_INSTRUMENTATION] [Javax] Request URI: " + uri);
                        getCounter().add(1);
                    }
                }
            } catch (Throwable t) {
                System.err.println("[SERVLET_INSTRUMENTATION] Javax advice execution failed: " + t.getMessage());
            }
        }
    }

    public static class JakartaFilterAdvice {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Argument(0) Object request) {
            try {
                com.tommy.instrumentation.instrument.MicrometerBridgeInstrumentation.setAppClassLoader(Thread.currentThread().getContextClassLoader());

                if (request != null && (request.getClass().getName().contains("HttpServletRequest") ||
                    request instanceof jakarta.servlet.http.HttpServletRequest)) {
                    
                    jakarta.servlet.http.HttpServletRequest req = (jakarta.servlet.http.HttpServletRequest) request;
                    String uri = req.getRequestURI();
                    
                    if (uri != null && !uri.startsWith("/actuator")) {
                        System.out.println("[SERVLET_INSTRUMENTATION] [Jakarta] Request URI: " + uri);
                        getCounter().add(1);
                    }
                }
            } catch (Throwable t) {
                System.err.println("[SERVLET_INSTRUMENTATION] Jakarta advice execution failed: " + t.getMessage());
            }
        }
    }
}
