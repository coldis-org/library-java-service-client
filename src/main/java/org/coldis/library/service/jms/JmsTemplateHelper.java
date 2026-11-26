package org.coldis.library.service.jms;

import org.springframework.jms.core.JmsTemplate;

/**
 * JMS template service. Implementations apply every option of the
 * {@link JmsMessage}, the send throttle ({@link JmsMessage#getThrottleKey()})
 * included.
 */
public interface JmsTemplateHelper {

	/**
	 * Sends a JMS message.
	 *
	 * @param template JMS template.
	 * @param message  Message.
	 */
	void send(
			JmsTemplate template,
			JmsMessage<?> message);

}
