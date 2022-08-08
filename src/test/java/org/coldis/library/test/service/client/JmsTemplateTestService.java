package org.coldis.library.test.service.client;

import java.util.HashSet;
import java.util.Set;

import javax.jms.JMSException;
import javax.jms.Message;

import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test service.
 */
@Component
public class JmsTemplateTestService {

	/**
	 * Test queue.
	 */
	public static final String JMS_TEMPLATE_TEST_QUEUE = "JmsTemplateTestQueue";

	/**
	 * Acknowledge messages.
	 */
	public static Set<String> ACKED_MESSAGES = new HashSet<>();

	/**
	 * JMS template.
	 */
	@Autowired
	private JmsTemplate jmsTemplate;

	/**
	 * JMS template helper.
	 */
	@Autowired
	private JmsTemplateHelper jmsTemplateHelper;

	/**
	 * Consumes messages.
	 *
	 * @param  destination  Destination.
	 * @param  timeout      Consume timeout.
	 * @throws JMSException If the message cannot be consumed.
	 */
	@Transactional
	public Message consumeMessage(
			final String destination,
			final Long timeout) throws JMSException {
		this.jmsTemplate.setReceiveTimeout(timeout);
		final Message message = this.jmsTemplate.receive(destination);
		if (message != null) {
			JmsTemplateTestService.ACKED_MESSAGES.add(message.getBody(String.class));
		}
		return message;
	}

	/**
	 * Sends duplicate messages.
	 *
	 * @param destination
	 * @param message
	 * @param messageId
	 * @param minimumDelaySeconds
	 * @param maximumDelaySeconds
	 */
	@Transactional
	public void sendMessage(
			final String destination,
			final Object message,
			final String lastValueKey,
			final Integer minimumDelaySeconds,
			final Integer maximumDelaySeconds) {
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage().withDestination(destination).withMessage(message).withLastValueKey(lastValueKey)
				.withFixedDelay(minimumDelaySeconds).withRandomDelay(maximumDelaySeconds));
	}

}
