package org.coldis.library.service.jms;

import java.util.Objects;

/**
 * JMS message.
 */
public class JmsMessage<MessageType> {

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
	 * Fixed delay.
	 */
	private Integer fixedDelay;

	/**
	 * Random delay.
	 */
	private Integer randomDelay;

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
	 * Gets the fixedDelay.
	 *
	 * @return The fixedDelay.
	 */
	public Integer getFixedDelay() {
		this.fixedDelay = (this.fixedDelay == null ? 0 : this.fixedDelay);
		return this.fixedDelay;
	}

	/**
	 * Sets the fixedDelay.
	 *
	 * @param fixedDelay New fixedDelay.
	 */
	public void setFixedDelay(
			final Integer fixedDelay) {
		this.fixedDelay = fixedDelay;
	}

	/**
	 * Sets the fixedDelay.
	 *
	 * @param  fixedDelay New fixedDelay.
	 * @return            Message.
	 */
	public JmsMessage<MessageType> withFixedDelay(
			final Integer fixedDelay) {
		this.setFixedDelay(fixedDelay);
		return this;
	}

	/**
	 * Gets the randomDelay.
	 *
	 * @return The randomDelay.
	 */
	public Integer getRandomDelay() {
		this.randomDelay = (this.randomDelay == null ? 0 : this.randomDelay);
		return this.randomDelay;
	}

	/**
	 * Sets the randomDelay.
	 *
	 * @param randomDelay New randomDelay.
	 */
	public void setRandomDelay(
			final Integer randomDelay) {
		this.randomDelay = randomDelay;
	}

	/**
	 * Sets the randomDelay.
	 *
	 * @param  randomDelay New randomDelay.
	 * @return             Message.
	 */
	public JmsMessage<MessageType> withRandomDelay(
			final Integer randomDelay) {
		this.setRandomDelay(randomDelay);
		return this;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.correlationId, this.destination, this.fixedDelay, this.lastValueKey, this.message, this.priority, this.randomDelay);
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
		@SuppressWarnings("rawtypes")
		final JmsMessage other = (JmsMessage) obj;
		return Objects.equals(this.correlationId, other.correlationId) && Objects.equals(this.destination, other.destination)
				&& Objects.equals(this.fixedDelay, other.fixedDelay) && Objects.equals(this.lastValueKey, other.lastValueKey)
				&& Objects.equals(this.message, other.message) && Objects.equals(this.priority, other.priority)
				&& Objects.equals(this.randomDelay, other.randomDelay);
	}

}
