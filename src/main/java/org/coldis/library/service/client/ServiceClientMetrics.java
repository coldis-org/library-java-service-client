package org.coldis.library.service.client;

/**
 * Metrics of the service client failover and balancing, per
 * {@link ServiceClientTarget}. Every method does nothing by default
 * ({@link #NONE}); {@link MicrometerServiceClientMetrics} records them when a
 * meter registry is available.
 */
public interface ServiceClientMetrics {

	/**
	 * Metrics that record nothing.
	 */
	ServiceClientMetrics NONE = new ServiceClientMetrics() {};

	/**
	 * Records an attempt.
	 *
	 * @param target  Target.
	 * @param outcome Outcome ({@code success}, or the kind of failure:
	 *                    {@code not-connected}, {@code status},
	 *                    {@code read-timeout}, {@code dropped} or
	 *                    {@code other}).
	 */
	default void recordAttempt(
			final String target,
			final String outcome) {
		// Records nothing.
	}

	/**
	 * Records the retry of a failed attempt on another endpoint.
	 *
	 * @param target Target.
	 * @param reason Kind of the failure retried ({@code not-connected},
	 *                   {@code status}, {@code read-timeout} or
	 *                   {@code dropped}).
	 */
	default void recordRetry(
			final String target,
			final String reason) {
		// Records nothing.
	}

	/**
	 * Records an endpoint put in its cooldown.
	 *
	 * @param target Target.
	 */
	default void recordCooldown(
			final String target) {
		// Records nothing.
	}

	/**
	 * Records a request started (in flight until {@link #recordRequestEnded}).
	 *
	 * @param target Target.
	 */
	default void recordRequestStarted(
			final String target) {
		// Records nothing.
	}

	/**
	 * Records a request ended.
	 *
	 * @param target Target.
	 */
	default void recordRequestEnded(
			final String target) {
		// Records nothing.
	}

}
