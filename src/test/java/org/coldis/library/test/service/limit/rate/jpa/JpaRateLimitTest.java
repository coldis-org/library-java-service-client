package org.coldis.library.test.service.limit.rate.jpa;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.limit.rate.RateLimit;
import org.coldis.library.service.limit.rate.RateLimitKey;
import org.coldis.library.service.limit.rate.RateLimits;
import org.coldis.library.service.limit.rate.jpa.JpaRateLimiter;
import org.coldis.library.test.service.limit.rate.AbstractRateLimitTest;
import org.junit.jupiter.api.BeforeEach;
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
		this.jpaRateLimiter.buffers.clear();
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

}
