package org.coldis.library.service.jms;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * JMS send throttle metrics configuration (only with Micrometer).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
public class JmsSendThrottleMetricsConfiguration {

	/**
	 * Creates the JMS send throttle metrics: recorded in the meter registry if
	 * there is one, none otherwise.
	 *
	 * @param  meterRegistryProvider Meter registry provider.
	 * @return                       The JMS send throttle metrics.
	 */
	@Bean
	@ConditionalOnMissingBean
	public JmsSendThrottleMetrics jmsSendThrottleMetrics(
			final ObjectProvider<MeterRegistry> meterRegistryProvider) {
		final MeterRegistry availableMeterRegistry = meterRegistryProvider.getIfAvailable();
		return (availableMeterRegistry == null ? JmsSendThrottleMetrics.NONE : new MicrometerJmsSendThrottleMetrics(availableMeterRegistry));
	}

}
