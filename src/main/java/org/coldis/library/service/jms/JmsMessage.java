package org.coldis.library.service.jms;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.coldis.library.helper.DateTimeHelper;

/**
 * JMS message.
 */
public class JmsMessage<MessageType> {

	/**
	 * Random.
	 */
	private static final Random RANDOM = new SecureRandom();

	/**
	 * Name of the message property holding the stale message filter key.
	 */
	public static final String STALE_FILTER_KEY_PROPERTY = "staleFilterKey";

	/**
	 * Name of the optional message property overriding the timestamp (wall-clock
	 * epoch millis) at which the message was posted. When absent, consumers fall
	 * back to the scheduled delivery time (when set) and then to the JMS
	 * timestamp, so producers do not usually need to set this property.
	 */
	public static final String STALE_FILTER_POSTED_AT_PROPERTY = "staleFilterPostedAt";

	/**
	 * Message destination.
	 */
	private String destination;

	/**
	 * Message.
	 */
	private MessageType message;

	/**
	 * Priority.
	 */
	private Integer priority;

	/**
	 * Correlation id.
	 */
	private String correlationId;

	/**
	 * Last value key.
	 */
	private String lastValueKey;

	/**
	 * Stale message filter key. Unlike {@link #lastValueKey} (which relies on
	 * broker-side last-value queues), this key enables consumer-side stale message
	 * filtering: consumers may drop this message if another message with the same
	 * key started successful processing after this message was posted. Only
	 * suitable for refresh-style messages (triggers to recompute from current
	 * state), never for messages whose body carries state.
	 */
	private String staleFilterKey;

	/**
	 * Fixed delay.
	 */
	private Duration fixedDelay;

	/**
	 * Random delay.
	 */
	private Duration randomDelay;

	/**
	 * Absolute scheduled delivery time (alternative to fixedDelay/randomDelay).
	 */
	private LocalDateTime scheduledAt;

	/**
	 * Computed scheduled delivery timestamp (epoch millis). Cached on first access, cleared on any scheduling change.
	 */
	private Long scheduledDeliveryTime;

	/**
	 * If true and a transaction is active when {@code JmsTemplateHelper.send} is called, the
	 * actual JMS send is deferred until after the transaction commits. Falls back to an immediate
	 * send when no transaction is active.
	 */
	private boolean afterCommit;

	/**
	 * Properties.
	 */
	private Map<String, Object> properties;

	/**
	 * Gets the destination.
	 *
	 * @return The destination.
	 */
	public String getDestination() {
		return this.destination;
	}

	/**
	 * Sets the destination.
	 *
	 * @param destination New destination.
	 */
	public void setDestination(
			final String destination) {
		this.destination = destination;
	}

	/**
	 * Sets the destination.
	 *
	 * @param  destination New destination.
	 * @return             Message.
	 */
	public JmsMessage<MessageType> withDestination(
			final String destination) {
		this.setDestination(destination);
		return this;
	}

	/**
	 * Gets the message.
	 *
	 * @return The message.
	 */
	public MessageType getMessage() {
		return this.message;
	}

	/**
	 * Sets the message.
	 *
	 * @param message New message.
	 */
	public void setMessage(
			final MessageType message) {
		this.message = message;
	}

	/**
	 * Sets the message.
	 *
	 * @param  message New message.
	 * @return         Message.
	 */
	public JmsMessage<MessageType> withMessage(
			final MessageType message) {
		this.setMessage(message);
		return this;
	}

	/**
	 * Gets the priority.
	 *
	 * @return The priority.
	 */
	public Integer getPriority() {
		return this.priority;
	}

	/**
	 * Sets the priority.
	 *
	 * @param priority New priority.
	 */
	public void setPriority(
			final Integer priority) {
		this.priority = priority;
	}

	/**
	 * Sets the priority.
	 *
	 * @param  priority New priority.
	 * @return          Message.
	 */
	public JmsMessage<MessageType> withPriority(
			final Integer priority) {
		this.setPriority(priority);
		return this;
	}

	/**
	 * Gets the correlationId.
	 *
	 * @return The correlationId.
	 */
	public String getCorrelationId() {
		return this.correlationId;
	}

