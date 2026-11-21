package org.coldis.library.service.client;

import java.util.concurrent.TimeUnit;

/**
 * Deadline of the request the current thread is serving: the moment
 * ({@link System#nanoTime()}) after which the caller no longer waits for its
 * answer. It is the contract between the service library, which sets it when a
 * request arrives with the {@value #HEADER} header and clears it when the request
 * ends, and the generated clients, which read it before each outgoing call
 * (their read timeout is capped to what is left of it, and they send what is
 * left in the same header). It lives here, not in the service library, because
 * the service library depends on this one and not the other way round.
 * <p>
 * Per thread only: work handed to another thread (asynchronous methods, JMS
 * listeners, executors) has no deadline, unless it is carried there with
 * {@link #getDeadlineNanos()} and {@link #setDeadlineNanos(Long)}. Reading it
 * takes no lock and allocates nothing.
 */
public final class ServiceClientRequestDeadline {

	/**
	 * Header that carries what is left of the caller's deadline, in milliseconds
	 * (a non-negative integer; zero or less means the caller has already given
	 * up).
	 */
	public static final String HEADER = "X-Request-Timeout-Ms";

	/**
	 * Longest time a deadline can be from now (one day, milliseconds): a longer
	 * one is taken as this.
	 */
	public static final long MAX_TIMEOUT_MILLIS = TimeUnit.DAYS.toMillis(1);

	/**
	 * Deadline of the current thread's request ({@link System#nanoTime()};
	 * {@code null} for none).
	 */
	private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();

	/**
	 * No instances.
	 */
	private ServiceClientRequestDeadline() {
		super();
	}

	/**
	 * Gets the deadline of the current thread's request.
	 *
	 * @return The deadline ({@link System#nanoTime()}; {@code null} for none).
	 */
	public static Long getDeadlineNanos() {
		return ServiceClientRequestDeadline.DEADLINE_NANOS.get();
	}

	/**
	 * Sets (or clears) the deadline of the current thread's request. Whoever sets
	 * it restores the previous value when the request ends, so a thread never
	 * keeps a deadline that is not its own.
	 *
	 * @param deadlineNanos Deadline ({@link System#nanoTime()}; {@code null}
	 *                          clears it).
	 */
	public static void setDeadlineNanos(
			final Long deadlineNanos) {
		if (deadlineNanos == null) {
			ServiceClientRequestDeadline.DEADLINE_NANOS.remove();
		}
		else {
			ServiceClientRequestDeadline.DEADLINE_NANOS.set(deadlineNanos);
		}
	}

	/**
	 * Gets the deadline that is a time from now. The time is kept within
	 * plus or minus {@link #MAX_TIMEOUT_MILLIS}, so the deadline is always compared to
	 * {@link System#nanoTime()} by subtraction without overflow.
	 *
	 * @param  timeoutMillis Time from now (milliseconds).
	 * @return               The deadline ({@link System#nanoTime()}).
	 */
	public static long getDeadlineNanosIn(
			final long timeoutMillis) {
		return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(-ServiceClientRequestDeadline.MAX_TIMEOUT_MILLIS, Math.min(timeoutMillis, ServiceClientRequestDeadline.MAX_TIMEOUT_MILLIS)));
	}

	/**
	 * Gets what is left until a deadline.
	 *
	 * @param  deadlineNanos Deadline ({@link System#nanoTime()}).
	 * @return               What is left (nanoseconds; zero or negative when it
	 *                       has passed).
	 */
	public static long getRemainingNanos(
			final long deadlineNanos) {
		return deadlineNanos - System.nanoTime();
	}

}
