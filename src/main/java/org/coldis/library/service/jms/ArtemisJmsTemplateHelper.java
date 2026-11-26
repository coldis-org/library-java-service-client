package org.coldis.library.service.jms;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.apache.activemq.artemis.api.core.Message;
import org.coldis.library.cache.LocalThrottle;
import org.coldis.library.cache.LocalThrottleReservation;
import org.coldis.library.helper.DateTimeHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JMS template helper.
 */
@Component
@ConditionalOnClass(value = { JmsTemplate.class, ArtemisProperties.class })
public class ArtemisJmsTemplateHelper implements JmsTemplateHelper {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ArtemisJmsTemplateHelper.class);

	/**
	 * Separates the destination from the throttle key in the send throttle key.
	 */
	private static final String THROTTLE_KEY_SEPARATOR = "\u0000";

	/**
	 * Send throttle (see {@link JmsMessage#getThrottleKey()}). On the wall clock,
	 * not the application clock: tests move the application clock, and a send
	 * recorded at a moved time would throttle the key until real time catches up.
	 */
	private final LocalThrottle sendThrottle = new LocalThrottle(LocalThrottle.DEFAULT_TIMES_TO_RUN_BEFORE_CLEARING_EXPIRED,
			() -> LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));

	/**
	 * Send throttle metrics.
	 */
	private JmsSendThrottleMetrics sendThrottleMetrics = JmsSendThrottleMetrics.NONE;

	/**
	 * Sets the send throttle metrics (none when there is no metrics bean).
	 *
	 * @param sendThrottleMetrics Send throttle metrics.
	 */
	@Autowired(required = false)
	public void setSendThrottleMetrics(
			final JmsSendThrottleMetrics sendThrottleMetrics) {
		this.sendThrottleMetrics = (sendThrottleMetrics == null ? JmsSendThrottleMetrics.NONE : sendThrottleMetrics);
	}

	/**
	 * If a message is throttled: it has a throttle key and a positive interval.
	 *
	 * @param  message Message.
	 * @return         If the message is throttled.
	 */
	private static boolean isThrottled(
			final JmsMessage<?> message) {
		return (message.getThrottleKey() != null) && (message.getThrottleInterval() != null) && message.getThrottleInterval().isPositive();
	}

	/**
	 * Performs the actual JMS send.
	 */
	private void doSend(
			final JmsTemplate template,
			final JmsMessage<?> message) {
		template.send(message.getDestination(), session -> {
			// Creates the message.
			final jakarta.jms.Message jmsMessage = template.getMessageConverter().toMessage(message.getMessage(), session);
			// Adds the scheduled delivery time. The decision ("should we schedule?") uses
			// DateTimeHelper.now() so callers on a virtual clock get consistent behaviour.
			// The wall-clock value sent to the broker, however, must be anchored to
			// System.currentTimeMillis() because Artemis gates HDR_SCHEDULED_DELIVERY_TIME
			// against real wall time — using the DateTimeHelper-derived epoch directly
			// would hold messages for the full clock-offset under tests that advance the
			// simulated clock.
			final Long scheduledTimestamp = message.getScheduledDeliveryTime();
			if (scheduledTimestamp != null) {
				final long delay = scheduledTimestamp
						- DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime());
				if (delay > 0L) {
					jmsMessage.setLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME.toString(),
							System.currentTimeMillis() + delay);
				}
			}
			// Sets the priority.
			if (message.getPriority() != null) {
				jmsMessage.setJMSPriority(Math.max(1, Math.min(message.getPriority().intValue(), 9)));
			}
			// Sets the correlation id.
			if (message.getCorrelationId() != null) {
				jmsMessage.setJMSCorrelationID(message.getCorrelationId());
			}
			// Sets the message id.
			if (message.getLastValueKey() != null) {
				jmsMessage.setStringProperty(Message.HDR_LAST_VALUE_NAME.toString(), message.getLastValueKey());
			}
			// Sets the stale message filter key. The posted-at timestamp is not set
			// here: consumers derive it from the scheduled delivery time (when set) or
			// the JMS timestamp, both in wall-clock domain.
			if (message.getStaleFilterKey() != null) {
				jmsMessage.setStringProperty(JmsMessage.STALE_FILTER_KEY_PROPERTY, message.getStaleFilterKey());
			}
			// Adds each extra properties.
			for (final Map.Entry<String, Object> property : message.getProperties().entrySet()) {
				jmsMessage.setObjectProperty(property.getKey(), property.getValue());
				// TODO Other types.
			}
			// Returns the message.
			return jmsMessage;
		});
	}

	/**
	 * Sends a message, unless it is throttled and a message with the same
	 * throttle key was sent to the same destination within the throttle interval.
	 * A transactional send (a session-transacted template, in a synchronized
	 * transaction) gives its throttle key back if the transaction does not commit,
	 * as the message is rolled back with it. A send that is not transactional
	 * keeps the key: the message reached the broker whatever the transaction does.
	 * A message skipped while that transaction was open is not replayed: the next
	 * same-key send goes out.
	 *
	 * @param template                JMS template.
	 * @param message                 Message.
	 * @param releaseOnUncommittedEnd If the throttle key is given back when the
	 *                                    current transaction does not commit.
	 */
	private void throttleAndSend(
			final JmsTemplate template,
			final JmsMessage<?> message,
			final boolean releaseOnUncommittedEnd) {
		if (!ArtemisJmsTemplateHelper.isThrottled(message)) {
			this.doSend(template, message);
		}
		else {
			final LocalThrottleReservation reservation = this.sendThrottle.tryAcquire(
					message.getDestination() + ArtemisJmsTemplateHelper.THROTTLE_KEY_SEPARATOR + message.getThrottleKey(), message.getThrottleInterval(), null);
			if (reservation == null) {
				this.sendThrottleMetrics.recordSkipped(message.getDestination());
				ArtemisJmsTemplateHelper.LOGGER.debug("Skipping a send to '{}': its throttle key was sent within {}.", message.getDestination(),
						message.getThrottleInterval());
			}
			else {
				if (releaseOnUncommittedEnd && template.isSessionTransacted() && TransactionSynchronizationManager.isSynchronizationActive()) {
					TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
						@Override
						public void afterCompletion(
								final int status) {
							if (status != TransactionSynchronization.STATUS_COMMITTED) {
								ArtemisJmsTemplateHelper.this.sendThrottle.release(reservation);
							}
						}
					});
				}
				try {
					this.doSend(template, message);
				}
				catch (final RuntimeException exception) {
					this.sendThrottle.release(reservation);
					throw exception;
				}
			}
		}
	}

	/**
	 * @see org.coldis.library.service.jms.JmsTemplateHelper#send(org.springframework.jms.core.JmsTemplate,
	 *      org.coldis.library.service.jms.JmsMessage)
	 */
	@Override
	public void send(
			final JmsTemplate template,
			final JmsMessage<?> message) {
		if (message.isAfterCommit() && TransactionSynchronizationManager.isActualTransactionActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					ArtemisJmsTemplateHelper.this.throttleAndSend(template, message, false);
				}
			});
		}
		else {
			this.throttleAndSend(template, message, true);
		}
	}

}
