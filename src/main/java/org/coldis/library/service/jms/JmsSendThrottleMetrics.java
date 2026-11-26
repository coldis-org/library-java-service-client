package org.coldis.library.service.jms;

/**
 * Metrics of the JMS send throttle (see {@link JmsMessage#getThrottleKey()}).
 * Every method does nothing by default ({@link #NONE});
 * {@link MicrometerJmsSendThrottleMetrics} records them when a meter registry
 * is available.
 */
public interface JmsSendThrottleMetrics {

	/**
	 * Metrics that record nothing.
	 */
	JmsSendThrottleMetrics NONE = new JmsSendThrottleMetrics() {};

	/**
	 * Records a send skipped because a message with the same throttle key was
	 * sent to the destination within the throttle interval.
	 *
	 * @param destination Destination.
	 */
	default void recordSkipped(
			final String destination) {
		// Records nothing.
	}

}
