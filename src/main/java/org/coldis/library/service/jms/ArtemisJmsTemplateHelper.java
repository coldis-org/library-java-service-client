package org.coldis.library.service.jms;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Random;

import org.apache.activemq.artemis.api.core.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * JMS template helper.
 */
@Component
@ConditionalOnClass(value = { JmsTemplate.class, ArtemisProperties.class })
public class ArtemisJmsTemplateHelper implements JmsTemplateHelper {

	/**
	 * Random.
	 */
	private static final Random RANDOM = new SecureRandom();

	/**
	 * @see org.coldis.library.service.jms.JmsTemplateHelper#send(org.springframework.jms.core.JmsTemplate,
	 *      org.coldis.library.service.jms.JmsMessage)
	 */
	@Override
	public void send(
			final JmsTemplate template,
			final JmsMessage<?> message) {

		// Tries sending the message.
		template.send(message.getDestination(), session -> {
			// Creates the message.
			final jakarta.jms.Message jmsMessage = template.getMessageConverter().toMessage(message.getMessage(), session);
			// Adds the delay.
			if ((message.getFixedDelay() != null) || (message.getRandomDelay() != null)) {
				final Long fixedDelay = (message.getFixedDelay() == null ? 0L : message.getFixedDelay().toMillis());
				final Long randomDelay = ((message.getRandomDelay() != null) && message.getRandomDelay().isPositive()
						? Math.abs(ArtemisJmsTemplateHelper.RANDOM.nextLong(message.getRandomDelay().toMillis()))
						: 0L);
				final long scheduledTimestamp = (System.currentTimeMillis() + fixedDelay + randomDelay);
				if ((System.currentTimeMillis() + 1000) < scheduledTimestamp) {
					jmsMessage.setLongProperty(Message.HDR_SCHEDULED_DELIVERY_TIME.toString(), fixedDelay + randomDelay);
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
