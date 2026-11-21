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
	 * Records an operation that failed and was not retried on another endpoint
	 * because its time budget was spent.
	 *
	 * @param target Target.
	 * @param reason Kind of the last failure ({@code not-connected},
	 *                   {@code status}, {@code read-timeout} or
	 *                   {@code dropped}).
	 */
	default void recordAttemptsStoppedByTimeLimit(
			final String target,
			final String reason) {
		// Records nothing.
	}

	/**
	 * Records a call stopped because the deadline of the request it was made for
	 * was spent (see {@link ServiceClientRequestDeadline}).
	 *
	 * @param target Target.
	 * @param stage  When it was stopped: {@code call} (before its first attempt,
	 *                   so with no request) or {@code retry} (after a failed
	 *                   attempt, instead of trying another endpoint).
	 */
	default void recordDeadlineExpired(
			final String target,
			final String stage) {
		// Records nothing.
	}

	/**
	 * Records a call that went to its target's last good endpoint list (its list
	 * was empty).
	 *
	 * @param target Target.
	 */
	default void recordCallOnLastEndpoints(
			final String target) {
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

	/**
	 * Registers the meters of the concurrency limit of a target (read at scrape
	 * time from the limit; the limiter never reads them). Called once per target,
	 * when its limit is created.
	 *
	 * @param limit View of the limit of the target.
	 */
	default void registerConcurrencyLimit(
			final ServiceClientConcurrencyLimitView limit) {
		// Records nothing.
	}

}
