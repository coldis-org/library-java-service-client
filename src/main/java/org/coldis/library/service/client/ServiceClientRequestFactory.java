package org.coldis.library.service.client;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.support.InterceptingHttpAccessor;
import org.springframework.web.client.RestOperations;

/**
 * Request factory of the service client: the HTTP components factory, whose read
 * (response) timeout can be overridden for the requests made by the current
 * thread while an action runs (a generated client's own read timeout). The
 * override is per thread: any other request made through this kind of factory
 * on the same thread during the exchange (by an interceptor or a message
 * converter, for instance) gets the same read timeout.
 */
public class ServiceClientRequestFactory extends HttpComponentsClientHttpRequestFactory {

	/**
	 * Read timeout of the requests made by the current thread ({@code null} for the
	 * factory's own).
	 */
	private static final ThreadLocal<Duration> READ_TIMEOUT = new ThreadLocal<>();

	/**
	 * Read timeout scope of this kind of factory: warns when the template visibly
	 * ignores the read timeout, and applies it to the requests the current thread
	 * makes while the action runs.
	 */
	public static final ServiceClientReadTimeoutScope READ_TIMEOUT_SCOPE = ServiceClientRequestFactory::runWithReadTimeout;

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
	 * Applies the current thread's read timeout, if any, over the factory's request
	 * configuration (at least one millisecond: zero would mean no timeout).
	 *
	 * @see HttpComponentsClientHttpRequestFactory#createRequestConfig(Object)
	 */
	@Override
	protected RequestConfig createRequestConfig(
			final Object client) {
		final RequestConfig requestConfig = super.createRequestConfig(client);
		final Duration readTimeout = ServiceClientRequestFactory.READ_TIMEOUT.get();
		return (readTimeout == null ? requestConfig
				: RequestConfig.copy(requestConfig == null ? RequestConfig.DEFAULT : requestConfig)
						.setResponseTimeout(Math.max(1L, readTimeout.toMillis()), TimeUnit.MILLISECONDS).build());
	}

}
