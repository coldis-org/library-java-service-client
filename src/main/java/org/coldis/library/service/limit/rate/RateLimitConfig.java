package org.coldis.library.service.limit.rate;

import java.time.Duration;

/**
 * Rate limit configuration.
 */
public class RateLimitConfig {

	/**
	 * Maximum number of executions within the period.
	 */
	private Long limit;

	/**
	 * Time window duration.
	 */
	private Duration period;

	/**
	 * Duration to block after limit is exceeded.
	 */
	private Duration backoffPeriod;

	/**
	 * Number of local executions before flushing to the central store.
	 */
	private Integer bufferSize;

	/**
	 * Maximum duration between flushes to the central store.
	 */
	private Duration bufferDuration;

	/**
	 * No arguments constructor.
	 */
	public RateLimitConfig() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param limit          Limit.
	 * @param period         Period.
	 * @param backoffPeriod  Backoff period.
	 * @param bufferSize     Buffer size.
	 * @param bufferDuration Buffer duration.
	 */
	public RateLimitConfig(
			final Long limit,
			final Duration period,
			final Duration backoffPeriod,
			final Integer bufferSize,
			final Duration bufferDuration) {
		this.limit = limit;
		this.period = period;
		this.backoffPeriod = backoffPeriod;
		this.bufferSize = bufferSize;
		this.bufferDuration = bufferDuration;
	}

	/**
	 * Gets the limit.
	 *
	 * @return The limit.
	 */
	public Long getLimit() {
		return this.limit;
	}

	/**
	 * Sets the limit.
	 *
	 * @param limit New limit.
	 */
	public void setLimit(
			final Long limit) {
		this.limit = limit;
	}

	/**
	 * Gets the period.
	 *
	 * @return The period.
	 */
	public Duration getPeriod() {
		return this.period;
	}

	/**
	 * Sets the period.
	 *
	 * @param period New period.
	 */
	public void setPeriod(
			final Duration period) {
		this.period = period;
	}

	/**
	 * Gets the backoffPeriod.
	 *
	 * @return The backoffPeriod.
	 */
	public Duration getBackoffPeriod() {
		return this.backoffPeriod;
	}

	/**
	 * Sets the backoffPeriod.
	 *
	 * @param backoffPeriod New backoffPeriod.
	 */
	public void setBackoffPeriod(
			final Duration backoffPeriod) {
		this.backoffPeriod = backoffPeriod;
	}

	/**
	 * Gets the bufferSize.
	 *
	 * @return The bufferSize.
	 */
	public Integer getBufferSize() {
		this.bufferSize = (this.bufferSize == null ? 5000 : this.bufferSize);
		return this.bufferSize;
	}

	/**
	 * Sets the bufferSize.
	 *
	 * @param bufferSize New bufferSize.
	 */
	public void setBufferSize(
			final Integer bufferSize) {
		this.bufferSize = bufferSize;
	}

	/**
	 * Gets the bufferDuration.
	 *
	 * @return The bufferDuration.
	 */
	public Duration getBufferDuration() {
		this.bufferDuration = (this.bufferDuration == null ? Duration.ofMinutes(1) : this.bufferDuration);
		return this.bufferDuration;
	}

	/**
	 * Sets the bufferDuration.
	 *
	 * @param bufferDuration New bufferDuration.
	 */
	public void setBufferDuration(
			final Duration bufferDuration) {
		this.bufferDuration = bufferDuration;
	}

}
