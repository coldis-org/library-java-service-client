package org.coldis.library.test.service.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.ServiceClientConcurrencyLimiter;
import org.coldis.library.service.client.ServiceClientMetrics;
import org.coldis.library.service.client.ServiceClientTarget;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Concurrency limiter test (plain objects, with a clock the test moves).
 */
public class ServiceClientConcurrencyLimiterTest {

	/**
	 * Target of the tests.
	 */
	private static final String TARGET = "LimitedTestClient";

	/**
	 * Settings of the tests: the shipped defaults, with no log line.
	 */
	private static final ServiceClientConcurrencyLimiter.Settings SETTINGS = new ServiceClientConcurrencyLimiter.Settings(
			ServiceClientConcurrencyLimiter.Mode.OBSERVE, 10, 256, 20, Duration.ofSeconds(1), 10, 3.0, Duration.ofMinutes(10), List.of(429, 503), 2.0,
			Duration.ZERO);

	/**
	 * Clock of the tests (nanoseconds).
	 */
	private final AtomicLong clock = new AtomicLong(TimeUnit.HOURS.toNanos(1000));

	/**
	 * Creates a limiter on the test clock.
	 *
	 * @param  mode     Mode.
	 * @param  initial  Initial limit.
	 * @param  riseTime Baseline rise time.
	 * @return          The limiter.
	 */
	private ServiceClientConcurrencyLimiter createLimiter(
			final ServiceClientConcurrencyLimiter.Mode mode,
			final int initial,
			final Duration riseTime) {
		final ServiceClientConcurrencyLimiter.Settings settings = ServiceClientConcurrencyLimiterTest.SETTINGS;
		return new ServiceClientConcurrencyLimiter(
				new ServiceClientConcurrencyLimiter.Settings(mode, settings.min(), settings.max(), initial, settings.window(), settings.minSamples(),
						settings.latencyTolerance(), riseTime, settings.overloadStatuses(), settings.suggestionMargin(), settings.suggestionLogInterval()),
				null, ServiceClientMetrics.NONE, this.clock::get);
	}

