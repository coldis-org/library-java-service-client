package org.coldis.library.test.service.limit.rate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.limit.rate.RateLimitException;
import org.coldis.library.service.limit.rate.RateLimitStats;
import org.coldis.library.test.TestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitStats} timing behavior. These drive the
 * testable clock directly so the backoff window math is fully deterministic,
 * exercising cases the container-based integration tests cannot (bursts that
 * land partway through the window).
 */
public class RateLimitStatsTest {

	/**
	 * Pins the test clock to a fixed instant so only explicit moveClockBy calls
	 * advance time.
	 */
	@BeforeEach
	public void freezeClock() {
		DateTimeHelper.setClock(Clock.fixed(Instant.now(), ZoneOffset.UTC));
	}

	/**
	 * Resets the test clock.
	 */
	@AfterEach
	public void cleanClock() {
		TestHelper.cleanClock();
	}

	/**
	 * Records the given number of executions at the current clock time.
	 *
	 * @param  stats              Stats.
	 * @param  count              Number of executions.
	 * @throws RateLimitException If the limit is reached.
	 */
	private void record(
			final RateLimitStats stats,
			final int count) throws RateLimitException {
		for (int idx = 0; idx < count; idx++) {
			stats.checkLimit("test");
		}
	}

	/**
	 * The backoff must be anchored to the moment the limit is breached (the burst
	 * time), not to the start of the sliding window. With a burst that lands late
	 * in the window, the old period-start anchor released the caller far too
	 * early.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testBackoffAnchoredToBurstTimeNotPeriodStart() throws Exception {
		final RateLimitStats stats = new RateLimitStats();
		stats.setLimit(5L);
		stats.setPeriod(Duration.ofSeconds(10));
		stats.setBackoffPeriod(Duration.ofSeconds(10));
		stats.setBucketDuration(Duration.ofSeconds(1));
		// Isolates the anchor behavior from the reset-on-block behavior.
		stats.setResetOnBlock(false);

		final long base = DateTimeHelper.getClock().millis();

		// One early execution opens the window's oldest bucket.
		this.record(stats, 1);

		// The burst that actually breaches the limit happens 8s later.
		TestHelper.moveClockBy(Duration.ofSeconds(8));
		this.record(stats, 4);

		// The next call is blocked, and the block must last the full backoff from
		// the burst time (base + 8s + 10s), NOT from the oldest bucket (base + 10s).
		Assertions.assertThrows(RateLimitException.class, () -> stats.checkLimit("test"));
		Assertions.assertEquals(base + Duration.ofSeconds(18).toMillis(), stats.getLimitedUntil());

		// At t=17s the caller is still blocked. Under the old period-start anchor
		// the block would have expired at t=10s and this call would have passed.
		TestHelper.moveClockBy(Duration.ofSeconds(9));
		Assertions.assertThrows(RateLimitException.class, () -> stats.checkLimit("test"));

		// Just past t=18s the backoff has elapsed and the caller is released.
		TestHelper.moveClockBy(Duration.ofMillis(1001));
		Assertions.assertDoesNotThrow(() -> stats.checkLimit("test"));
	}

	/**
	 * With resetOnBlock=true (the default), hitting the limit clears the window so
	 * the caller is released cleanly once the backoff elapses, even when the
	 * backoff is shorter than the period.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testResetOnBlockReleasesAfterBackoff() throws Exception {
		final RateLimitStats stats = new RateLimitStats();
		stats.setLimit(5L);
		stats.setPeriod(Duration.ofSeconds(10));
		stats.setBackoffPeriod(Duration.ofSeconds(2));
		stats.setBucketDuration(Duration.ofSeconds(1));
		stats.setResetOnBlock(true);

		this.record(stats, 5);
		Assertions.assertThrows(RateLimitException.class, () -> stats.checkLimit("test"));

		// The backoff (2s) is shorter than the period (10s), but because the window
		// was cleared on block the caller is released as soon as the backoff ends.
		TestHelper.moveClockBy(Duration.ofMillis(2001));
		Assertions.assertDoesNotThrow(() -> stats.checkLimit("test"));
	}

	/**
	 * With resetOnBlock=false, lingering counts inside the sliding window re-block
	 * the caller after the backoff elapses, until the window finally drains. This
	 * is the behavior that resetOnBlock=true avoids.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testNoResetOnBlockReBlocksUntilWindowDrains() throws Exception {
		final RateLimitStats stats = new RateLimitStats();
		stats.setLimit(5L);
		stats.setPeriod(Duration.ofSeconds(10));
		stats.setBackoffPeriod(Duration.ofSeconds(2));
		stats.setBucketDuration(Duration.ofSeconds(1));
		stats.setResetOnBlock(false);

		final long base = DateTimeHelper.getClock().millis();

		this.record(stats, 5);
		Assertions.assertThrows(RateLimitException.class, () -> stats.checkLimit("test"));

		// After the 2s backoff the window still holds the 5 counts (period is 10s),
		// so the caller is re-blocked for another backoff.
		TestHelper.moveClockBy(Duration.ofMillis(2001));
		Assertions.assertThrows(RateLimitException.class, () -> stats.checkLimit("test"));
		Assertions.assertEquals(base + 2001 + Duration.ofSeconds(2).toMillis(), stats.getLimitedUntil());

		// Once the window has fully drained (past the 10s period) the caller is
		// finally released.
		TestHelper.moveClockBy(Duration.ofMillis(8000));
		Assertions.assertDoesNotThrow(() -> stats.checkLimit("test"));
	}

}
