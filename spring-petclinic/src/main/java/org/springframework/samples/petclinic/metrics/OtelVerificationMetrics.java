/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.samples.petclinic.metrics;

import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.context.annotation.Configuration;

/**
 * Micrometer custom metrics for verifying that the OTel Javaagent correctly bridges
 * application-defined Micrometer metrics to Prometheus via OTLP.
 *
 * <p>
 * Metrics defined here:
 * <ul>
 * <li>{@code petclinic.owners.search.count} — Counter for owner search operations</li>
 * <li>{@code petclinic.visits.created.count} — Counter for visit creation operations</li>
 * <li>{@code petclinic.api.response.time} — Timer for API response duration</li>
 * <li>{@code petclinic.active.sessions} — Gauge for currently active sessions</li>
 * </ul>
 *
 * @author Custom OTel Instrumentation Project
 */
@Configuration
public class OtelVerificationMetrics {

	private final Counter ownerSearchCounter;

	private final Counter visitCreatedCounter;

	private final Timer apiResponseTimer;

	private final AtomicInteger activeSessions = new AtomicInteger(0);

	public OtelVerificationMetrics(MeterRegistry meterRegistry) {
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

	public Counter getOwnerSearchCounter() {
		return ownerSearchCounter;
	}

	public Counter getVisitCreatedCounter() {
		return visitCreatedCounter;
	}

	public Timer getApiResponseTimer() {
		return apiResponseTimer;
	}

	public AtomicInteger getActiveSessions() {
		return activeSessions;
	}

}
