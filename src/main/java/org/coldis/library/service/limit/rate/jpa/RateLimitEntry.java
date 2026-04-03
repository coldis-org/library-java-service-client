package org.coldis.library.service.limit.rate.jpa;

import java.time.Duration;
import java.util.Objects;
import java.util.TreeMap;

import org.coldis.library.model.view.ModelView;
import org.coldis.library.service.limit.rate.RateLimitStats;

import com.fasterxml.jackson.annotation.JsonView;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Rate limit entry (JPA entity). Extends {@link RateLimitStats} using
 * epoch millis instead of nanoTime for cross-JVM compatibility.
 */
@Entity
@Table(name = "rate_limit")
@IdClass(RateLimitEntryId.class)
public class RateLimitEntry extends RateLimitStats {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * Name.
	 */
	private String name;

	/**
	 * Key.
	 */
	private String key;

	/**
	 * No arguments constructor.
	 */
	public RateLimitEntry() {
		super();
	}

	/**
	 * @see RateLimitStats#currentTime()
	 */
	@Override
	@Transient
	protected long currentTime() {
		return System.currentTimeMillis();
	}

	/**
	 * @see RateLimitStats#toDurationUnit(Duration)
	 */
	@Override
	@Transient
	protected long toDurationUnit(
			final Duration duration) {
		return duration.toMillis();
	}

	/**
	 * Gets the name.
	 *
	 * @return The name.
	 */
	@Id
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getName() {
		return this.name;
	}

	/**
	 * Sets the name.
	 *
	 * @param name New name.
	 */
	public void setName(
			final String name) {
		this.name = name;
	}

	/**
	 * Gets the key.
	 *
	 * @return The key.
	 */
	@Id
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getKey() {
		this.key = (this.key == null ? "" : this.key);
		return this.key;
	}

	/**
	 * Sets the key.
	 *
	 * @param key New key.
	 */
	public void setKey(
			final String key) {
		this.key = key;
	}

	/**
	 * @see RateLimitStats#getBuckets()
	 */
	@Override
	@Column(columnDefinition = "JSONB")
	@Convert(converter = BucketMapJsonConverter.class)
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public TreeMap<Long, Long> getBuckets() {
		return super.getBuckets();
	}

	/**
	 * @see RateLimitStats#getLimitedUntil()
	 */
	@Override
	@Column(name = "limited_until")
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Long getLimitedUntil() {
		return super.getLimitedUntil();
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.name, this.key);
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
		final RateLimitEntry other = (RateLimitEntry) obj;
		return Objects.equals(this.name, other.name) && Objects.equals(this.key, other.key);
	}

}
