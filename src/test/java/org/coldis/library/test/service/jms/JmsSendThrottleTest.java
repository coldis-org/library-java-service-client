package org.coldis.library.test.service.jms;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.jms.ArtemisJmsTemplateHelper;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.MicrometerJmsSendThrottleMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.coldis.library.test.TestHelper;
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
	@DisplayName("A throttled key is sent once per interval and per destination")
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
	@DisplayName("A message without a key or a positive interval is always sent")
	public void testUnthrottledMessagesAreAlwaysSent() {
		final JmsMessage<String> withoutKey = new JmsMessage<String>().withDestination(JmsSendThrottleTest.DESTINATION).withMessage("message")
				.withThrottleInterval(JmsSendThrottleTest.INTERVAL);
		final JmsMessage<String> withoutInterval = new JmsMessage<String>().withDestination(JmsSendThrottleTest.DESTINATION).withMessage("message")
				.withThrottleKey("key");
		final JmsMessage<String> withZeroInterval = this.buildMessage(JmsSendThrottleTest.DESTINATION, "key").withThrottleInterval(Duration.ZERO);
		final JmsMessage<String> withNegativeInterval = this.buildMessage(JmsSendThrottleTest.DESTINATION, "key")
				.withThrottleInterval(Duration.ofMinutes(-1));
		for (final JmsMessage<String> message : List.of(withoutKey, withoutKey, withoutInterval, withoutInterval, withZeroInterval, withZeroInterval,
				withNegativeInterval, withNegativeInterval)) {
			this.jmsTemplateHelper.send(this.jmsTemplate, message);
		}

		Assertions.assertEquals(8, this.jmsTemplate.sentDestinations.size());
		Assertions.assertEquals(0.0, this.getSkippedCount(JmsSendThrottleTest.DESTINATION));
	}

	/**
	 * Tests that a transactional send whose transaction rolls back gives its key
	 * back, while a committed one keeps it.
	 */
	@Test
	@DisplayName("A rolled-back transactional send gives its key back; a committed one keeps it")
	public void testRolledBackSendGivesTheKeyBack() {
		this.jmsTemplate.setSessionTransacted(true);
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
	@DisplayName("A failed send gives its key back")
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
	@DisplayName("An after-commit message is throttled when sent, and not sent on rollback")
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

	/**
	 * Tests that a key is sent again once its interval passed, on the wall clock.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A throttled key is sent again once its interval passed")
	public void testSendsAgainAfterTheInterval() throws Exception {
		final Duration shortInterval = Duration.ofMillis(300);
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key").withThrottleInterval(shortInterval));
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key").withThrottleInterval(shortInterval));
		Assertions.assertEquals(1, this.jmsTemplate.sentDestinations.size());

		Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
			this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key").withThrottleInterval(shortInterval));
			return this.jmsTemplate.sentDestinations.size();
		}, sentCount -> sentCount == 2, TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
	}

	/**
	 * Tests that moving the application clock does not change the throttle
	 * window, which runs on the wall clock.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Moving the application clock does not change the throttle window")
	public void testApplicationClockDoesNotChangeTheWindow() throws Exception {
		final Clock regularClock = DateTimeHelper.getClock();
		try {
			this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key"));
			DateTimeHelper.setClock(Clock.offset(regularClock, Duration.ofDays(1)));
			this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key"));
			Assertions.assertEquals(1, this.jmsTemplate.sentDestinations.size());

			// A key sent while the clock is moved is free again after the real interval, not a day later.
			final Duration shortInterval = Duration.ofMillis(300);
			this.jmsTemplateHelper.send(this.jmsTemplate,
					this.buildMessage(JmsSendThrottleTest.DESTINATION, "moved-key").withThrottleInterval(shortInterval));
			DateTimeHelper.setClock(regularClock);
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
				this.jmsTemplateHelper.send(this.jmsTemplate,
						this.buildMessage(JmsSendThrottleTest.DESTINATION, "moved-key").withThrottleInterval(shortInterval));
				return this.jmsTemplate.sentDestinations.size();
			}, sentCount -> sentCount == 3, TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
		finally {
			DateTimeHelper.setClock(regularClock);
		}
	}

	/**
	 * Tests that a send that is not transactional keeps its key when the
	 * transaction rolls back, as the message reached the broker anyway.
	 */
	@Test
	@DisplayName("A send that is not transactional keeps its key when the transaction rolls back")
	public void testNonTransactionalSendKeepsTheKeyOnRollback() {
		this.jmsTemplate.setSessionTransacted(false);
		this.startTransaction();
		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key"));
		this.completeTransaction(false);

		this.jmsTemplateHelper.send(this.jmsTemplate, this.buildMessage(JmsSendThrottleTest.DESTINATION, "key"));

		Assertions.assertEquals(List.of(JmsSendThrottleTest.DESTINATION), this.jmsTemplate.sentDestinations);
		Assertions.assertEquals(1.0, this.getSkippedCount(JmsSendThrottleTest.DESTINATION));
	}

}
