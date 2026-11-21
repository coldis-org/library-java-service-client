package org.coldis.library.service.client;

/**
 * Read-only view of the concurrency limit of a target (see
 * {@link ServiceClientConcurrencyLimiter}), for the metrics: read at scrape
 * time, never written. Its values are best-effort readings of a limit other
 * threads keep changing.
 */
public interface ServiceClientConcurrencyLimitView {

	/**
	 * Gets the target.
	 *
	 * @return The target.
	 */
	String getTarget();

	/**
	 * Gets the current limit.
	 *
	 * @return The current limit.
	 */
	int getLimit();

	/**
	 * Gets the operations in flight.
	 *
	 * @return The operations in flight.
	 */
	int getInFlight();

	/**
	 * Gets the peak of operations in flight over the last 5 minutes.
	 *
	 * @return The peak of operations in flight over the last 5 minutes.
	 */
	int getInFlightPeak();

	/**
	 * Gets the operations over the limit while overloaded, not rejected.
	 *
	 * @return The operations over the limit while overloaded.
	 */
	long getOverLimitCalls();

	/**
	 * Gets the operations rejected.
	 *
	 * @return The operations rejected.
	 */
	long getRejectedCalls();

	/**
	 * Gets the time overloaded.
	 *
	 * @return The time overloaded (seconds).
	 */
	double getOverloadedSeconds();

	/**
	 * Gets the baseline latency.
	 *
	 * @return The baseline latency (seconds; zero before the first window).
	 */
	double getBaselineLatencySeconds();

	/**
	 * Gets the mean latency of the last window.
	 *
	 * @return The mean latency of the last window (seconds; zero before the first
	 *         window).
	 */
	double getCurrentLatencySeconds();

	/**
	 * Gets the suggested fixed limit.
	 *
	 * @return The suggested fixed limit ({@code NaN} before a healthy window).
	 */
	double getSuggestedLimit();

	/**
	 * Gets if the limit is enforced.
	 *
	 * @return 1 if the limit is enforced, 0 otherwise.
	 */
	double getEnforced();

	/**
	 * Gets the failures inside the limiter (the operations proceeded).
	 *
	 * @return The failures inside the limiter.
	 */
	long getErrors();

}
