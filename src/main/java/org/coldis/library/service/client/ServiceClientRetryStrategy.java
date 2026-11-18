package org.coldis.library.service.client;

import java.io.IOException;
import java.util.function.Supplier;

import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.protocol.HttpContext;

/**
 * HTTP client retry strategy of the service client: the HTTP client's default
 * strategy (one re-send, after one second or the {@code Retry-After} header),
 * except that a response is re-sent on a 429 (too many requests) only, not on a
 * 503: the HTTP client's default re-sends a 503 for any method, POST included,
 * and between services a 503 usually comes from the application, so the re-send
 * may repeat work the server already did. The 503 re-send can be brought back
 * ({@code org.coldis.library.service-client.retry-service-unavailable}); like an
 * I/O re-send, it is then suspended during failover. An I/O failure is re-sent for
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
	 * If a 503 (service unavailable) is re-sent.
	 */
	private final boolean shouldRetryServiceUnavailable;

	/**
	 * Constructor.
	 *
	 * @param shouldRetryServiceUnavailable If a 503 (service unavailable) is
	 *                                          re-sent once (outside failover), as
	 *                                          the HTTP client's default does.
	 */
	public ServiceClientRetryStrategy(final boolean shouldRetryServiceUnavailable) {
		super();
		this.shouldRetryServiceUnavailable = shouldRetryServiceUnavailable;
	}

	/**
	 * Default constructor (the HTTP client's default strategy: one retry, after one
	 * second or the {@code Retry-After} header; a response re-sent on a 429 only).
	 */
	public ServiceClientRetryStrategy() {
		this(false);
	}

	/**
	 * Gets if a 503 (service unavailable) is re-sent.
	 *
	 * @return If a 503 (service unavailable) is re-sent.
	 */
	public boolean isRetryingServiceUnavailable() {
		return this.shouldRetryServiceUnavailable;
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

	/**
	 * Re-sends a response on a 429, and on a 503 only if enabled and no failover
	 * attempt runs on the current thread (the HTTP client's default re-sends both).
	 *
	 * @see DefaultHttpRequestRetryStrategy#retryRequest(HttpResponse, int,
	 *      HttpContext)
	 */
	@Override
	public boolean retryRequest(
			final HttpResponse response,
			final int execCount,
			final HttpContext context) {
		final boolean isRetriableStatus = (response.getCode() == HttpStatus.SC_TOO_MANY_REQUESTS)
				|| (this.shouldRetryServiceUnavailable && (response.getCode() == HttpStatus.SC_SERVICE_UNAVAILABLE)
						&& !ServiceClientRetryStrategy.ARE_RETRIES_SUSPENDED.get());
		return isRetriableStatus && super.retryRequest(response, execCount, context);
	}

}
