package org.coldis.library.service.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Supplier;

import javax.net.ssl.SSLException;

import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

/**
 * HTTP client retry strategy of the service client: the HTTP client's default
 * strategy (one re-send, after one second or the {@code Retry-After} header),
 * except that a response is re-sent on a 429 (too many requests) only, not on a
 * 503: the HTTP client's default re-sends a 503 for any method, POST included,
 * and between services a 503 usually comes from the application, so the re-send
 * may repeat work the server already did. An I/O failure is re-sent for
 * idempotent methods, as by default, unless a failover attempt runs on the
 * current thread (the failover executor already retries on another endpoint, so
 * attempts are not doubled). The library HTTP client uses it; a service whose
 * generated clients run on its own template and HTTP client installs it there
 * too, or each failover attempt may be retried by the HTTP client as well.
 */
public class ServiceClientRetryStrategy extends DefaultHttpRequestRetryStrategy {

	/**
	 * If retries are suspended on the current thread.
	 */
	private static final ThreadLocal<Boolean> ARE_RETRIES_SUSPENDED = ThreadLocal.withInitial(() -> Boolean.FALSE);

	/**
	 * I/O failures never re-sent (the HTTP client's default list).
	 */
	private static final List<Class<? extends IOException>> NON_RETRIABLE_FAILURES = List.of(InterruptedIOException.class, UnknownHostException.class,
			ConnectException.class, ConnectionClosedException.class, NoRouteToHostException.class, SSLException.class);

	/**
	 * Default constructor (the HTTP client's default strategy: one retry, after one
	 * second or the {@code Retry-After} header; a response re-sent on a 429 only).
	 */
	public ServiceClientRetryStrategy() {
		super(1, TimeValue.ofSeconds(1L), ServiceClientRetryStrategy.NON_RETRIABLE_FAILURES, List.of(HttpStatus.SC_TOO_MANY_REQUESTS));
	}

	/**
	 * Runs an action with the HTTP client retries suspended on the current thread.
	 *
	 * @param  <ResultType> Action result type.
	 * @param  action       Action.
	 * @return              The action result.
	 */
	public static <ResultType> ResultType runWithoutRetries(
			final Supplier<ResultType> action) {
		final Boolean wasSuspended = ServiceClientRetryStrategy.ARE_RETRIES_SUSPENDED.get();
		ServiceClientRetryStrategy.ARE_RETRIES_SUSPENDED.set(Boolean.TRUE);
		try {
			return action.get();
		}
		finally {
			// Leaves no entry on the thread unless an outer call suspended retries.
			if (wasSuspended) {
				ServiceClientRetryStrategy.ARE_RETRIES_SUSPENDED.set(Boolean.TRUE);
			}
			else {
				ServiceClientRetryStrategy.ARE_RETRIES_SUSPENDED.remove();
			}
		}
	}

	/**
	 * @see DefaultHttpRequestRetryStrategy#retryRequest(HttpRequest, IOException,
	 *      int, HttpContext)
	 */
	@Override
	public boolean retryRequest(
			final HttpRequest request,
			final IOException exception,
			final int execCount,
			final HttpContext context) {
		return !ServiceClientRetryStrategy.ARE_RETRIES_SUSPENDED.get() && super.retryRequest(request, exception, execCount, context);
	}

}