	/**
	 * Sets the correlationId.
	 *
	 * @param correlationId New correlationId.
	 */
	public void setCorrelationId(
			final String correlationId) {
		this.correlationId = correlationId;
	}

	/**
	 * Sets the correlationId.
	 *
	 * @param  correlationId New correlationId.
	 * @return               Message.
	 */
	public JmsMessage<MessageType> withCorrelationId(
			final String correlationId) {
		this.setCorrelationId(correlationId);
		return this;
	}

	/**
	 * Gets the lastValueKey.
	 *
	 * @return The lastValueKey.
	 */
	public String getLastValueKey() {
		return this.lastValueKey;
	}

	/**
	 * Sets the lastValueKey.
	 *
	 * @param lastValueKey New lastValueKey.
	 */
	public void setLastValueKey(
			final String lastValueKey) {
		this.lastValueKey = lastValueKey;
	}

	/**
	 * Sets the lastValueKey.
	 *
	 * @param  lastValueKey New lastValueKey.
	 * @return              Message.
	 */
	public JmsMessage<MessageType> withLastValueKey(
			final String lastValueKey) {
		this.setLastValueKey(lastValueKey);
		return this;
	}

	/**
	 * Gets the staleFilterKey.
	 *
	 * @return The staleFilterKey.
	 */
	public String getStaleFilterKey() {
		return this.staleFilterKey;
	}

	/**
	 * Sets the staleFilterKey.
	 *
	 * @param staleFilterKey New staleFilterKey.
	 */
	public void setStaleFilterKey(
			final String staleFilterKey) {
		this.staleFilterKey = staleFilterKey;
	}

	/**
	 * Sets the staleFilterKey.
	 *
	 * @param  staleFilterKey New staleFilterKey.
	 * @return                Message.
	 */
	public JmsMessage<MessageType> withStaleFilterKey(
			final String staleFilterKey) {
		this.setStaleFilterKey(staleFilterKey);
		return this;
	}

	/**
	 * Gets the fixedDelay.
	 *
	 * @return The fixedDelay.
	 */
	public Duration getFixedDelay() {
		this.fixedDelay = ((this.fixedDelay != null) && (this.fixedDelay.isNegative()) ? Duration.ofMillis(0L) : this.fixedDelay);
		return this.fixedDelay;
	}

	/**
	 * Sets the fixedDelay.
	 *
	 * @param fixedDelay New fixedDelay.
	 */
	public void setFixedDelay(
			final Duration fixedDelay) {
		this.fixedDelay = fixedDelay;
		this.scheduledAt = null;
		this.scheduledDeliveryTime = null;
	}

	/**
	 * Sets the fixedDelay.
	 *
	 * @param  fixedDelay New fixedDelay.
	 * @return            Message.
	 */
	public JmsMessage<MessageType> withFixedDelay(
			final Duration fixedDelay) {
		this.setFixedDelay(fixedDelay);
		return this;
	}

	/**
	 * Gets the randomDelay.
	 *
	 * @return The randomDelay.
	 */
	public Duration getRandomDelay() {
		this.randomDelay = ((this.randomDelay != null) && (this.randomDelay.isNegative()) ? Duration.ofMillis(0L) : this.randomDelay);
		return this.randomDelay;
	}

	/**
	 * Sets the randomDelay.
	 *
	 * @param randomDelay New randomDelay.
	 */
	public void setRandomDelay(
			final Duration randomDelay) {
		this.randomDelay = randomDelay;
		this.scheduledAt = null;
		this.scheduledDeliveryTime = null;
	}

	/**
	 * Sets the randomDelay.
	 *
	 * @param  randomDelay New randomDelay.
	 * @return             Message.
	 */
	public JmsMessage<MessageType> withRandomDelay(
			final Duration randomDelay) {
		this.setRandomDelay(randomDelay);
		return this;
	}

	/**
	 * Gets the scheduledAt.
	 *
	 * @return The scheduledAt.
	 */
	public LocalDateTime getScheduledAt() {
		return this.scheduledAt;
	}

