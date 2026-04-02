package org.coldis.library.service.limit.rate;

/**
 * Rate limiter.
 */
public interface RateLimiter {

	/**
	 * Checks if the rate limit has been exceeded and records the execution if not.
	 *
	 * @param  name               Rate limit name.
	 * @param  key                Rate limit key.
	 * @param  config             Rate limit configuration.
	 * @throws RateLimitException If the rate limit has been exceeded.
	 */
	void checkLimit(
			String name,
			String key,
			RateLimitConfig config) throws RateLimitException;

	/**
	 * Cleans up expired rate limit entries.
	 */
	void cleanExpiredEntries();

}
