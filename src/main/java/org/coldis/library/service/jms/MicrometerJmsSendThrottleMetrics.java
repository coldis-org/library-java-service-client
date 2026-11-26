package org.coldis.library.service.jms;

import java.util.Objects;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Metrics of the JMS send throttle, recorded in a meter registry:
 * <ul>
 * <li>{@value #SKIPPED} (counter; tag {@code destination}).</li>
 * </ul>
 */
public class MicrometerJmsSendThrottleMetrics implements JmsSendThrottleMetrics {

	/**
	 * Skipped sends counter.
	 */
	public static final String SKIPPED = "jms.throttle.skipped";

	/**
	 * Meter registry.
	 */
	private final MeterRegistry meterRegistry;

	/**
	 * Creates the metrics.
	 *
	 * @param meterRegistry Meter registry.
	 */
	public MicrometerJmsSendThrottleMetrics(final MeterRegistry meterRegistry) {
		this.meterRegistry = Objects.requireNonNull(meterRegistry);
	}

	/**
	 * @see org.coldis.library.service.jms.JmsSendThrottleMetrics#recordSkipped(java.lang.String)
	 */
	@Override
	public void recordSkipped(
			final String destination) {
		Counter.builder(MicrometerJmsSendThrottleMetrics.SKIPPED).description("JMS sends skipped by the send throttle.").tag("destination", destination)
				.register(this.meterRegistry).increment();
	}

}
