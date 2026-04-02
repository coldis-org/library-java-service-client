package org.coldis.library.service.limit.rate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.coldis.library.model.Typable;
import org.coldis.library.model.view.ModelView;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonView;

/**
 * Rate limit stats.
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
	 * Executions.
	 */
	private ArrayList<Long> executions;

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
		// Returns the period.
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
	 * Gets the executions.
	 *
	 * @return The executions.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public ArrayList<Long> getExecutions() {
		// Makes sure the set is initialized.
		this.executions = (this.executions == null ? new ArrayList<>() : this.executions);
		// Drops expired executions.
		this.executions.removeIf(execution -> execution < (this.currentTime() - this.toDurationUnit(this.getPeriod())));
		// Returns the set.
		return this.executions;
	}

	/**
	 * Sets the executions.
	 *
	 * @param executions New executions.
	 */
	public void setExecutions(
			final ArrayList<Long> executions) {
		this.executions = executions;
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
		this.limitedUntil = ((this.limitedUntil == null)
				? (this.getExecutions().size() >= this.getLimit()) ? this.getExecutions().get(0) + this.toDurationUnit(this.getBackoffPeriod()) : null
				: this.limitedUntil);
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
	 * Checks the limit.
	 *
	 * @param  name               Name.
	 * @throws RateLimitException If the limit has been reached.
	 */
	public void checkLimit(
			final String name) throws RateLimitException {
		// Throws and exception limit is already scheduled.
		if (this.getLimitedUntil() != null) {
			throw new RateLimitException(name, this.limit);
		}
		else {
			this.getExecutions().add(this.currentTime());
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
		return Objects.hash(this.backoffPeriod, this.executions, this.limit, this.limitedUntil, this.period);
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
		return Objects.equals(this.backoffPeriod, other.backoffPeriod) && Objects.equals(this.executions, other.executions)
				&& Objects.equals(this.limit, other.limit) && Objects.equals(this.limitedUntil, other.limitedUntil)
				&& Objects.equals(this.period, other.period);
	}

}
