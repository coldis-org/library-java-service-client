package org.coldis.library.service.limit.rate;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Rate limit.
 */
@Documented
@Retention(RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER })
public @interface RateLimit {

	/**
	 * Name for the limit.
	 */
	String name() default "";

	/**
	 * Period (in seconds) for the limit.
	 */
	String period() default "60";

	/**
	 * Backoff period is the period by default.
	 */
	String backoffPeriod() default "-1";

	/**
	 * Limit for the period.
	 */
	String limit();

	/**
	 * Rate limiter bean name.
	 */
	String limiter() default "localRateLimiter";

	/**
	 * Number of local executions before flushing to the central store.
	 */
	String bufferSize() default "5000";

	/**
	 * Maximum duration (in seconds) between flushes to the central store.
	 */
	String bufferDuration() default "60";

	/**
	 * If the exception type should be changed.
	 */
	Class<? extends Exception> errorType() default RateLimitException.class;

	/**
	 * Error messages to be used randomly.
	 */
	String[] randomErrorMessages() default {};

}
