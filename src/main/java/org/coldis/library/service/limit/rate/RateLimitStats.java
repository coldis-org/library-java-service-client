package org.coldis.library.service.limit.rate;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.coldis.library.model.Typable;
import org.coldis.library.model.view.ModelView;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonView;

/**
 * Rate limit stats using bucket-based sliding window counter. Requests within
 * each bucket are counted together, providing O(#buckets) per check instead of
 * O(#requests).
 */
@JsonTypeName(value = RateLimitStats.TYPE_NAME)
public class RateLimitStats implements Typable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 2765305635524612046L;

	/**
	 * Rate limit stats.
	 */
	public static final String TYPE_NAME = "org.coldis.library.service.limit.rate.RateLimitStats";

	/**
	 * Limit.
	 */
	private Long limit;

	/**
	 * Period.
	 */
	private Duration period;

	/**
	 * Backoff period.
	 */
	private Duration backoffPeriod;

	/**
	 * Bucket duration for the sliding window counter.
	 */
	private Duration bucketDuration;

	/**
	 * Buckets: bucket key (time / bucketDuration) to execution count.
	 */
	private TreeMap<Long, Long> buckets;

	/**
	 * Until when limited.
	 */
	private Long limitedUntil;

	/**
	 * No arguments constructor.
	 */
	public RateLimitStats() {
		super();
	}

	/**
	 * Gets the current time in the appropriate unit.
	 *
	 * @return Current time.
	 */
	protected long currentTime() {
		return System.nanoTime();
	}

	/**
	 * Converts a duration to the appropriate time unit.
	 *
	 * @param  duration Duration.
	 * @return          Duration in the appropriate unit.
	 */
	protected long toDurationUnit(
			final Duration duration) {
		return duration.toNanos();
	}

	/**
	 * Gets the limit.
	 *
	 * @return The limit.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Long getLimit() {
		// Makes sure the limit is initialized.
		this.limit = (this.limit == null ? 100L : this.limit);
		// Returns the limit.
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
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Duration getPeriod() {
		// Makes sure the period is initialized.
		this.period = (this.period == null ? Duration.ofMinutes(1) : this.period);
		// Returns the period.
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
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Duration getBackoffPeriod() {
		// Makes sure the period is initialized.
		this.backoffPeriod = ((this.backoffPeriod == null) || this.backoffPeriod.isNegative() ? this.getPeriod() : this.backoffPeriod);
		// Returns the period.
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
	 * Gets the bucket duration.
	 *
	 * @return The bucket duration.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Duration getBucketDuration() {
		this.bucketDuration = (this.bucketDuration == null ? Duration.ofSeconds(Long.parseLong(RateLimitConfig.DEFAULT_BUCKET_SECONDS)) : this.bucketDuration);
		return this.bucketDuration;
	}

	/**
	 * Sets the bucket duration.
	 *
	 * @param bucketDuration New bucket duration.
	 */
	public void setBucketDuration(
			final Duration bucketDuration) {
		this.bucketDuration = bucketDuration;
	}

	/**
	 * Gets the effective bucket duration, clamped to at most period/10 to
	 * ensure enough granularity for accurate sliding window counting.
	 *
	 * @return Effective bucket duration.
	 */
	protected Duration getEffectiveBucketDuration() {
		final Duration bucket = this.getBucketDuration();
		final Duration maxBucket = this.getPeriod().dividedBy(10);
		final Duration effective = (bucket.compareTo(maxBucket) > 0) ? maxBucket : bucket;
		// Ensure at least 1ms to avoid division by zero.
		return effective.isZero() || effective.isNegative() ? Duration.ofMillis(1) : effective;
	}

	/**
	 * Gets the bucket key for a given time.
	 *
	 * @param  time Time value.
	 * @return      Bucket key.
	 */
	public long toBucketKey(
			final long time) {
		return time / this.toDurationUnit(this.getEffectiveBucketDuration());
	}

	/**
	 * Gets the raw buckets map, initializing if needed.
	 *
	 * @return The buckets map.
	 */
	protected TreeMap<Long, Long> getBucketsRaw() {
		this.buckets = (this.buckets == null ? new TreeMap<>() : this.buckets);
		return this.buckets;
	}

	/**
	 * Gets the buckets, removing expired entries.
	 *
	 * @return The buckets.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public TreeMap<Long, Long> getBuckets() {
		final TreeMap<Long, Long> b = this.getBucketsRaw();
		final long windowStart = this.currentTime() - this.toDurationUnit(this.getPeriod());
		final long bucketUnit = this.toDurationUnit(this.getEffectiveBucketDuration());
		// Remove expired buckets.
		final Iterator<Map.Entry<Long, Long>> it = b.entrySet().iterator();
		while (it.hasNext()) {
			final Map.Entry<Long, Long> entry = it.next();
			final long bucketStart = entry.getKey() * bucketUnit;
			if (bucketStart <= windowStart) {
				it.remove();
			}
			else {
				break; // TreeMap is sorted, no more expired entries.
			}
		}
		return b;
	}

	/**
	 * Sets the buckets.
	 *
	 * @param buckets New buckets.
	 */
	public void setBuckets(
			final TreeMap<Long, Long> buckets) {
		this.buckets = buckets;
	}

	/**
	 * Gets the total execution count within the current sliding window.
	 *
	 * @return Execution count.
	 */
	public long getCount() {
		long count = 0;
		for (final Long value : this.getBuckets().values()) {
			count += value;
		}
		return count;
	}

	/**
	 * Gets the limitedUntil.
	 *
	 * @return The limitedUntil.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Long getLimitedUntil() {
		// Clears limit if expired.
		this.limitedUntil = ((this.limitedUntil != null) && (this.limitedUntil < this.currentTime()) ? null : this.limitedUntil);
		// Re-checks the limit.
		if (this.limitedUntil == null) {
			final long count = this.getCount();
			if (count >= this.getLimit()) {
				final TreeMap<Long, Long> b = this.getBucketsRaw();
				if (!b.isEmpty()) {
					final long oldestBucketStart = b.firstKey() * this.toDurationUnit(this.getEffectiveBucketDuration());
					this.limitedUntil = oldestBucketStart + this.toDurationUnit(this.getBackoffPeriod());
				}
			}
		}
		// Returns the limit.
		return this.limitedUntil;
	}

	/**
	 * Sets the limitedUntil.
	 *
	 * @param limitedUntil New limitedUntil.
	 */
	public void setLimitedUntil(
			final Long limitedUntil) {
		this.limitedUntil = limitedUntil;
	}

	/**
	 * Checks the limit and records the execution.
	 *
	 * @param  name               Name.
	 * @throws RateLimitException If the limit has been reached.
	 */
	public void checkLimit(
			final String name) throws RateLimitException {
		// Throws an exception if limit is already active.
		if (this.getLimitedUntil() != null) {
			throw new RateLimitException(name, this.limit);
		}
		else {
			// Add to the current bucket.
			final long now = this.currentTime();
			final long bucketKey = this.toBucketKey(now);
			this.getBucketsRaw().merge(bucketKey, 1L, Long::sum);
		}
	}

	/**
	 * @see org.coldis.library.model.Typable#getTypeName()
	 */
	@Override
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getTypeName() {
		return RateLimitStats.TYPE_NAME;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.backoffPeriod, this.buckets, this.limit, this.limitedUntil, this.period);
	}

	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(
			final Object obj) {
		if (this == obj) {
			return true;
		}
		if ((obj == null) || (this.getClass() != obj.getClass())) {
			return false;
		}
		final RateLimitStats other = (RateLimitStats) obj;
		return Objects.equals(this.backoffPeriod, other.backoffPeriod) && Objects.equals(this.buckets, other.buckets)
				&& Objects.equals(this.limit, other.limit) && Objects.equals(this.limitedUntil, other.limitedUntil)
				&& Objects.equals(this.period, other.period);
	}

}
