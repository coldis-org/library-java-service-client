package org.coldis.library.test.service.jms;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.coldis.library.service.jms.ArtemisJmsTemplateHelper;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.MicrometerJmsSendThrottleMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * JMS send throttle test: through {@link ArtemisJmsTemplateHelper}, with a JMS
 * template that records the destinations it sends to instead of reaching a
 * broker.
 */
public class JmsSendThrottleTest {

	/** Destination. */
	private static final String DESTINATION = "throttle/test";

	/** Other destination. */
	private static final String OTHER_DESTINATION = "throttle/test/other";

	/** Throttle interval. */
	private static final Duration INTERVAL = Duration.ofMinutes(1);

	/**
	 * JMS template that records the destinations it sends to, and fails while
	 * told to.
	 */
	private static class RecordingJmsTemplate extends JmsTemplate {

		/** Destinations sent to. */
		private final List<String> sentDestinations = new CopyOnWriteArrayList<>();

		/** If the next sends fail. */
		private boolean failing;

		/**
		 * @see org.springframework.jms.core.JmsTemplate#send(java.lang.String,
		 *      org.springframework.jms.core.MessageCreator)
		 */
		@Override
		public void send(
				final String destinationName,
				final MessageCreator messageCreator) {
			if (this.failing) {
				throw new UncategorizedJmsException("Broker unavailable.");
			}
			this.sentDestinations.add(destinationName);
		}

	}

	/** JMS template. */
	private RecordingJmsTemplate jmsTemplate;

	/** Meter registry. */
	private SimpleMeterRegistry meterRegistry;

	/** JMS template helper. */
	private ArtemisJmsTemplateHelper jmsTemplateHelper;

	/** Creates a fresh helper (and throttle) for each test. */
	@BeforeEach
	public void createHelper() {
		this.jmsTemplate = new RecordingJmsTemplate();
		this.meterRegistry = new SimpleMeterRegistry();
		this.jmsTemplateHelper = new ArtemisJmsTemplateHelper();
		this.jmsTemplateHelper.setSendThrottleMetrics(new MicrometerJmsSendThrottleMetrics(this.meterRegistry));
	}

	/** Ends the transaction a test may have started. */
	@AfterEach
	public void endTransaction() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	/**
	 * Builds a throttled message.
	 *
	 * @param  destination Destination.
	 * @param  key         Throttle key.
	 * @return             The message.
	 */
	private JmsMessage<String> buildMessage(
			final String destination,
			final String key) {
		return new JmsMessage<String>().withDestination(destination).withMessage("message").withThrottleKey(key)
				.withThrottleInterval(JmsSendThrottleTest.INTERVAL);
	}

	/**
	 * Gets the number of skipped sends recorded for a destination.
	 *
	 * @param  destination Destination.
	 * @return             The number of skipped sends.
	 */
	private double getSkippedCount(
			final String destination) {
		return this.meterRegistry.find(MicrometerJmsSendThrottleMetrics.SKIPPED).tag("destination", destination).counters().stream()
				.mapToDouble(counter -> counter.count()).sum();
	}

	/** Starts a transaction (synchronization only, as a transaction manager would). */
	private void startTransaction() {
		TransactionSynchronizationManager.initSynchronization();
		TransactionSynchronizationManager.setActualTransactionActive(true);
	}

	/**
	 * Ends the transaction started with {@link #startTransaction()}.
	 *
	 * @param committed If the transaction committed.
	 */
	private void completeTransaction(
			final boolean committed) {
		final List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
		if (committed) {
			TransactionSynchronizationUtils.invokeAfterCommit(synchronizations);
		}
		TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations,
				(committed ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK));
		this.endTransaction();
	}

	/**
	 * Tests that a throttled key is sent once per interval, per destination, and
	 * that the skipped sends are counted.
	 */
	@Test
	public void testSendsAKeyOncePerIntervalAndDestination() {
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key"));
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key"));
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "other-key"));
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.OTHER_DESTINATION, "key"));

		Assertions.assertEquals(List.of(JmsSendThrottleTest.DESTINATION, JmsSendThrottleTest.DESTINATION, JmsSendThrottleTest.OTHER_DESTINATION),
				this.jmsTemplate.sentDestinations);
		Assertions.assertEquals(1.0, this.getSkippedCount(JmsSendThrottleTest.DESTINATION));
		Assertions.assertEquals(0.0, this.getSkippedCount(JmsSendThrottleTest.OTHER_DESTINATION));
	}

	/**
	 * Tests that a message without a throttle key, or without a positive
	 * interval, is never throttled.
	 */
	@Test
	public void testUnthrottledMessagesAreAlwaysSent() {
		final JmsMessage<String> withoutKey = new JmsMessage<String>().withDestination(JmsSendThrottleTest.DESTINATION).withMessage("message")
				.withThrottleInterval(JmsSendThrottleTest.INTERVAL);
		final JmsMessage<String> withoutInterval = new JmsMessage<String>().withDestination(JmsSendThrottleTest.DESTINATION).withMessage("message")
				.withThrottleKey("key");
		final JmsMessage<String> withZeroInterval = this.buildMessage(JmsSendThrottleTest.DESTINATION, "key").withThrottleInterval(Duration.ZERO);
		for (final JmsMessage<String> message : List.of(withoutKey, withoutKey, withoutInterval, withoutInterval, withZeroInterval, withZeroInterval)) {
			this.jmsTemplateHelper.send(this.jmsTemplate, message);
		}

		Assertions.assertEquals(6, this.jmsTemplate.sentDestinations.size());
		Assertions.assertEquals(0.0, this.getSkippedCount(JmsSendThrottleTest.DESTINATION));
	}

	/**
	 * Tests that a send whose transaction rolls back gives its key back, while a
	 * committed one keeps it.
	 */
	@Test
	public void testRolledBackSendGivesTheKeyBack() {
		this.startTransaction();
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key"));
		this.completeTransaction(false);

		this.startTransaction();
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key"));
		this.completeTransaction(true);

		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key"));

		Assertions.assertEquals(2, this.jmsTemplate.sentDestinations.size());
		Assertions.assertEquals(1.0, this.getSkippedCount(JmsSendThrottleTest.DESTINATION));
	}

	/**
	 * Tests that a failed send gives its key back.
	 */
	@Test
	public void testFailedSendGivesTheKeyBack() {
		this.jmsTemplate.failing = true;
		Assertions.assertThrows(UncategorizedJmsException.class,
				() -> this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key")));
		this.jmsTemplate.failing = false;

		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key"));

		Assertions.assertEquals(List.of(JmsSendThrottleTest.DESTINATION), this.jmsTemplate.sentDestinations);
	}

	/**
	 * Tests that an after-commit message is throttled when it is actually sent,
	 * after the commit, and not sent at all when the transaction rolls back.
	 */
	@Test
	public void testAfterCommitSendIsThrottledOnCommit() {
		this.startTransaction();
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key").withAfterCommit(true));
		Assertions.assertTrue(this.jmsTemplate.sentDestinations.isEmpty());
		this.completeTransaction(false);
		Assertions.assertTrue(this.jmsTemplate.sentDestinations.isEmpty());

		this.startTransaction();
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key").withAfterCommit(true));
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key").withAfterCommit(true));
		this.completeTransaction(true);

		Assertions.assertEquals(List.of(JmsSendThrottleTest.DESTINATION), this.jmsTemplate.sentDestinations);
		Assertions.assertEquals(1.0, this.getSkippedCount(JmsSendThrottleTest.DESTINATION));
	}

}
