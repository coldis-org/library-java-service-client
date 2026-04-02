package org.coldis.library.service.limit.rate.jpa;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for rate limit entry.
 */
public class RateLimitEntryId implements Serializable {

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
	public RateLimitEntryId() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param name Name.
	 * @param key  Key.
	 */
	public RateLimitEntryId(
			final String name,
			final String key) {
		this.name = name;
		this.key = key;
	}

	/**
	 * Gets the name.
	 *
	 * @return The name.
	 */
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
	public String getKey() {
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
		final RateLimitEntryId other = (RateLimitEntryId) obj;
		return Objects.equals(this.name, other.name) && Objects.equals(this.key, other.key);
	}

}
