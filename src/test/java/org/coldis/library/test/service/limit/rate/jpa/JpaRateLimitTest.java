package org.coldis.library.test.service.limit.rate.jpa;

import java.time.Duration;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.limit.rate.RateLimit;
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
	 * Counts the entries of a rate limit key.
	 *
	 * @param  key Rate limit key.
	 * @return     The number of entries.
	 */
	private Long countEntries(
			final String key) {
		return this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rate_limit WHERE name LIKE '%clean-up%' AND key = ?",
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
				"SELECT limited_until FROM rate_limit WHERE name LIKE '%clean-up%' AND key = 'blocked'", Long.class));

		TestHelper.moveClockBy(Duration.ofSeconds(30));
		this.jpaRateLimiter.cleanExpiredEntries();
		Assertions.assertEquals(1L, this.countEntries("blocked"));

		TestHelper.moveClockBy(Duration.ofSeconds(31));
		this.jpaRateLimiter.cleanExpiredEntries();
		Assertions.assertEquals(0L, this.countEntries("blocked"));
	}

	/**
	 * Tests that the entries of a rate limit this instance has not used are not
	 * removed by the window clean up.
	 */
	@Test
	public void testEntriesOfAnUnusedLimitAreNotCleaned() {
		this.jdbcTemplate.update("INSERT INTO rate_limit (name, key, buckets) VALUES ('not-used', 'other', '{\"1\": 1}')");

		this.jpaRateLimiter.cleanExpiredEntries();

		Assertions.assertEquals(1L,
				this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rate_limit WHERE name = 'not-used'", Long.class));
	}

}
