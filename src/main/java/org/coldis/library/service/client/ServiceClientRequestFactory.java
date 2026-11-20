package org.coldis.library.service.client;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.support.InterceptingHttpAccessor;
import org.springframework.web.client.RestOperations;

/**
 * Request factory of the service client: the HTTP components factory, whose read
 * (response) timeout can be overridden for the requests made by the current
 * thread while an action runs (a generated client's own read timeout), and
 * whose waits for a pooled connection and for the connection can be capped the
 * same way (the rest of an operation's time budget). The overrides are per
 * thread: any other request made through this kind of factory on the same thread
 * during the exchange (by an interceptor or a message converter, for instance)
 * gets the same ones.
 */
public class ServiceClientRequestFactory extends HttpComponentsClientHttpRequestFactory {

	/**
	 * Read timeout of the requests made by the current thread ({@code null} for the
	 * factory's own).
	 */
	private static final ThreadLocal<Duration> READ_TIMEOUT = new ThreadLocal<>();

	/**
	 * Longest wait for a pooled connection and for the connection of the requests
	 * made by the current thread ({@code null} for the factory's own).
	 */
	private static final ThreadLocal<Duration> CONNECTION_WAIT_LIMIT = new ThreadLocal<>();

	/**
	 * If the connection wait limit cut the connect timeout of the last request made
	 * by the current thread under {@link #runWithConnectionWaitLimit(Duration,
	 * Supplier)} (reset when it starts).
	 */
	private static final ThreadLocal<Boolean> CONNECT_TIMEOUT_CUT = ThreadLocal.withInitial(() -> false);