	/**
	 * Sets the scheduledAt.
	 *
	 * @param scheduledAt New scheduledAt.
	 */
	public void setScheduledAt(
			final LocalDateTime scheduledAt) {
		this.scheduledAt = scheduledAt;
		this.fixedDelay = null;
		this.randomDelay = null;
		this.scheduledDeliveryTime = null;
	}

	/**
	 * Sets the scheduledAt.
	 *
	 * @param  scheduledAt New scheduledAt.
	 * @return             Message.
	 */
	public JmsMessage<MessageType> withScheduledAt(
			final LocalDateTime scheduledAt) {
		this.setScheduledAt(scheduledAt);
		return this;
	}

	/**
	 * Returns the scheduled delivery timestamp in epoch millis, computed once and cached.
	 * Returns null if no schedule is set.
	 *
	 * @return Scheduled delivery time in epoch millis, or null.
	 */
	public Long getScheduledDeliveryTime() {
		if (this.scheduledDeliveryTime == null) {
			if (this.scheduledAt != null) {
				this.scheduledDeliveryTime = DateTimeHelper.toTimestamp(this.scheduledAt);
			}
			else if ((this.getFixedDelay() != null) || (this.getRandomDelay() != null)) {
				final long fixed = (this.getFixedDelay() == null ? 0L : this.getFixedDelay().toMillis());
				final long random = ((this.getRandomDelay() != null) && this.getRandomDelay().isPositive()
						? Math.abs(JmsMessage.RANDOM.nextLong(this.getRandomDelay().toMillis()))
						: 0L);
				// Anchor to DateTimeHelper.now() (mockable clock) rather than System.currentTimeMillis(),
				// so callers using a virtual clock see consistent scheduling decisions.
				this.scheduledDeliveryTime = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime()) + fixed + random;
			}
		}
		return this.scheduledDeliveryTime;
	}

	/**
	 * Gets the afterCommit.
	 *
	 * @return The afterCommit.
	 */
	public boolean isAfterCommit() {
		return this.afterCommit;
	}

	/**
	 * Sets the afterCommit.
	 *
	 * @param afterCommit New afterCommit.
	 */
	public void setAfterCommit(
			final boolean afterCommit) {
		this.afterCommit = afterCommit;
	}

	/**
	 * Sets the afterCommit.
	 *
	 * @param  afterCommit New afterCommit.
	 * @return             Message.
	 */
	public JmsMessage<MessageType> withAfterCommit(
			final boolean afterCommit) {
		this.setAfterCommit(afterCommit);
		return this;
	}

	/**
	 * Gets the properties.
	 *
	 * @return The properties.
	 */
	public Map<String, Object> getProperties() {
		// Makes sure the map is initialized.
		this.properties = (this.properties == null ? new HashMap<>() : this.properties);
		// Returns the map.
		return this.properties;
	}

	/**
	 * Sets the properties.
	 *
	 * @param properties New properties.
	 */
	public void setProperties(
			final Map<String, Object> properties) {
		this.properties = properties;
	}

	/**
	 * Sets the properties.
	 *
	 * @param  properties New properties.
	 * @return            The message.
	 */
	public JmsMessage<MessageType> withProperties(
			final Map<String, Object> properties) {
		this.setProperties(properties);
		return this;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.correlationId, this.destination, this.fixedDelay, this.lastValueKey, this.staleFilterKey, this.message, this.priority,
				this.properties, this.randomDelay, this.scheduledAt);
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
		final JmsMessage other = (JmsMessage) obj;
		return Objects.equals(this.correlationId, other.correlationId) && Objects.equals(this.destination, other.destination)
				&& Objects.equals(this.fixedDelay, other.fixedDelay) && Objects.equals(this.lastValueKey, other.lastValueKey)
				&& Objects.equals(this.staleFilterKey, other.staleFilterKey) && Objects.equals(this.message, other.message)
				&& Objects.equals(this.priority, other.priority) && Objects.equals(this.properties, other.properties)
				&& Objects.equals(this.randomDelay, other.randomDelay) && Objects.equals(this.scheduledAt, other.scheduledAt);
	}

}