	/**
	 * Runs one window of a target: batches of operations in flight together, each
	 * with one attempt of the given latency; the clock moves to the window end
	 * before the last batch ends, so the window closes with all of them.
	 *
	 * @param limiter       Limiter.
	 * @param batches       Batches.
	 * @param concurrent    Operations per batch (in flight together).
	 * @param latencyMillis Latency of each attempt (milliseconds).
	 * @param isOverload    If each attempt is an overload outcome.
	 */
	private void runWindow(
			final ServiceClientConcurrencyLimiter limiter,
			final int batches,
			final int concurrent,
			final long latencyMillis,
			final boolean isOverload) {
		for (int batchIndex = 0; batchIndex < batches; batchIndex++) {
			final List<ServiceClientConcurrencyLimiter.Permit> permits = new ArrayList<>();
			for (int operationIndex = 0; operationIndex < concurrent; operationIndex++) {
				permits.add(limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET));
			}
			permits.forEach(permit -> limiter.recordAttempt(permit, TimeUnit.MILLISECONDS.toNanos(latencyMillis), true, isOverload));
			if (batchIndex == (batches - 1)) {
				this.clock.addAndGet(ServiceClientConcurrencyLimiterTest.SETTINGS.window().toNanos());
			}
			permits.forEach(limiter::release);
		}
	}

	/**
	 * Runs windows of 10 sequential operations of a latency.
	 *
	 * @param limiter       Limiter.
	 * @param windows       Windows.
	 * @param latencyMillis Latency of each attempt (milliseconds).
	 */
	private void runWindows(
			final ServiceClientConcurrencyLimiter limiter,
			final int windows,
			final long latencyMillis) {
		for (int windowIndex = 0; windowIndex < windows; windowIndex++) {
			this.runWindow(limiter, 10, 1, latencyMillis, false);
		}
	}

	/**
	 * Gets the snapshot of the test target.
	 *
	 * @param  limiter Limiter.
	 * @return         The snapshot of the test target.
	 */
	private static ServiceClientConcurrencyLimiter.Snapshot getSnapshot(
			final ServiceClientConcurrencyLimiter limiter) {
		return limiter.getSnapshot(ServiceClientConcurrencyLimiterTest.TARGET).orElseThrow();
	}

	/**
	 * A target going from 70 ms to 4 s halves its limit every window, down to the
	 * minimum; back to 70 ms, it grows back by the square root of the limit per
	 * window while in flight is at half the limit or more.
	 */
	@Test
	public void testOverloadHalvesToMinThenRecovers() {
		final ServiceClientConcurrencyLimiter limiter = this.createLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE, 256, Duration.ofMinutes(10));
		this.runWindows(limiter, ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS, 70);
		Assertions.assertEquals(256, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).limit());
		Assertions.assertEquals(70, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).baselineLatency().toMillis());
		final List<Integer> overloadedLimits = new ArrayList<>();
		for (int windowIndex = 0; windowIndex < 6; windowIndex++) {
			this.runWindows(limiter, 1, 4000);
			overloadedLimits.add(ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).limit());
			Assertions.assertTrue(ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).isOverloaded());
		}
		Assertions.assertEquals(List.of(128, 64, 32, 16, 10, 10), overloadedLimits);
		Assertions.assertEquals(6, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).overloadedTime().toSeconds());
		final List<Integer> recoveredLimits = new ArrayList<>();
		for (int windowIndex = 0; windowIndex < 4; windowIndex++) {
			final int limit = ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).limit();
			this.runWindow(limiter, 2, (limit + 1) / 2, 70, false);
			recoveredLimits.add(ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).limit());
			Assertions.assertFalse(ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).isOverloaded());
		}
		Assertions.assertEquals(List.of(14, 18, 23, 28), recoveredLimits);
	}

	/**
	 * A healthy target is never over its limit: a burst over it raises the limit
	 * to the in-flight count (up to the maximum), even in enforce mode.
	 */
	@Test
	public void testHealthyBurstNeverOverLimit() {
		final ServiceClientConcurrencyLimiter limiter = this.createLimiter(ServiceClientConcurrencyLimiter.Mode.ENFORCE, 20, Duration.ofMinutes(10));
		this.runWindows(limiter, ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS + 1, 70);
		final List<ServiceClientConcurrencyLimiter.Permit> permits = new ArrayList<>();
		for (int operationIndex = 0; operationIndex < 100; operationIndex++) {
			permits.add(limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET));
		}
		Assertions.assertEquals(100, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).limit());
		for (int operationIndex = 0; operationIndex < 200; operationIndex++) {
			permits.add(limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET));
		}
		final ServiceClientConcurrencyLimiter.Snapshot snapshot = ServiceClientConcurrencyLimiterTest.getSnapshot(limiter);
		Assertions.assertEquals(256, snapshot.limit());
		Assertions.assertEquals(300, snapshot.inFlight());
		Assertions.assertEquals(0, snapshot.overLimitCalls());
		Assertions.assertEquals(0, snapshot.rejectedCalls());
		permits.forEach(limiter::release);
		Assertions.assertEquals(0, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).inFlight());
	}

	/**
	 * A target that gets slower and stays so is overloaded at first, then becomes
	 * the new normal as its baseline rises (about the rise time): 10x slower is
	 * still overloaded after 15% of the rise time, and healthy after one rise time.
	 */
	@Test
	public void testSlowRiseToleratedAfterRiseTime() {
		final ServiceClientConcurrencyLimiter limiter = this.createLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE, 20, Duration.ofSeconds(100));
		this.runWindows(limiter, ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS, 70);
		this.runWindows(limiter, 1, 700);
		Assertions.assertTrue(ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).isOverloaded());
		this.runWindows(limiter, 14, 700);
		Assertions.assertTrue(ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).isOverloaded());
		this.runWindows(limiter, 85, 700);
		Assertions.assertFalse(ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).isOverloaded());
	}

	/**
	 * During the warm-up (first windows), nothing is overloaded or over the limit,
	 * even in enforce mode, and the limit does not move; the first window after
	 * it at 4 s is overloaded.
	 */
	@Test
	public void testWarmUp() {
		final ServiceClientConcurrencyLimiter limiter = this.createLimiter(ServiceClientConcurrencyLimiter.Mode.ENFORCE, 20, Duration.ofMinutes(10));
		this.runWindows(limiter, 1, 70);
		this.runWindows(limiter, ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS - 2, 4000);
		final List<ServiceClientConcurrencyLimiter.Permit> permits = new ArrayList<>();
		for (int operationIndex = 0; operationIndex < 50; operationIndex++) {
			permits.add(limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET));
		}
		permits.forEach(limiter::release);
		ServiceClientConcurrencyLimiter.Snapshot snapshot = ServiceClientConcurrencyLimiterTest.getSnapshot(limiter);
		Assertions.assertFalse(snapshot.isOverloaded());
		Assertions.assertEquals(20, snapshot.limit());
		Assertions.assertEquals(0, snapshot.overLimitCalls() + snapshot.rejectedCalls());
		this.runWindows(limiter, 1, 4000);
		Assertions.assertFalse(ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).isOverloaded());
		Assertions.assertEquals(ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).windows());
		this.runWindows(limiter, 1, 4000);
		snapshot = ServiceClientConcurrencyLimiterTest.getSnapshot(limiter);
		Assertions.assertTrue(snapshot.isOverloaded());
		Assertions.assertEquals(10, snapshot.limit());
	}

	/**
	 * With low traffic, a window stays open until it has the minimum samples,
	 * however long it takes.
	 */
	@Test
	public void testLowTraffic() {
		final ServiceClientConcurrencyLimiter limiter = this.createLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE, 20, Duration.ofMinutes(10));
		for (int operationIndex = 0; operationIndex < 9; operationIndex++) {
			this.clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
			this.runWindow(limiter, 1, 1, 70, false);
		}
		Assertions.assertEquals(0, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).windows());
		this.runWindow(limiter, 1, 1, 70, false);
		Assertions.assertEquals(1, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).windows());
	}

	/**
	 * A window kept open by low traffic (one call a minute) and overloaded
	 * charges one window of time as overloaded, not its idle minutes, and moves
	 * the baseline as one window would.
	 */
	@Test
	public void testLowTrafficOverloadedTime() {
		final ServiceClientConcurrencyLimiter limiter = this.createLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE, 20, Duration.ofMinutes(10));
		this.runWindows(limiter, ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS, 70);
		for (int operationIndex = 0; operationIndex < 10; operationIndex++) {
			this.clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
			final ServiceClientConcurrencyLimiter.Permit permit = limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET);
			limiter.recordAttempt(permit, TimeUnit.MILLISECONDS.toNanos(operationIndex == 0 ? 9000 : 70), true, operationIndex == 0);
			limiter.release(permit);
		}
		final ServiceClientConcurrencyLimiter.Snapshot snapshot = ServiceClientConcurrencyLimiterTest.getSnapshot(limiter);
		Assertions.assertTrue(snapshot.isOverloaded());
		Assertions.assertEquals(1, snapshot.overloadedTime().toSeconds());
		// (9000 + 9 x 70) / 10 = 963 ms; one window of a 10 min rise: + 893 x (1 - e^(-1/600)).
		Assertions.assertEquals(71, snapshot.baselineLatency().toMillis());
	}

	/**
	 * Clearing forgets what a target learnt (limit, overload, windows, baseline),
	 * and keeps the operations in flight and the counters.
	 */
	@Test
	public void testClear() {
		final ServiceClientConcurrencyLimiter limiter = this.createLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE, 20, Duration.ofMinutes(10));
		this.runWindows(limiter, ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS, 70);
		this.runWindows(limiter, 1, 4000);
		final ServiceClientConcurrencyLimiter.Permit permit = limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET);
		limiter.clear();
		final ServiceClientConcurrencyLimiter.Snapshot snapshot = ServiceClientConcurrencyLimiterTest.getSnapshot(limiter);
		Assertions.assertFalse(snapshot.isOverloaded());
		Assertions.assertEquals(20, snapshot.limit());
		Assertions.assertEquals(0, snapshot.windows());
		Assertions.assertEquals(Duration.ZERO, snapshot.baselineLatency());
		Assertions.assertEquals(1, snapshot.inFlight());
		Assertions.assertEquals(1, snapshot.overloadedTime().toSeconds());
		limiter.release(permit);
		Assertions.assertEquals(0, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).inFlight());
	}

	/**
	 * At least 10% overload outcomes (here a wait for a pooled connection, with no
	 * latency sample) make a window overloaded, even with a normal latency.
	 */
	@Test
	public void testOverloadOutcomes() {
		final ServiceClientConcurrencyLimiter limiter = this.createLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE, 20, Duration.ofMinutes(10));
		this.runWindows(limiter, ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS, 70);
		final ServiceClientConcurrencyLimiter.Permit permit = limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET);
		limiter.recordAttempt(permit, TimeUnit.SECONDS.toNanos(5), false, true);
		limiter.release(permit);
		this.runWindow(limiter, 9, 1, 70, false);
		final ServiceClientConcurrencyLimiter.Snapshot snapshot = ServiceClientConcurrencyLimiterTest.getSnapshot(limiter);
		Assertions.assertTrue(snapshot.isOverloaded());
		Assertions.assertEquals(18, snapshot.limit());
		Assertions.assertEquals(70, snapshot.currentLatency().toMillis());
	}

	/**
	 * Overloaded and over the limit: enforce rejects (503, retry in 1 s, the
	 * permit not kept), observe counts it and lets it pass; the minimum always
	 * passes.
	 */
	@Test
	public void testEnforceAndObserveOverLimit() {
		for (final ServiceClientConcurrencyLimiter.Mode mode : List.of(ServiceClientConcurrencyLimiter.Mode.ENFORCE,
				ServiceClientConcurrencyLimiter.Mode.OBSERVE)) {
			final ServiceClientConcurrencyLimiter limiter = this.createLimiter(mode, 20, Duration.ofMinutes(10));
			this.runWindows(limiter, ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS, 70);
			this.runWindows(limiter, 1, 4000);
			Assertions.assertEquals(10, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).limit());
			final List<ServiceClientConcurrencyLimiter.Permit> permits = new ArrayList<>();
			for (int operationIndex = 0; operationIndex < 10; operationIndex++) {
				permits.add(limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET));
			}
			if (mode == ServiceClientConcurrencyLimiter.Mode.ENFORCE) {
				final IntegrationException rejection = Assertions.assertThrows(IntegrationException.class,
						() -> limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET));
				Assertions.assertTrue(ServiceClientConcurrencyLimiter.isRejection(rejection));
				Assertions.assertEquals(503, rejection.getStatusCode());
				Assertions.assertEquals(Duration.ofSeconds(1), rejection.getRetryIn());
				Assertions.assertEquals(10, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).inFlight());
				Assertions.assertEquals(1, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).rejectedCalls());
			}
			else {
				permits.add(limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET));
				Assertions.assertEquals(11, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).inFlight());
				Assertions.assertEquals(1, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).overLimitCalls());
				Assertions.assertEquals(0, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).rejectedCalls());
			}
			permits.forEach(limiter::release);
			Assertions.assertEquals(0, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).inFlight());
		}
	}

	/**
	 * At most {@link ServiceClientConcurrencyLimiter#MAX_TARGETS} targets get a
	 * limit; the others, and calls with no target, are not limited.
	 */
	@Test
	public void testMaxTargets() {
		final ServiceClientConcurrencyLimiter limiter = this.createLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE, 20, Duration.ofMinutes(10));
		for (int targetIndex = 0; targetIndex < (ServiceClientConcurrencyLimiter.MAX_TARGETS + 44); targetIndex++) {
			limiter.release(limiter.acquire("Target" + targetIndex));
		}
		Assertions.assertTrue(limiter.getSnapshot("Target" + (ServiceClientConcurrencyLimiter.MAX_TARGETS - 1)).isPresent());
		Assertions.assertTrue(limiter.getSnapshot("Target" + ServiceClientConcurrencyLimiter.MAX_TARGETS).isEmpty());
		Assertions.assertNull(limiter.acquire("Target" + ServiceClientConcurrencyLimiter.MAX_TARGETS));
		Assertions.assertNull(limiter.acquire(ServiceClientTarget.NONE));
		Assertions.assertTrue(limiter.getSnapshot(ServiceClientTarget.NONE).isEmpty());
	}

	/**
	 * Mode off (default or per target) limits nothing; per-target mode, minimum
	 * and maximum come from the environment.
	 */
	@Test
	public void testModesAndPerTargetSettings() {
		Assertions.assertNull(ServiceClientConcurrencyLimiter.NONE.acquire(ServiceClientConcurrencyLimiterTest.TARGET));
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources()
				.addFirst(new MapPropertySource("test",
						Map.of(ServiceClientConcurrencyLimiter.PROPERTY_PREFIX + "mode.OffTestClient", "off",
								ServiceClientConcurrencyLimiter.PROPERTY_PREFIX + "mode." + ServiceClientConcurrencyLimiterTest.TARGET, "Enforce",
								ServiceClientConcurrencyLimiter.PROPERTY_PREFIX + "min." + ServiceClientConcurrencyLimiterTest.TARGET, "30",
								ServiceClientConcurrencyLimiter.PROPERTY_PREFIX + "max." + ServiceClientConcurrencyLimiterTest.TARGET, "40",
								ServiceClientConcurrencyLimiter.PROPERTY_PREFIX + "mode.InvalidTestClient", "sometimes")));
		final ServiceClientConcurrencyLimiter limiter = new ServiceClientConcurrencyLimiter(ServiceClientConcurrencyLimiterTest.SETTINGS, environment,
				ServiceClientMetrics.NONE, this.clock::get);
		Assertions.assertNull(limiter.acquire("OffTestClient"));
		limiter.release(limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET));
		final ServiceClientConcurrencyLimiter.Snapshot snapshot = ServiceClientConcurrencyLimiterTest.getSnapshot(limiter);
		Assertions.assertEquals(ServiceClientConcurrencyLimiter.Mode.ENFORCE, snapshot.mode());
		Assertions.assertEquals(30, snapshot.limit());
		limiter.release(limiter.acquire("InvalidTestClient"));
		Assertions.assertEquals(ServiceClientConcurrencyLimiter.Mode.OBSERVE, limiter.getSnapshot("InvalidTestClient").orElseThrow().mode());
		// The per-target maximum caps a healthy burst (not the default 256).
		this.runWindows(limiter, ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS + 1, 70);
		final List<ServiceClientConcurrencyLimiter.Permit> permits = new ArrayList<>();
		for (int operationIndex = 0; operationIndex < 100; operationIndex++) {
			permits.add(limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET));
		}
		final ServiceClientConcurrencyLimiter.Snapshot burstSnapshot = ServiceClientConcurrencyLimiterTest.getSnapshot(limiter);
		Assertions.assertEquals(40, burstSnapshot.limit());
		Assertions.assertEquals(100, burstSnapshot.inFlight());
		Assertions.assertEquals(0, burstSnapshot.rejectedCalls());
		permits.forEach(limiter::release);
	}

	/**
	 * Mode off by default, with one target switched on in the environment: only
	 * that target is limited.
	 */
	@Test
	public void testOffByDefaultWithTargetOn() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test",
				Map.of(ServiceClientConcurrencyLimiter.PROPERTY_PREFIX + "mode." + ServiceClientConcurrencyLimiterTest.TARGET, "observe")));
		final ServiceClientConcurrencyLimiter limiter = new ServiceClientConcurrencyLimiter(
				ServiceClientConcurrencyLimiterTest.SETTINGS.withMode(ServiceClientConcurrencyLimiter.Mode.OFF), environment, ServiceClientMetrics.NONE,
				this.clock::get);
		Assertions.assertNull(limiter.acquire("OtherTestClient"));
		final ServiceClientConcurrencyLimiter.Permit permit = limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET);
		Assertions.assertNotNull(permit);
		Assertions.assertEquals(ServiceClientConcurrencyLimiter.Mode.OBSERVE, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).mode());
		Assertions.assertEquals(1, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).inFlight());
		limiter.release(permit);
		Assertions.assertEquals(0, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).inFlight());
	}

	/**
	 * The suggested fixed limit is the margin times the peak demand of the healthy
	 * windows (rate times latency, or the in-flight peak), never under the
	 * minimum, and none before a healthy window.
	 */
	@Test
	public void testSuggestion() {
		final ServiceClientConcurrencyLimiter limiter = this.createLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE, 20, Duration.ofMinutes(10));
		this.runWindows(limiter, ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS, 100);
		Assertions.assertEquals(0, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).suggestedLimit());
		// 10 sequential operations of 100 ms in 1 s: 1 in flight, so the minimum.
		this.runWindows(limiter, 1, 100);
		Assertions.assertEquals(10, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).suggestedLimit());
		// 96 operations of 100 ms in 1 s (9.6 in flight by Little's law), 6 at once
		// at most: 2 x 9.6 rounded up.
		this.runWindow(limiter, 16, 6, 100, false);
		Assertions.assertEquals(20, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).suggestedLimit());
		// 4 s windows are overloaded, so they do not count.
		this.runWindow(limiter, 10, 30, 4000, false);
		Assertions.assertTrue(ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).isOverloaded());
		Assertions.assertEquals(20, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).suggestedLimit());
		// Older than 24 hours, it is forgotten.
		this.clock.addAndGet(TimeUnit.HOURS.toNanos(25));
		Assertions.assertEquals(0, ServiceClientConcurrencyLimiterTest.getSnapshot(limiter).suggestedLimit());
	}

	/**
	 * Operations from 16 threads at once (with the real clock): no exception, and
	 * the in-flight count goes back to exactly zero.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testConcurrentOperations() throws Exception {
		final ServiceClientConcurrencyLimiter limiter = new ServiceClientConcurrencyLimiter(
				new ServiceClientConcurrencyLimiter.Settings(ServiceClientConcurrencyLimiter.Mode.ENFORCE, 10, 256, 20, Duration.ofMillis(1), 10, 3.0,
						Duration.ofMinutes(10), List.of(429, 503), 2.0, Duration.ZERO),
				null, ServiceClientMetrics.NONE);
		final ExecutorService threads = Executors.newFixedThreadPool(16);
		try {
			final List<Future<Long>> results = new ArrayList<>();
			for (int threadIndex = 0; threadIndex < 16; threadIndex++) {
				results.add(threads.submit(() -> {
					long rejections = 0;
					for (int operationIndex = 0; operationIndex < 50_000; operationIndex++) {
						try {
							final ServiceClientConcurrencyLimiter.Permit permit = limiter.acquire(ServiceClientConcurrencyLimiterTest.TARGET);
							limiter.recordAttempt(permit, ThreadLocalRandom.current().nextLong(TimeUnit.MILLISECONDS.toNanos(500)), true,
									ThreadLocalRandom.current().nextInt(20) == 0);
							limiter.release(permit);
						}
						catch (final IntegrationException exception) {
							Assertions.assertTrue(ServiceClientConcurrencyLimiter.isRejection(exception));
							rejections++;
						}
					}
					return rejections;
				}));
			}
			long rejections = 0;
			for (final Future<Long> result : results) {
				rejections += result.get(1, TimeUnit.MINUTES);
			}
			final ServiceClientConcurrencyLimiter.Snapshot snapshot = ServiceClientConcurrencyLimiterTest.getSnapshot(limiter);
			Assertions.assertEquals(0, snapshot.inFlight());
			Assertions.assertEquals(rejections, snapshot.rejectedCalls());
			Assertions.assertTrue((snapshot.limit() >= 10) && (snapshot.limit() <= 256));
		}
		finally {
			threads.shutdownNow();
		}
	}

}
