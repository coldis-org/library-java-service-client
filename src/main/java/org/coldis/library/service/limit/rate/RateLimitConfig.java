package org.coldis.library.service.limit.rate;

import java.time.Duration;

/**
 * Rate limit configuration.
 */
public class RateLimitConfig {

	/**
	 * Default bucket duration in seconds (10 minutes).
	 */
	public static final String DEFAULT_BUCKET_SECONDS = "600";

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
	 * Bucket duration for the sliding window counter.
	 */
	private Duration bucket;

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
	 * @param bucket         Bucket duration.
	 */
	public RateLimitConfig(
			final Long limit,
			final Duration period,
			final Duration backoffPeriod,
			final Integer bufferSize,
			final Duration bufferDuration,
			final Duration bucket) {
		this.limit = limit;
		this.period = period;
		this.backoffPeriod = backoffPeriod;
		this.bufferSize = bufferSize;
		this.bufferDuration = bufferDuration;
		this.bucket = bucket;
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

	/**
	 * Gets the bucket.
	 *
	 * @return The bucket.
	 */
	public Duration getBucket() {
		this.bucket = (this.bucket == null ? Duration.ofSeconds(Long.parseLong(DEFAULT_BUCKET_SECONDS)) : this.bucket);
		return this.bucket;
	}

	/**
	 * Sets the bucket.
	 *
	 * @param bucket New bucket.
	 */
	public void setBucket(
			final Duration bucket) {
		this.bucket = bucket;
	}

}
