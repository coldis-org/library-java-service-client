package org.coldis.library.test.service.limit.rate;

import java.util.List;
import java.util.Objects;
import java.util.Random;

import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

/**
 * Abstract rate limit test. Subclasses provide the annotated methods with the
 * appropriate limiter configuration.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractRateLimitTest {

	/**
	 * Redis container.
	 */
	public static GenericContainer<?> REDIS_CONTAINER = TestHelper.createRedisContainer();

	/**
	 * Postgres container.
	 */
	public static GenericContainer<?> POSTGRES_CONTAINER = TestHelper.createPostgresContainer();

	/**
	 * Artemis container.
	 */
	public static GenericContainer<?> ARTEMIS_CONTAINER = TestHelper.createArtemisContainer();

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRateLimitTest.class);

	/**
	 * Rate limited method 1 (limit=100, period=1s).
	 */
	protected abstract void rateLimit1() throws Exception;

	/**
	 * Rate limited method 2 (limit=100/1s + limit=200/3s).
	 */
	protected abstract void rateLimit2() throws Exception;

	/**
	 * Rate limited method with key 1 (limit=100, period=1s).
	 *
	 * @param key Rate limit key.
	 */
	protected abstract void rateLimitWithKey1(String key) throws Exception;

	/**
	 * Rate limited method with key 2 (limit=100/1s + limit=200/3s).
	 *
	 * @param key Rate limit key.
	 * @param arg Extra argument.
	 */
	protected abstract void rateLimitWithKey2(String key, String arg) throws Exception;

	/**
	 * Tests the rate limit.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testRateLimit() throws Exception {
		// Runs 100 calls for the limited methods.
		for (Integer count = 1; count <= 100; count++) {
			this.rateLimit1();
			this.rateLimit2();
			AbstractRateLimitTest.LOGGER.debug(count.toString());
		}

		// The next call should pass the limits.
		Assertions.assertThrows(Exception.class, () -> this.rateLimit1());
		Assertions.assertThrows(Exception.class, () -> this.rateLimit2());

		// Waits the period and try again.
		Thread.sleep(1 * 1000);
		for (Integer count = 1; count <= 100; count++) {
			this.rateLimit1();
			this.rateLimit2();
		}

		// The next call should pass the limits.
		Assertions.assertThrows(Exception.class, () -> this.rateLimit1());

		// Waits the period and try again.
		Thread.sleep(1 * 1000);
		for (Integer count = 1; count <= 100; count++) {
			this.rateLimit1();
			Assertions.assertThrows(Exception.class, () -> this.rateLimit2());
		}

		// Waits the period and try again.
		Thread.sleep(1 * 1000);
		for (Integer count = 1; count <= 100; count++) {
			this.rateLimit1();
			this.rateLimit2();
		}

	}

	/**
	 * Tests the rate limit with key.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testRateLimitWithKey() throws Exception {
		final Random random = new Random();
		final List<String> keys = List.of("key1", "key2");

		// Runs 100 calls for the limited methods.
		for (final String key : keys) {
			for (Integer count = 1; count <= 100; count++) {
				this.rateLimitWithKey1(key);
				this.rateLimitWithKey2(key, Objects.toString(random.nextInt()));
				AbstractRateLimitTest.LOGGER.debug(count.toString());
			}
		}

		// The next call should pass the limits.
		for (final String key : keys) {
			Assertions.assertThrows(Exception.class, () -> this.rateLimitWithKey1(key));
			Assertions.assertThrows(Exception.class, () -> this.rateLimitWithKey2(key, Objects.toString(random.nextInt())));
		}

		// Waits the period and try again.
		Thread.sleep(1 * 1000);
		for (final String key : keys) {
			for (Integer count = 1; count <= 100; count++) {
				this.rateLimitWithKey1(key);
				this.rateLimitWithKey2(key, Objects.toString(random.nextInt()));
			}
		}

		// The next call should pass the limits.
		for (final String key : keys) {
			Assertions.assertThrows(Exception.class, () -> this.rateLimitWithKey1(key));
		}

		// Waits the period and try again.
		Thread.sleep(1 * 1000);
		for (final String key : keys) {
			for (Integer count = 1; count <= 100; count++) {
				this.rateLimitWithKey1(key);
				Assertions.assertThrows(Exception.class, () -> this.rateLimitWithKey2(key, Objects.toString(random.nextInt())));
			}
		}

		// Waits the period and try again.
		Thread.sleep(1 * 1000);
		for (final String key : keys) {
			for (Integer count = 1; count <= 100; count++) {
				this.rateLimitWithKey1(key);
				this.rateLimitWithKey2(key, Objects.toString(random.nextInt()));
			}
		}

	}

}
