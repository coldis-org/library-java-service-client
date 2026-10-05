package org.coldis.library.test.service.limit.rate.jpa;

import java.time.Duration;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.limit.rate.RateLimit;
import org.coldis.library.service.limit.rate.RateLimitException;
import org.coldis.library.service.limit.rate.RateLimitKey;
import org.coldis.library.service.limit.rate.RateLimits;
import org.coldis.library.service.limit.rate.jpa.JpaRateLimiter;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.service.limit.rate.AbstractRateLimitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JPA rate limit test.
 */
public class JpaRateLimitTest extends AbstractRateLimitTest {

	/**
	 * JDBC template.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * Central rate limiter.
	 */
	@Autowired
	@Qualifier("jpaRateLimiter")
	private JpaRateLimiter jpaRateLimiter;

	/**
	 * Cleans up JPA rate limit state.
	 */
	@BeforeEach
	void cleanUp() {
		this.jdbcTemplate.execute("DELETE FROM rate_limit");
		this.jpaRateLimiter.clearBuffers();
	}

	/**
	 * @see AbstractRateLimitTest#rateLimit1()
	 */
	@Override
	@RateLimit(
			limit = "100",
			period = "1",
			limiter = "jpaRateLimiter",
			bufferSize = "10",
			bufferDuration = "1",
			errorType = BusinessException.class,
			randomErrorMessages = { "Error 1", "Error 2" }
	)
	protected void rateLimit1() {
	}

	/**
	 * @see AbstractRateLimitTest#rateLimit2()
	 */
	@Override
	@RateLimits(
			limits = { @RateLimit(
					limit = "100",
					period = "1",
					limiter = "jpaRateLimiter",
					bufferSize = "10",
					bufferDuration = "1"
			), @RateLimit(
					limit = "200",
					period = "3",
					limiter = "jpaRateLimiter",
					bufferSize = "10",
					bufferDuration = "1",
					errorType = Exception.class,
					randomErrorMessages = { "Error 3", "Error 4" }
			) }
	)
	protected void rateLimit2() {
	}

	/**
	 * @see AbstractRateLimitTest#rateLimitWithKey1(String)
	 */
	@Override
	@RateLimit(
			limit = "100",
			period = "1",
			limiter = "jpaRateLimiter",
			bufferSize = "10",
			bufferDuration = "1",
			errorType = IntegrationException.class,
			randomErrorMessages = { "Error 1", "Error 6" }
	)
	protected void rateLimitWithKey1(
			@RateLimitKey
			final String key) {
	}

	/**
	 * @see AbstractRateLimitTest#rateLimitWithKey2(String, String)
	 */
	@Override
	@RateLimits(
			limits = { @RateLimit(
					limit = "100",
					period = "1",
					limiter = "jpaRateLimiter",
					bufferSize = "10",
					bufferDuration = "1"
			), @RateLimit(
					limit = "200",
					period = "3",
					limiter = "jpaRateLimiter",
					bufferSize = "10",
					bufferDuration = "1",
					errorType = BusinessException.class,
					randomErrorMessages = { "Error 7", "Error 8" }
			) }
	)
	protected void rateLimitWithKey2(
			@RateLimitKey
			final String key,
			final String arg) {
	}

	/**
	 * Rate limited method used by the clean up tests (limit=2, period=60s).
	 *
	 * @param key Rate limit key.
	 */
	@RateLimit(
			name = "clean-up",
			limit = "2",
			period = "60",
			limiter = "jpaRateLimiter",
			bufferSize = "1"
	)
	protected void cleanUpLimit(
			@RateLimitKey
			final String key) {
	}

	/**
	 * Rate limited method used by the clean up tests that keep executions in the
	 * local buffer (limit=100, period=60s, bufferSize=10).
	 *
	 * @param key Rate limit key.
	 */
	@RateLimit(
			name = "clean-up-buffered",
			limit = "100",
			period = "60",
			limiter = "jpaRateLimiter",
			bufferSize = "10"
	)
	protected void bufferedCleanUpLimit(
			@RateLimitKey
			final String key) {
	}

