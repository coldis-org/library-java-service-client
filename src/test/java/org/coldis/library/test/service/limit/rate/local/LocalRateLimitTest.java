package org.coldis.library.test.service.limit.rate.local;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.limit.rate.RateLimit;
import org.coldis.library.service.limit.rate.RateLimitKey;
import org.coldis.library.service.limit.rate.RateLimits;
import org.coldis.library.service.limit.rate.local.LocalRateLimiter;
import org.coldis.library.test.service.limit.rate.AbstractRateLimitTest;
import org.junit.jupiter.api.BeforeEach;

/**
 * Local rate limit test.
 */
public class LocalRateLimitTest extends AbstractRateLimitTest {

	/**
	 * Cleans up local rate limit state.
	 */
	@BeforeEach
	void cleanUp() {
		LocalRateLimiter.clearExecutions();
	}

	/**
	 * @see AbstractRateLimitTest#rateLimit1()
	 */
	@Override
	@RateLimit(
			limit = "100",
			period = "1",
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
					period = "1"
			), @RateLimit(
					limit = "200",
					period = "3",
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
					period = "1"
			), @RateLimit(
					limit = "200",
					period = "3",
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
