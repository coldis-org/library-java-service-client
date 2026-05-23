package org.coldis.library.service.jms;

import java.util.Map;

import org.apache.activemq.artemis.api.core.Message;
import org.coldis.library.helper.DateTimeHelper;
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
					ArtemisJmsTemplateHelper.this.doSend(template, message);
				}
			});
		}
		else {
			this.doSend(template, message);
		}
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
			// Adds each extra properties.
			for (final Map.Entry<String, Object> property : message.getProperties().entrySet()) {
				jmsMessage.setObjectProperty(property.getKey(), property.getValue());
				// TODO Other types.
			}
			// Returns the message.
			return jmsMessage;
		});
	}

}
