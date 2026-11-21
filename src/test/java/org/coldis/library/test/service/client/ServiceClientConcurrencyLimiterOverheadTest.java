package org.coldis.library.test.service.client;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.coldis.library.service.client.ServiceClientConcurrencyLimiter;
import org.coldis.library.service.client.ServiceClientMetrics;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.management.ThreadMXBean;

/**
 * Overhead of the concurrency limiter on an operation (plain objects, no JMH):
 * acquire, one attempt recorded and release, 5 million operations after a
 * warm-up, single-threaded and from 8 threads at once on the same target. The
 * mean time the limiter takes per operation must be under 1 microsecond, and
 * the measured loop must allocate nothing.
 * <p>
 * Single-threaded, the operations run back to back. From 8 threads, each
 * operation also spends 1 microsecond in its "call" between acquire and release
 * (a real call takes a thousand times that or more), and only the limiter's own
 * time is counted (clock reads included). The 8 threads are also run back to
 * back, with no call at all, as the worst case: every operation then fights
 * over the cache line of the shared in-flight count, which no real traffic
 * does; it is logged and only checked against a loose bound (a lock or an
 * allocation would show there).
 */
public class ServiceClientConcurrencyLimiterOverheadTest {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientConcurrencyLimiterOverheadTest.class);

	/**
	 * Target of the test.
	 */
	private static final String TARGET = "OverheadTestClient";

	/**
	 * Operations measured (in all threads).
	 */
	private static final int OPERATIONS = 5_000_000;

	/**
	 * Operations of the warm-up (per thread).
	 */
	private static final int WARM_UP_OPERATIONS = 1_000_000;

	/**
	 * Largest mean time per operation (nanoseconds).
	 */
	private static final long MAX_MEAN_NANOS = 1_000;

	/**
	 * Largest mean time per operation of the worst case (8 threads back to back;
	 * nanoseconds).
	 */
	private static final long MAX_WORST_CASE_MEAN_NANOS = 5_000;

	/**
	 * Time each operation spends in its call, from 8 threads (nanoseconds).
	 */
	private static final long CALL_NANOS = 1_000;

	/**
	 * Bytes the measured loop may allocate, at most (any allocation per
	 * operation would be millions of bytes; this leaves room for the measuring
	 * itself).
	 */
	private static final long MAX_ALLOCATED_BYTES = 1_024;

	/**
	 * Result of a thread.
	 *
	 * @param meanMeasuredNanos Mean measured time per operation (nanoseconds):
	 *                              the thread's CPU time back to back; with
	 *                              calls, the limiter's own elapsed time.
	 * @param meanWallNanos     Mean wall time per operation (nanoseconds).
	 * @param allocatedBytes Bytes allocated by the measured loop.
	 */
	private record Result(
			double meanMeasuredNanos,
			double meanWallNanos,
			long allocatedBytes) {}

	/**
	 * Runs operations through the limiter.
	 *
	 * @param limiter    Limiter.
	 * @param operations Operations.
	 */
	private static void runOperations(
			final ServiceClientConcurrencyLimiter limiter,
			final int operations) {
		for (int operationIndex = 0; operationIndex < operations; operationIndex++) {
			final ServiceClientConcurrencyLimiter.Permit permit = limiter.acquire(ServiceClientConcurrencyLimiterOverheadTest.TARGET);
			limiter.recordAttempt(permit, 1_000_000L + operationIndex, true, false);
			limiter.release(permit);
		}
	}

	/**
	 * Runs operations through the limiter, each spending a time in its call, and
	 * counts the limiter's own time (clock reads included).
	 *
	 * @param  limiter    Limiter.
	 * @param  operations Operations.
	 * @param  callNanos  Time each operation spends in its call (nanoseconds).
	 * @return            The limiter's own time (nanoseconds).
	 */
	private static long runCallingOperations(
			final ServiceClientConcurrencyLimiter limiter,
			final int operations,
			final long callNanos) {
		long limiterNanos = 0;
		for (int operationIndex = 0; operationIndex < operations; operationIndex++) {
			final long acquireStart = System.nanoTime();
			final ServiceClientConcurrencyLimiter.Permit permit = limiter.acquire(ServiceClientConcurrencyLimiterOverheadTest.TARGET);
			final long callStart = System.nanoTime();
			long callEnd = callStart;
			while ((callEnd - callStart) < callNanos) {
				Thread.onSpinWait();
				callEnd = System.nanoTime();
			}
			limiter.recordAttempt(permit, callEnd - callStart, true, false);
			limiter.release(permit);
			limiterNanos += (callStart - acquireStart) + (System.nanoTime() - callEnd);
		}
		return limiterNanos;
	}

	/**
	 * Measures operations with a call in the current thread.
	 *
	 * @param  limiter    Limiter.
	 * @param  operations Operations measured.
	 * @return            The result (the CPU time is the limiter's own time).
	 */
	private static Result measureCalling(
			final ServiceClientConcurrencyLimiter limiter,
			final int operations) {
		final ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		final long allocatedBefore = threadBean.getCurrentThreadAllocatedBytes();
		final long limiterNanos = ServiceClientConcurrencyLimiterOverheadTest.runCallingOperations(limiter, operations,
				ServiceClientConcurrencyLimiterOverheadTest.CALL_NANOS);
		final long allocatedAfter = threadBean.getCurrentThreadAllocatedBytes();
		return new Result(limiterNanos / (double) operations, limiterNanos / (double) operations, allocatedAfter - allocatedBefore);
	}

	/**
	 * Measures operations in the current thread, after a warm-up.
	 *
	 * @param  limiter    Limiter.
	 * @param  operations Operations measured.
	 * @return            The result.
	 */
	private static Result measure(
			final ServiceClientConcurrencyLimiter limiter,
			final int operations) {
		final ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
		final long allocatedBefore = threadBean.getCurrentThreadAllocatedBytes();
		final long cpuBefore = threadBean.getCurrentThreadCpuTime();
		final long wallBefore = System.nanoTime();
		ServiceClientConcurrencyLimiterOverheadTest.runOperations(limiter, operations);
		final long wallAfter = System.nanoTime();
		final long cpuAfter = threadBean.getCurrentThreadCpuTime();
		final long allocatedAfter = threadBean.getCurrentThreadAllocatedBytes();
		return new Result((cpuAfter - cpuBefore) / (double) operations, (wallAfter - wallBefore) / (double) operations, allocatedAfter - allocatedBefore);
	}

	/**
	 * Checks and logs a result.
	 *
	 * @param name         Name of the run.
	 * @param result       Result.
	 * @param maxMeanNanos Largest mean time per operation (nanoseconds).
	 */
	private static void check(
			final String name,
			final Result result,
			final long maxMeanNanos) {
		ServiceClientConcurrencyLimiterOverheadTest.LOGGER.info("Concurrency limiter overhead ({}): {} ns measured, {} ns wall per operation, {} bytes allocated.",
				name, Math.round(result.meanMeasuredNanos()), Math.round(result.meanWallNanos()), result.allocatedBytes());
		Assertions.assertTrue(result.meanMeasuredNanos() < maxMeanNanos, name + ": " + result);
		Assertions.assertTrue(result.allocatedBytes() <= ServiceClientConcurrencyLimiterOverheadTest.MAX_ALLOCATED_BYTES, name + ": " + result);
	}

	/**
	 * Single-threaded overhead.
	 */
	@Test
	public void testSingleThreadOverhead() {
		final ServiceClientConcurrencyLimiter limiter = new ServiceClientConcurrencyLimiter(ServiceClientConcurrencyLimiter.Settings.DEFAULTS, null,
				ServiceClientMetrics.NONE);
		ServiceClientConcurrencyLimiterOverheadTest.runOperations(limiter, ServiceClientConcurrencyLimiterOverheadTest.WARM_UP_OPERATIONS);
		ServiceClientConcurrencyLimiterOverheadTest.check("1 thread",
				ServiceClientConcurrencyLimiterOverheadTest.measure(limiter, ServiceClientConcurrencyLimiterOverheadTest.OPERATIONS),
				ServiceClientConcurrencyLimiterOverheadTest.MAX_MEAN_NANOS);
		Assertions.assertEquals(0, limiter.getSnapshot(ServiceClientConcurrencyLimiterOverheadTest.TARGET).orElseThrow().inFlight());
	}

	/**
	 * Runs 5 million operations from 8 threads (at most one per processor) at
	 * once on the same target, after a
	 * warm-up in the same threads, and checks each thread's result.
	 *
	 * @param  name         Name of the run.
	 * @param  isCalling    If each operation spends a time in its call (the
	 *                          limiter's own time is then measured).
	 * @param  maxMeanNanos Largest mean time per operation (nanoseconds).
	 * @throws Exception    If the test fails.
	 */
	private static void runEightThreads(
			final String name,
			final boolean isCalling,
			final long maxMeanNanos) throws Exception {
		// At most one thread per processor: more would measure the scheduler's
		// pauses (elapsed time), not the limiter.
		final int threadCount = Math.min(8, Runtime.getRuntime().availableProcessors());
		final ServiceClientConcurrencyLimiter limiter = new ServiceClientConcurrencyLimiter(ServiceClientConcurrencyLimiter.Settings.DEFAULTS, null,
				ServiceClientMetrics.NONE);
		final CyclicBarrier barrier = new CyclicBarrier(threadCount);
		final ExecutorService threads = Executors.newFixedThreadPool(threadCount);
		try {
			final List<Future<Result>> results = new ArrayList<>();
			for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
				results.add(threads.submit(() -> {
					ServiceClientConcurrencyLimiterOverheadTest.runOperations(limiter, ServiceClientConcurrencyLimiterOverheadTest.WARM_UP_OPERATIONS);
					ServiceClientConcurrencyLimiterOverheadTest.runCallingOperations(limiter, ServiceClientConcurrencyLimiterOverheadTest.WARM_UP_OPERATIONS / 10,
							ServiceClientConcurrencyLimiterOverheadTest.CALL_NANOS);
					barrier.await(1, TimeUnit.MINUTES);
					final int operations = ServiceClientConcurrencyLimiterOverheadTest.OPERATIONS / threadCount;
					return (isCalling ? ServiceClientConcurrencyLimiterOverheadTest.measureCalling(limiter, operations)
							: ServiceClientConcurrencyLimiterOverheadTest.measure(limiter, operations));
				}));
			}
			for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
				ServiceClientConcurrencyLimiterOverheadTest.check(name + ", thread " + threadIndex, results.get(threadIndex).get(5, TimeUnit.MINUTES),
						maxMeanNanos);
			}
			Assertions.assertEquals(0, limiter.getSnapshot(ServiceClientConcurrencyLimiterOverheadTest.TARGET).orElseThrow().inFlight());
		}
		finally {
			threads.shutdownNow();
		}
	}

	/**
	 * Overhead from 8 threads at once on the same target, each operation with a
	 * 1 microsecond call.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testEightThreadsOverhead() throws Exception {
		ServiceClientConcurrencyLimiterOverheadTest.runEightThreads("8 threads (at most one per processor), 1 us calls", true, ServiceClientConcurrencyLimiterOverheadTest.MAX_MEAN_NANOS);
	}

	/**
	 * Overhead from 8 threads at once on the same target, back to back (worst
	 * case: logged, loose bound).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testEightThreadsWorstCase() throws Exception {
		ServiceClientConcurrencyLimiterOverheadTest.runEightThreads("8 threads, back to back", false,
				ServiceClientConcurrencyLimiterOverheadTest.MAX_WORST_CASE_MEAN_NANOS);
	}

}