	/**
	 * Read timeout scope of this kind of factory: warns when the template visibly
	 * ignores the read timeout, and applies it (and the connection wait limit) to
	 * the requests the current thread makes while the action runs.
	 */
	public static final ServiceClientReadTimeoutScope READ_TIMEOUT_SCOPE = new ServiceClientReadTimeoutScope() {

		/**
		 * @see ServiceClientReadTimeoutScope#runWithReadTimeout(RestOperations,
		 *      Duration, Supplier)
		 */
		@Override
		public <ResultType> ResultType runWithReadTimeout(
				final RestOperations template,
				final Duration readTimeout,
				final Supplier<ResultType> action) {
			return ServiceClientRequestFactory.runWithReadTimeout(template, readTimeout, action);
		}

		/**
		 * @see ServiceClientReadTimeoutScope#runWithTimeouts(RestOperations,
		 *      Duration, Duration, Supplier)
		 */
		@Override
		public <ResultType> ResultType runWithTimeouts(
				final RestOperations template,
				final Duration readTimeout,
				final Duration connectionWaitLimit,
				final Supplier<ResultType> action) {
			return ServiceClientRequestFactory.runWithConnectionWaitLimit(connectionWaitLimit,
					() -> ServiceClientRequestFactory.runWithReadTimeout(template, readTimeout, action));
		}

		/**
		 * @see ServiceClientReadTimeoutScope#isLastConnectTimeoutCut()
		 */
		@Override
		public boolean isLastConnectTimeoutCut() {
			return ServiceClientRequestFactory.isLastConnectTimeoutCut();
		}

		/**
		 * @see ServiceClientReadTimeoutScope#appliesConnectionWaitLimit()
		 */
		@Override
		public boolean appliesConnectionWaitLimit() {
			return true;
		}

	};

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientRequestFactory.class);

	/**
	 * Templates already warned about a read timeout they ignore (weakly held).
	 */
	private static final Set<RestOperations> TEMPLATES_WARNED_FOR_READ_TIMEOUT = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

	/**
	 * Constructor.
	 *
	 * @param httpClient HTTP client.
	 */
	public ServiceClientRequestFactory(final HttpClient httpClient) {
		super(httpClient);
	}

	/**
	 * Sets the connect timeout (redeclared without the deprecation mark, so that
	 * Spring Boot's template builder sets it on this factory as it does on the base
	 * one). Upstream, the connect timeout moves to the connection pool's connection
	 * configuration: revisit this at the next Spring upgrade.
	 *
	 * @see HttpComponentsClientHttpRequestFactory#setConnectTimeout(Duration)
	 */
	@Override
	@SuppressWarnings("deprecation")
	public void setConnectTimeout(
			final Duration connectTimeout) {
		super.setConnectTimeout(connectTimeout);
	}

	/**
	 * Gets if a duration is a read timeout of its own (positive): {@code null},
	 * zero or negative keeps the factory's.
	 *
	 * @param  readTimeout Read timeout.
	 * @return             If the duration is a read timeout of its own.
	 */
	public static boolean hasOwnReadTimeout(
			final Duration readTimeout) {
		return (readTimeout != null) && readTimeout.isPositive();
	}

	/**
	 * Runs an action with a read timeout for every request the current thread makes
	 * through this kind of factory while the action runs (nested calls included).
	 *
	 * @param  <ResultType> Action result type.
	 * @param  readTimeout  Read timeout ({@code null}, zero or negative keeps the
	 *                          factory's own).
	 * @param  action       Action.
	 * @return              The action result.
	 */
	public static <ResultType> ResultType runWithReadTimeout(
			final Duration readTimeout,
			final Supplier<ResultType> action) {
		final Duration previousReadTimeout = ServiceClientRequestFactory.READ_TIMEOUT.get();
		ServiceClientRequestFactory.READ_TIMEOUT.set(ServiceClientRequestFactory.hasOwnReadTimeout(readTimeout) ? readTimeout : null);
		try {
			return action.get();
		}
		finally {
			if (previousReadTimeout == null) {
				ServiceClientRequestFactory.READ_TIMEOUT.remove();
			}
			else {
				ServiceClientRequestFactory.READ_TIMEOUT.set(previousReadTimeout);
			}
		}
	}

	/**
	 * Runs an action with a limit on the waits for a pooled connection and for the
	 * connection of every request the current thread makes through this kind of
	 * factory while the action runs (nested calls included): the factory's own
	 * waits, when shorter, are kept.
	 *
	 * @param  <ResultType>        Action result type.
	 * @param  connectionWaitLimit Longest wait ({@code null} keeps the factory's
	 *                                 own; zero or negative waits the least
	 *                                 possible, one millisecond).
	 * @param  action              Action.
	 * @return                     The action result.
	 */
	public static <ResultType> ResultType runWithConnectionWaitLimit(
			final Duration connectionWaitLimit,
			final Supplier<ResultType> action) {
		final Duration previousLimit = ServiceClientRequestFactory.CONNECTION_WAIT_LIMIT.get();
		ServiceClientRequestFactory.CONNECTION_WAIT_LIMIT.set(connectionWaitLimit);
		ServiceClientRequestFactory.CONNECT_TIMEOUT_CUT.remove();
		try {
			return action.get();
		}
		finally {
			if (previousLimit == null) {
				ServiceClientRequestFactory.CONNECTION_WAIT_LIMIT.remove();
			}
			else {
				ServiceClientRequestFactory.CONNECTION_WAIT_LIMIT.set(previousLimit);
			}
		}
	}

	/**
	 * Gets if the connection wait limit cut the connect timeout of the last
	 * request the current thread made under
	 * {@link #runWithConnectionWaitLimit(Duration, Supplier)}: the request then
	 * did not get the factory's own connect timeout, so a connect timeout of it
	 * says nothing about the endpoint.
	 *
	 * @return If the limit cut the last request's connect timeout.
	 */
	public static boolean isLastConnectTimeoutCut() {
		return ServiceClientRequestFactory.CONNECT_TIMEOUT_CUT.get();
	}

	/**
	 * Caps a timeout to a limit: the shorter of both (a missing or disabled
	 * timeout, zero, is no wait limit at all, so the limit wins).
	 *
	 * @param  timeout     Timeout ({@code null} for none).
	 * @param  limitMillis Limit (milliseconds, at least one).
	 * @return             The capped timeout.
	 */
	private static Timeout capTimeout(
			final Timeout timeout,
			final long limitMillis) {
		return ((timeout == null) || timeout.isDisabled() || (timeout.toMilliseconds() > limitMillis) ? Timeout.ofMilliseconds(limitMillis) : timeout);
	}

	/**
	 * Warns (once per template) when a read timeout is given for a template whose
	 * request factory is visibly not this kind of factory: the timeout has no
	 * effect there. A template with interceptors, or whose factory wraps another
	 * one (buffering), hides its actual factory and is not checked.
	 *
	 * @param  template    Template.
	 * @param  readTimeout Read timeout ({@code null}, zero or negative for none).
	 * @return             If the warning was logged by this call.
	 */
	public static boolean warnIfReadTimeoutIgnored(
			final RestOperations template,
			final Duration readTimeout) {
		final boolean shouldWarn = ServiceClientRequestFactory.hasOwnReadTimeout(readTimeout) && (template instanceof final InterceptingHttpAccessor accessor)
				&& accessor.getInterceptors().isEmpty() && !(accessor.getRequestFactory() instanceof ServiceClientRequestFactory)
				&& !(accessor.getRequestFactory() instanceof AbstractClientHttpRequestFactoryWrapper)
				&& ServiceClientRequestFactory.TEMPLATES_WARNED_FOR_READ_TIMEOUT.add(template);
		if (shouldWarn) {
			ServiceClientRequestFactory.LOGGER.warn("A read timeout is set for a client whose template uses {}, not {}: it has no effect.",
					((InterceptingHttpAccessor) template).getRequestFactory().getClass().getName(), ServiceClientRequestFactory.class.getSimpleName());
		}
		return shouldWarn;
	}

	/**
	 * Runs an action through a template with a read timeout (see
	 * {@link ServiceClientReadTimeoutScope}): warns when the template visibly
	 * ignores it, then applies it as {@link #runWithReadTimeout(Duration, Supplier)}
	 * does.
	 *
	 * @param  <ResultType> Action result type.
	 * @param  template     Template the action's requests go through.
	 * @param  readTimeout  Read timeout ({@code null}, zero or negative keeps the
	 *                          factory's own).
	 * @param  action       Action.
	 * @return              The action result.
	 */
	public static <ResultType> ResultType runWithReadTimeout(
			final RestOperations template,
			final Duration readTimeout,
			final Supplier<ResultType> action) {
		ServiceClientRequestFactory.warnIfReadTimeoutIgnored(template, readTimeout);
		return ServiceClientRequestFactory.runWithReadTimeout(readTimeout, action);
	}

	/**
	 * Applies the current thread's read timeout and connection wait limit, if any,
	 * over the factory's request configuration (at least one millisecond: zero
	 * would mean no timeout). The limit caps the wait for a pooled connection and
	 * the connect timeout.
	 *
	 * @see HttpComponentsClientHttpRequestFactory#createRequestConfig(Object)
	 */
	@Override
	@SuppressWarnings("deprecation")
	protected RequestConfig createRequestConfig(
			final Object client) {
		final RequestConfig requestConfig = super.createRequestConfig(client);
		final Duration readTimeout = ServiceClientRequestFactory.READ_TIMEOUT.get();
		final Duration connectionWaitLimit = ServiceClientRequestFactory.CONNECTION_WAIT_LIMIT.get();
		RequestConfig actualRequestConfig = requestConfig;
		if ((readTimeout != null) || (connectionWaitLimit != null)) {
			final RequestConfig baseRequestConfig = (requestConfig == null ? RequestConfig.DEFAULT : requestConfig);
			final RequestConfig.Builder requestConfigBuilder = RequestConfig.copy(baseRequestConfig);
			if (readTimeout != null) {
				requestConfigBuilder.setResponseTimeout(Math.max(1L, readTimeout.toMillis()), TimeUnit.MILLISECONDS);
			}
			if (connectionWaitLimit != null) {
				final long limitMillis = Math.max(1L, connectionWaitLimit.toMillis());
				requestConfigBuilder.setConnectionRequestTimeout(ServiceClientRequestFactory.capTimeout(baseRequestConfig.getConnectionRequestTimeout(), limitMillis));
				final Timeout ownConnectTimeout = baseRequestConfig.getConnectTimeout();
				requestConfigBuilder.setConnectTimeout(ServiceClientRequestFactory.capTimeout(ownConnectTimeout, limitMillis));
				ServiceClientRequestFactory.CONNECT_TIMEOUT_CUT.set((ownConnectTimeout == null) || ownConnectTimeout.isDisabled()
						|| (ownConnectTimeout.toMilliseconds() > limitMillis));
			}
			actualRequestConfig = requestConfigBuilder.build();
		}
		return actualRequestConfig;
	}

}
