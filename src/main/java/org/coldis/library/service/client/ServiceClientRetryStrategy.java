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
 * strategy, suspended while a failover attempt runs on the current thread (the
 * failover executor already retries on another endpoint, so attempts are not
 * doubled), except for a 429 (too many requests), which is still retried once on
 * the same endpoint after its {@code Retry-After}. The library HTTP client uses it; a service whose generated clients
 * run on its own template and HTTP client installs it there too, or each
 * failover attempt may be retried by the HTTP client as well.
 */
public class ServiceClientRetryStrategy extends DefaultHttpRequestRetryStrategy {

	/**
	 * If retries are suspended on the current thread.
	 */
	private static final ThreadLocal<Boolean> ARE_RETRIES_SUSPENDED = ThreadLocal.withInitial(() -> Boolean.FALSE);

	/**
	 * Default constructor (the HTTP client's default strategy: one retry, after one
	 * second or the {@code Retry-After} header).
	 */
	public ServiceClientRetryStrategy() {
		super();
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
	 * @see DefaultHttpRequestRetryStrategy#retryRequest(HttpResponse, int,
	 *      HttpContext)
	 */
	@Override
	public boolean retryRequest(
			final HttpResponse response,
			final int execCount,
			final HttpContext context) {
		return (!ServiceClientRetryStrategy.ARE_RETRIES_SUSPENDED.get() || (response.getCode() == HttpStatus.SC_TOO_MANY_REQUESTS))
				&& super.retryRequest(response, execCount, context);
	}

}
