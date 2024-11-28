package org.coldis.library.test.service.client;

import java.time.Duration;
import java.util.Random;

import org.coldis.library.test.ContainerExtension;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.StopTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.testcontainers.containers.GenericContainer;

import jakarta.jms.JMSException;

/**
 * JMS message converter test.
 */
@TestWithContainer
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(StopTestWithContainerExtension.class)
public class JmsTemplateServiceTest {

	/**
	 * Artemis container.
	 */
	public static GenericContainer<?> ARTEMIS_CONTAINER = TestHelper.createArtemisContainer();

	/**
	 * Random.
	 */
	private static final Random RANDOM = new Random();

	/**
	 * Test service.
	 */
	@Autowired
	private JmsTemplateTestService jmsTemplateTestService;

	/**
	 * Clears before tests.
	 *
	 * @throws JMSException If the test fails.
	 */
	@BeforeEach
	public void clear() throws JMSException {

		// Clears acked messages.
		JmsTemplateTestService.ACKED_MESSAGES.clear();

	}

	/**
	 * Tests last value messages.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testLastValue() throws Exception {

		final Integer random = JmsTemplateServiceTest.RANDOM.nextInt();

		// Sends messages in a row.
		this.jmsTemplateTestService.sendMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, "message1-" + random /* message */,
				null /* last value */, Duration.ofMillis(0), Duration.ofMillis(0));
		this.jmsTemplateTestService.sendMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, "message2-" + random, null, Duration.ofMillis(0),
				Duration.ofMillis(0));
		this.jmsTemplateTestService.sendMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, "message3-" + random, "teste2", Duration.ofMillis(0),
				Duration.ofMillis(0));
		this.jmsTemplateTestService.sendMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, "message4-" + random, "teste2", Duration.ofMillis(0),
				Duration.ofMillis(0));

		// Wait until all messages are sent and consumes them.
		this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 1000L);
		this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 1000L);
		this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 1000L);
		this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 1000L);

		// Last values messages should not be consumed twice,
		Assertions.assertTrue(JmsTemplateTestService.ACKED_MESSAGES.contains("message1-" + random));
		Assertions.assertTrue(JmsTemplateTestService.ACKED_MESSAGES.contains("message2-" + random));
		Assertions.assertTrue(!JmsTemplateTestService.ACKED_MESSAGES.contains("message3-" + random));
		Assertions.assertTrue(JmsTemplateTestService.ACKED_MESSAGES.contains("message4-" + random));

		// A new last value message should be consumed.
		this.jmsTemplateTestService.sendMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, "message5-" + random, "teste2", Duration.ofMillis(0),
				Duration.ofMillis(0));
		this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 1000L);
		Assertions.assertTrue(JmsTemplateTestService.ACKED_MESSAGES.contains("message5-" + random));
	}

	/**
	 * Tests last value messages.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testScheduled() throws Exception {

		final Integer random = JmsTemplateServiceTest.RANDOM.nextInt();

		// Sends messages in a row.
		Assertions.assertFalse(JmsTemplateTestService.ACKED_MESSAGES.contains("testScheduled1-" + random));
		this.jmsTemplateTestService.sendMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, "testScheduled1-" + random /* message */,
				null /* last value */, Duration.ofMillis(30 * 1000) /* fixed wait */, Duration.ofMillis(0) /* random wait */);
		this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 10L);
		Assertions.assertFalse(JmsTemplateTestService.ACKED_MESSAGES.contains("testScheduled1-" + random));

		TestHelper.waitUntilValid(() -> {
			return JmsTemplateTestService.ACKED_MESSAGES.contains("testScheduled1-" + random);
		}, consumed -> {
			try {
				this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 10L);
				this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 10L);
				this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 10L);
			}
			catch (final JMSException expcetion) {
				throw new RuntimeException();
			}
			return consumed != null;
		}, 27 * 1000, 1 * 1000);
		Assertions.assertFalse(JmsTemplateTestService.ACKED_MESSAGES.contains("testScheduled1-" + random));

		// Waits and tries again.
		Thread.sleep(30 * 1000);
		this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 10L);
		this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 10L);
		this.jmsTemplateTestService.consumeMessage(JmsTemplateTestService.JMS_TEMPLATE_TEST_QUEUE + random, 10L);
		Assertions.assertTrue(JmsTemplateTestService.ACKED_MESSAGES.contains("testScheduled1-" + random));

	}

}