	/**
	 * Rate limited method sharing its name with {@link #longSharedLimit(String)}
	 * under a shorter period (limit=100, period=60s).
	 *
	 * @param key Rate limit key.
	 */
	@RateLimit(
			name = "clean-up-shared",
			limit = "100",
			period = "60",
			limiter = "jpaRateLimiter",
			bufferSize = "1"
	)
	protected void shortSharedLimit(
			@RateLimitKey
			final String key) {
	}

	/**
	 * Rate limited method sharing its name with {@link #shortSharedLimit(String)}
	 * under a longer period (limit=100, period=300s).
	 *
	 * @param key Rate limit key.
	 */
	@RateLimit(
			name = "clean-up-shared",
			limit = "100",
			period = "300",
			limiter = "jpaRateLimiter",
			bufferSize = "1"
	)
	protected void longSharedLimit(
			@RateLimitKey
			final String key) {
	}

	/**
	 * Gets the stored name of a rate limit entry.
	 *
	 * @param  key Rate limit key.
	 * @return     The stored name.
	 */
	private String storedName(
			final String key) {
		return this.jdbcTemplate.queryForObject("SELECT name FROM rate_limit WHERE name LIKE '%clean-up' AND key = ?", String.class,
				key);
	}

	/**
	 * Counts the entries of a rate limit key.
	 *
	 * @param  key Rate limit key.
	 * @return     The number of entries.
	 */
	private Long countEntries(
			final String key) {
		return this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rate_limit WHERE name LIKE '%clean-up' AND key = ?",
				Long.class, key);
	}

	/**
	 * Tests that an entry that was never blocked is kept inside its window, and is
	 * removed from the database and from the local buffer once the window has
	 * elapsed.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testIdleEntryIsCleanedAfterTheWindow() throws Exception {
		this.cleanUpLimit("idle");
		this.jpaRateLimiter.flushAllBuffers();
		Assertions.assertEquals(1L, this.countEntries("idle"));

		TestHelper.moveClockBy(Duration.ofSeconds(30));
		this.jpaRateLimiter.cleanExpiredEntries();
		Assertions.assertEquals(1L, this.countEntries("idle"));
		Assertions.assertEquals(1, this.jpaRateLimiter.getBufferedEntryCount());

		TestHelper.moveClockBy(Duration.ofSeconds(31));
		this.jpaRateLimiter.cleanExpiredEntries();
		Assertions.assertEquals(0L, this.countEntries("idle"));
		Assertions.assertEquals(0, this.jpaRateLimiter.getBufferedEntryCount());
	}

	/**
	 * Tests that a blocked entry is kept until its block expires, even though the
	 * block cleared its buckets.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testBlockedEntryIsKeptUntilTheBlockExpires() throws Exception {
		this.cleanUpLimit("blocked");
		this.cleanUpLimit("blocked");
		Assertions.assertThrows(Exception.class, () -> this.cleanUpLimit("blocked"));
		Assertions.assertNotNull(this.jdbcTemplate.queryForObject(
				"SELECT limited_until FROM rate_limit WHERE name LIKE '%clean-up' AND key = 'blocked'", Long.class));

		TestHelper.moveClockBy(Duration.ofSeconds(30));
		this.jpaRateLimiter.cleanExpiredEntries();
		Assertions.assertEquals(1L, this.countEntries("blocked"));
		Assertions.assertEquals(1, this.jpaRateLimiter.getBufferedEntryCount());

		TestHelper.moveClockBy(Duration.ofSeconds(31));
		this.jpaRateLimiter.cleanExpiredEntries();
		Assertions.assertEquals(0L, this.countEntries("blocked"));
		Assertions.assertEquals(0, this.jpaRateLimiter.getBufferedEntryCount());
	}

	/**
	 * Tests that the window clean up removes the entries of the rate limits this
	 * instance has used and leaves the entries of any other rate limit.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testEntriesOfAnUnusedLimitAreNotCleaned() throws Exception {
		this.cleanUpLimit("used");
		this.jpaRateLimiter.flushAllBuffers();
		this.jdbcTemplate.update("INSERT INTO rate_limit (name, key, buckets) VALUES ('not-used', 'other', '{\"1\": 1}')");

		TestHelper.moveClockBy(Duration.ofSeconds(61));
		this.jpaRateLimiter.cleanExpiredEntries();

		Assertions.assertEquals(0L, this.countEntries("used"));
		Assertions.assertEquals(1L,
				this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rate_limit WHERE name = 'not-used'", Long.class));
	}

	/**
	 * Tests that a state with executions not yet flushed is kept by the clean up
	 * once its window has elapsed, and that its flush still reaches the database.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testUnflushedStateIsKeptByTheCleanUp() throws Exception {
		this.bufferedCleanUpLimit("unflushed");
		this.bufferedCleanUpLimit("unflushed");

		TestHelper.moveClockBy(Duration.ofSeconds(61));
		this.jpaRateLimiter.cleanExpiredEntries();
		Assertions.assertEquals(1, this.jpaRateLimiter.getBufferedEntryCount());

		this.jpaRateLimiter.flushAllBuffers();
		Assertions.assertEquals(1L, this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM rate_limit WHERE name LIKE '%clean-up-buffered' AND key = 'unflushed'", Long.class));
	}

	/**
	 * Tests that a key checked again after its state was evicted reads the
	 * database first, so a block set by another instance meanwhile is enforced.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testReturningKeyReadsABlockSetElsewhere() throws Exception {
		this.cleanUpLimit("returning");
		this.jpaRateLimiter.flushAllBuffers();
		final String name = this.storedName("returning");
		TestHelper.moveClockBy(Duration.ofSeconds(61));
		this.jpaRateLimiter.cleanExpiredEntries();
		Assertions.assertEquals(0, this.jpaRateLimiter.getBufferedEntryCount());

		this.jdbcTemplate.update("INSERT INTO rate_limit (name, key, buckets, limited_until) VALUES (?, 'returning', '{}', ?)", name,
				DateTimeHelper.getClock().millis() + 60000L);

		Assertions.assertThrows(RateLimitException.class, () -> this.cleanUpLimit("returning"));
	}

	/**
	 * Tests that a rate limit name used with two periods is left out of the
	 * window clean up, so an entry inside the longer window is kept.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testNameWithTwoPeriodsIsNotCleaned() throws Exception {
		this.shortSharedLimit("short");
		this.longSharedLimit("long");
		this.jpaRateLimiter.flushAllBuffers();

		TestHelper.moveClockBy(Duration.ofSeconds(61));
		this.jpaRateLimiter.cleanExpiredEntries();

		Assertions.assertEquals(2L, this.jdbcTemplate
				.queryForObject("SELECT COUNT(*) FROM rate_limit WHERE name LIKE '%clean-up-shared' AND key IN ('long', 'short')", Long.class));
	}

	/**
	 * Tests that an entry whose buckets are not a JSON object is kept, and does not
	 * stop the clean up of the other entries of its rate limit.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testNonObjectBucketsAreKeptAndDoNotStopTheCleanUp() throws Exception {
		this.cleanUpLimit("object");
		this.jpaRateLimiter.flushAllBuffers();
		this.jdbcTemplate.update("INSERT INTO rate_limit (name, key, buckets) VALUES (?, 'array', '[]')", this.storedName("object"));

		TestHelper.moveClockBy(Duration.ofSeconds(61));
		this.jpaRateLimiter.cleanExpiredEntries();

		Assertions.assertEquals(0L, this.countEntries("object"));
		Assertions.assertEquals(1L, this.countEntries("array"));
	}

}
