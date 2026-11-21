package org.coldis.library.service.client;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.support.InterceptingHttpAccessor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestOperations;

/**
 * Executes a service client operation over its endpoint list (instances of the
 * same service), failing over to another endpoint when a failure allows it. The
 * endpoint of each attempt is chosen by the {@link ServiceClientEndpointBalancer},
 * which this executor keeps informed of requests in flight and failures.
 * <p>
 * The endpoints of a call come from its {@link ServiceClientEndpointResolver}
 * (the last good list of the target when its list is empty; no request when
 * there is none).
 * <p>
 * The attempts of an operation share one time budget: no further attempt starts
 * once it is spent, and the wait for a pooled connection and the wait for the
 * connection of every attempt are each capped to what is left of it when the
 * attempt starts (the read timeout is not). The waits before a request is sent
 * so stay under the budget plus one connect timeout.
 * <p>
 * Each operation holds one permit of its target's
 * {@link ServiceClientConcurrencyLimiter} across its attempts (taken before the
 * first attempt, using none of the budget), and each attempt's latency and
 * outcome feed it. A failure inside the limiter is counted and the operation
 * proceeds (deliberate fail-open: the limit protects the target, and must never
 * fail or slow a call by itself).
 * <p>
 * When the thread serves a request with a deadline
 * ({@link ServiceClientRequestDeadline}, set by the service library from the
 * caller's {@value ServiceClientRequestDeadline#HEADER} header), what is left of
 * it, less a margin, is checked before every attempt: once it is spent, no
 * attempt starts (the first one fails at once with an
 * {@link IntegrationException}, status 504, and no request); otherwise it caps
 * the attempt's read timeout and connection waits (and so the time budget), and
 * is sent to the endpoint in the same header (the attempt's read timeout, the
 * call's own or else the default template's, when shorter). The deadline is on
 * by default and can be switched off
 * ({@code org.coldis.library.service-client.deadline.enabled}, or
 * {@link ServiceClientRequestDeadlinePolicy#setEnabled(boolean)}); an executor
 * built without the auto-configuration has it off. A connect
 * timeout that took all the time the deadline left puts no endpoint in
 * cooldown (one well within it does). Nothing
 * running is interrupted: the deadline is only checked when an attempt starts.
 */
public class ServiceClientFailoverExecutor {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientFailoverExecutor.class);

	/**
	 * Executor used when none is configured: one attempt on a random endpoint, with
	 * the read timeout scope of {@link ServiceClientRequestFactory} (a scope bean
	 * does not reach it).
	 */
	public static final ServiceClientFailoverExecutor SINGLE_ATTEMPT = new ServiceClientFailoverExecutor(false, 1, null, null, null, false, Duration.ZERO,
			new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.RANDOM, Duration.ZERO), ServiceClientEndpointResolver.WITHOUT_LAST_ENDPOINTS,
			ServiceClientRequestFactory.READ_TIMEOUT_SCOPE, ServiceClientMetrics.NONE);

	/**
	 * Margin under an attempt's deadline limit within which a connect timeout is
	 * taken as the deadline's, not the endpoint's.
	 */
	private static final Duration DEADLINE_CUT_MARGIN = Duration.ofMillis(50);

	/**
	 * Least remaining time for another attempt.
	 */
	private static final Duration LEAST_REMAINING_TIME = Duration.ofMillis(1);

	/**
	 * Message code of a call not made because the request's deadline is spent.
	 */
	public static final String DEADLINE_EXPIRED_CODE = "service.client.deadline.expired";

	/**
	 * Status of the failure of a call not made because the request's deadline is
	 * spent (gateway timeout: the caller of the request gave up waiting).
	 */
	public static final int DEADLINE_EXPIRED_STATUS = 504;

	/**
	 * Stage of a deadline expiry (its metrics tag value): before the first attempt.
	 */
	public static final String DEADLINE_STAGE_CALL = "call";

	/**
	 * Stage of a deadline expiry (its metrics tag value): before a retry.
	 */
	public static final String DEADLINE_STAGE_RETRY = "retry";

	/**
	 * An absolute URL: its base (scheme, user information, host and port) and the
	 * rest (path, query, fragment).
	 */
	private static final Pattern ABSOLUTE_URL = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*://[^/?#\\s]+)(.*)$", Pattern.DOTALL);

	/**
	 * Failures that put the endpoint in its cooldown: the endpoint could not be
	 * connected to.
	 */
	private static final List<Class<? extends Throwable>> COOLDOWN_FAILURES = List.of(ConnectException.class, ConnectTimeoutException.class,
			NoRouteToHostException.class, UnknownHostException.class);

	/**
	 * Failures where the connection was never established (nothing was sent): the
	 * cooldown failures, and running out of local pooled connections (retried, but
	 * not the endpoint's failure, so no cooldown).
	 */
	private static final List<Class<? extends Throwable>> NOT_CONNECTED_FAILURES = Stream
			.concat(ServiceClientFailoverExecutor.COOLDOWN_FAILURES.stream(), Stream.of(ConnectionRequestTimeoutException.class)).toList();

	/**
	 * Failures waiting for a local pooled connection (an overload signal of the
	 * concurrency limit).
	 */
	private static final List<Class<? extends Throwable>> POOL_WAIT_FAILURES = List.of(ConnectionRequestTimeoutException.class);

	/**
	 * Failures where the connection dropped before a response (the request may have
	 * been processed).
	 */
	private static final List<Class<? extends Throwable>> DROPPED_CONNECTION_FAILURES = List.of(NoHttpResponseException.class,
			ConnectionClosedException.class, SocketException.class);

	/**
	 * Kind of an attempt failure (its metrics tag value).
	 */
	private enum FailureKind {

		/**
		 * The connection was never established.
		 */
		NOT_CONNECTED("not-connected"),

		/**
		 * The endpoint answered an error status.
		 */
		STATUS("status"),

		/**
		 * No response in the read timeout.
		 */
		READ_TIMEOUT("read-timeout"),

		/**
		 * The connection dropped before a response.
		 */
		DROPPED("dropped"),

		/**
		 * Any other failure.
		 */
		OTHER("other");

		/**
		 * Metrics tag value.
		 */
		private final String tagValue;

		/**
		 * Constructor.
		 *
		 * @param tagValue Metrics tag value.
		 */
		FailureKind(final String tagValue) {
			this.tagValue = tagValue;
		}

	}

	/**
	 * Outcome of a successful attempt (its metrics tag value).
	 */
	private static final String SUCCESS = "success";

	/**
	 * If failover is enabled.
	 */
	private final boolean isEnabled;

	/**
	 * Maximum attempts per operation.
	 */
	private final int maxAttempts;

	/**
	 * Methods taken as idempotent when the operation does not say.
	 */
	private final Set<HttpMethod> idempotentMethods;

	/**
	 * Response statuses retried for idempotent operations.
	 */
	private final Set<Integer> retryStatuses;

	/**
	 * Response statuses that put the endpoint in its cooldown (apart from the
	 * connection failures, which always do).
	 */
	private final Set<Integer> cooldownStatuses;

	/**
	 * If read timeouts are retried for idempotent operations.
	 */
	private final boolean shouldRetryOnReadTimeout;

	/**
	 * Scope that applies a call's own read timeout to its attempts.
	 */
	private final ServiceClientReadTimeoutScope readTimeoutScope;

	/**
	 * Endpoint balancer.
	 */
	private final ServiceClientEndpointBalancer balancer;

	/**
	 * Metrics.
	 */
	private final ServiceClientMetrics metrics;

	/**
	 * Time budget of an operation, over all its attempts (zero or negative for
	 * none).
	 */
	private final Duration maxTotalTime;

	/**
	 * Endpoint resolver.
	 */
	private final ServiceClientEndpointResolver endpointResolver;

	/**
	 * Concurrency limiter.
	 */
	private final ServiceClientConcurrencyLimiter concurrencyLimiter;

	/**
	 * How the request's deadline is applied to the calls.
	 */
	private final ServiceClientRequestDeadlinePolicy deadlinePolicy;

	/**
	 * Outcome of an operation: the response, or the failure of its last attempt.
	 *
	 * @param <ResponseType> Response type.
	 * @param url            URL of the last attempt.
	 * @param response       Response (if an attempt succeeded).
	 * @param failure        Failure of the last attempt (if none succeeded).
	 */
	public record Outcome<ResponseType>(
			String url,
			ResponseEntity<ResponseType> response,
			Exception failure) {}

	/**
	 * Default constructor.
	 *
	 * @param isEnabled                If failover is enabled.
	 * @param maxAttempts              Maximum attempts per operation.
	 * @param idempotentMethods        Methods taken as idempotent when the
	 *                                     operation does not say.
	 * @param retryStatuses            Response statuses retried for idempotent
	 *                                     operations.
	 * @param cooldownStatuses         Response statuses that put the endpoint in
	 *                                     its cooldown.
	 * @param shouldRetryOnReadTimeout If read timeouts are retried for idempotent
	 *                                     operations.
	 * @param maxTotalTime             Time budget of an operation, over all its
	 *                                     attempts ({@code null}, zero or
	 *                                     negative for none).
	 * @param balancer                 Endpoint balancer.
	 * @param endpointResolver         Endpoint resolver.
	 * @param readTimeoutScope         Scope that applies a call's own timeouts to
	 *                                     its attempts.
	 * @param metrics                  Metrics.
	 * @param concurrencyLimiter       Concurrency limiter.
	 * @param deadlinePolicy           How the request's deadline is applied to
	 *                                     the calls ({@code null} for one that is
	 *                                     off).
	 */
	public ServiceClientFailoverExecutor(
			final boolean isEnabled,
			final int maxAttempts,
			final String[] idempotentMethods,
			final String[] retryStatuses,
			final String[] cooldownStatuses,
			final boolean shouldRetryOnReadTimeout,
			final Duration maxTotalTime,
			final ServiceClientEndpointBalancer balancer,
			final ServiceClientEndpointResolver endpointResolver,
			final ServiceClientReadTimeoutScope readTimeoutScope,
			final ServiceClientMetrics metrics,
			final ServiceClientConcurrencyLimiter concurrencyLimiter,
			final ServiceClientRequestDeadlinePolicy deadlinePolicy) {
		super();
		this.concurrencyLimiter = Objects.requireNonNull(concurrencyLimiter, "concurrencyLimiter");
		this.balancer = Objects.requireNonNull(balancer, "balancer");
		this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver");
		this.readTimeoutScope = Objects.requireNonNull(readTimeoutScope, "readTimeoutScope");
		this.metrics = Objects.requireNonNull(metrics, "metrics");
		this.maxTotalTime = (maxTotalTime == null ? Duration.ZERO : maxTotalTime);
		this.isEnabled = isEnabled;
		this.maxAttempts = maxAttempts;
		this.idempotentMethods = Arrays.stream(idempotentMethods == null ? new String[] {} : idempotentMethods).filter(StringUtils::isNotBlank)
				.map(method -> HttpMethod.valueOf(method.trim().toUpperCase(Locale.ROOT))).collect(Collectors.toSet());
		this.retryStatuses = ServiceClientFailoverExecutor.parseStatuses(retryStatuses);
		this.cooldownStatuses = ServiceClientFailoverExecutor.parseStatuses(cooldownStatuses);
		this.shouldRetryOnReadTimeout = shouldRetryOnReadTimeout;
		this.deadlinePolicy = (deadlinePolicy == null ? ServiceClientRequestDeadlinePolicy.createDisabled() : deadlinePolicy);
	}

	/**
	 * Constructor with the request's deadline off (a policy of its own, see
	 * {@link ServiceClientRequestDeadlinePolicy#createDisabled()}).
	 *
	 * @param isEnabled                If failover is enabled.
	 * @param maxAttempts              Maximum attempts per operation.
	 * @param idempotentMethods        Methods taken as idempotent when the
	 *                                     operation does not say.
	 * @param retryStatuses            Response statuses retried for idempotent
	 *                                     operations.
	 * @param cooldownStatuses         Response statuses that put the endpoint in
	 *                                     its cooldown.
	 * @param shouldRetryOnReadTimeout If read timeouts are retried for idempotent
	 *                                     operations.
	 * @param maxTotalTime             Time budget of an operation, over all its
	 *                                     attempts ({@code null}, zero or
	 *                                     negative for none).
	 * @param balancer                 Endpoint balancer.
	 * @param endpointResolver         Endpoint resolver.
	 * @param readTimeoutScope         Scope that applies a call's own timeouts to
	 *                                     its attempts.
	 * @param metrics                  Metrics.
	 * @param concurrencyLimiter       Concurrency limiter.
	 */
	public ServiceClientFailoverExecutor(
			final boolean isEnabled,
			final int maxAttempts,
			final String[] idempotentMethods,
			final String[] retryStatuses,
			final String[] cooldownStatuses,
			final boolean shouldRetryOnReadTimeout,
			final Duration maxTotalTime,
			final ServiceClientEndpointBalancer balancer,
			final ServiceClientEndpointResolver endpointResolver,
			final ServiceClientReadTimeoutScope readTimeoutScope,
			final ServiceClientMetrics metrics,
			final ServiceClientConcurrencyLimiter concurrencyLimiter) {
		this(isEnabled, maxAttempts, idempotentMethods, retryStatuses, cooldownStatuses, shouldRetryOnReadTimeout, maxTotalTime, balancer, endpointResolver,
				readTimeoutScope, metrics, concurrencyLimiter, null);
	}

	/**
	 * Constructor with no concurrency limit
	 * ({@link ServiceClientConcurrencyLimiter#NONE}) and the request's deadline
	 * off.
	 *
	 * @param isEnabled                If failover is enabled.
	 * @param maxAttempts              Maximum attempts per operation.
	 * @param idempotentMethods        Methods taken as idempotent when the
	 *                                     operation does not say.
	 * @param retryStatuses            Response statuses retried for idempotent
	 *                                     operations.
	 * @param cooldownStatuses         Response statuses that put the endpoint in
	 *                                     its cooldown.
	 * @param shouldRetryOnReadTimeout If read timeouts are retried for idempotent
	 *                                     operations.
	 * @param maxTotalTime             Time budget of an operation, over all its
	 *                                     attempts ({@code null}, zero or
	 *                                     negative for none).
	 * @param balancer                 Endpoint balancer.
	 * @param endpointResolver         Endpoint resolver.
	 * @param readTimeoutScope         Scope that applies a call's own timeouts to
	 *                                     its attempts.
	 * @param metrics                  Metrics.
	 */
	public ServiceClientFailoverExecutor(
			final boolean isEnabled,
			final int maxAttempts,
			final String[] idempotentMethods,
			final String[] retryStatuses,
			final String[] cooldownStatuses,
			final boolean shouldRetryOnReadTimeout,
			final Duration maxTotalTime,
			final ServiceClientEndpointBalancer balancer,
			final ServiceClientEndpointResolver endpointResolver,
			final ServiceClientReadTimeoutScope readTimeoutScope,
			final ServiceClientMetrics metrics) {
		this(isEnabled, maxAttempts, idempotentMethods, retryStatuses, cooldownStatuses, shouldRetryOnReadTimeout, maxTotalTime, balancer, endpointResolver,
				readTimeoutScope, metrics, ServiceClientConcurrencyLimiter.NONE, null);
	}

	/**
	 * Constructor with no time budget and an endpoint resolver that keeps no last
	 * list ({@link ServiceClientEndpointResolver#WITHOUT_LAST_ENDPOINTS}: a call with an empty
	 * list fails at once, with no request), so an executor a service builds itself
	 * does not start calling a last list unless it passes a resolver.
	 *
	 * @param isEnabled                If failover is enabled.
	 * @param maxAttempts              Maximum attempts per operation.
	 * @param idempotentMethods        Methods taken as idempotent when the
	 *                                     operation does not say.
	 * @param retryStatuses            Response statuses retried for idempotent
	 *                                     operations.
	 * @param cooldownStatuses         Response statuses that put the endpoint in
	 *                                     its cooldown.
	 * @param shouldRetryOnReadTimeout If read timeouts are retried for idempotent
	 *                                     operations.
	 * @param balancer                 Endpoint balancer.
	 * @param readTimeoutScope         Scope that applies a call's own read
	 *                                     timeout to its attempts.
	 * @param metrics                  Metrics.
	 */
	public ServiceClientFailoverExecutor(
			final boolean isEnabled,
			final int maxAttempts,
			final String[] idempotentMethods,
			final String[] retryStatuses,
			final String[] cooldownStatuses,
			final boolean shouldRetryOnReadTimeout,
			final ServiceClientEndpointBalancer balancer,
			final ServiceClientReadTimeoutScope readTimeoutScope,
			final ServiceClientMetrics metrics) {
		this(isEnabled, maxAttempts, idempotentMethods, retryStatuses, cooldownStatuses, shouldRetryOnReadTimeout, Duration.ZERO, balancer,
				ServiceClientEndpointResolver.WITHOUT_LAST_ENDPOINTS, readTimeoutScope, metrics);
	}

	/**
	 * Constructor without metrics.
	 *
	 * @param isEnabled                If failover is enabled.
	 * @param maxAttempts              Maximum attempts per operation.
	 * @param idempotentMethods        Methods taken as idempotent when the
	 *                                     operation does not say.
	 * @param retryStatuses            Response statuses retried for idempotent
	 *                                     operations.
	 * @param cooldownStatuses         Response statuses that put the endpoint in
	 *                                     its cooldown.
	 * @param shouldRetryOnReadTimeout If read timeouts are retried for idempotent
	 *                                     operations.
	 * @param balancer                 Endpoint balancer.
	 * @param readTimeoutScope         Scope that applies a call's own read
	 *                                     timeout to its attempts.
	 */
	public ServiceClientFailoverExecutor(
			final boolean isEnabled,
			final int maxAttempts,
			final String[] idempotentMethods,
			final String[] retryStatuses,
			final String[] cooldownStatuses,
			final boolean shouldRetryOnReadTimeout,
			final ServiceClientEndpointBalancer balancer,
			final ServiceClientReadTimeoutScope readTimeoutScope) {
		this(isEnabled, maxAttempts, idempotentMethods, retryStatuses, cooldownStatuses, shouldRetryOnReadTimeout, balancer, readTimeoutScope,
				ServiceClientMetrics.NONE);
	}

	/**
	 * Parses a list of response statuses (blank entries ignored).
	 *
	 * @param  statuses Statuses ({@code null} for none).
	 * @return          The statuses.
	 */
	private static Set<Integer> parseStatuses(
			final String[] statuses) {
		return Arrays.stream(statuses == null ? new String[] {} : statuses).filter(StringUtils::isNotBlank).map(status -> Integer.valueOf(status.trim()))
				.collect(Collectors.toSet());
	}

	/**
	 * Gets if a method is taken as idempotent when the operation does not say.
	 *
	 * @param  method HTTP method.
	 * @return        If the method is taken as idempotent.
	 */
	public boolean isIdempotentMethod(
			final HttpMethod method) {
		return this.idempotentMethods.contains(method);
	}

	/**
	 * Gets the time budget of an operation, over all its attempts.
	 *
	 * @return The time budget of an operation (zero or negative for none).
	 */
	public Duration getMaxTotalTime() {
		return this.maxTotalTime;
	}

	/**
	 * Gets how the request's deadline is applied to the calls.
	 *
	 * @return How the request's deadline is applied to the calls.
	 */
	public ServiceClientRequestDeadlinePolicy getDeadlinePolicy() {
		return this.deadlinePolicy;
	}

	/**
	 * Gets the endpoint resolver.
	 *
	 * @return The endpoint resolver.
	 */
	public ServiceClientEndpointResolver getEndpointResolver() {
		return this.endpointResolver;
	}

	/**
	 * Gets the concurrency limiter.
	 *
	 * @return The concurrency limiter.
	 */
	public ServiceClientConcurrencyLimiter getConcurrencyLimiter() {
		return this.concurrencyLimiter;
	}

	/**
	 * Gets the endpoints of a list: trimmed, without blank or repeated ones, and a
	 * single blank endpoint when none is left (the path is then the whole URL), as
	 * before; the executor no longer uses it.
	 *
	 * @param      endpoints Endpoints of the list ({@code null} for none).
	 * @return               The endpoints of the list.
	 * @deprecated           Use
	 *                           {@link ServiceClientEndpointResolver#getAvailableEndpoints(List)},
	 *                           which returns an empty list when none is left.
	 */
	@Deprecated
	public List<String> getAvailableEndpoints(
			final List<String> endpoints) {
		final List<String> availableEndpoints = ServiceClientEndpointResolver.getAvailableEndpoints(endpoints);
		return (availableEndpoints.isEmpty() ? List.of("") : availableEndpoints);
	}

	/**
	 * Gets what is left of an operation's time budget.
	 *
	 * @param  startNanos When the operation started ({@link System#nanoTime()}).
	 * @return            What is left of the budget ({@code null} with no budget;
	 *                    zero or negative when it is spent).
	 */
	private Duration getRemainingTime(
			final long startNanos) {
		return (this.maxTotalTime.isPositive() ? this.maxTotalTime.minusNanos(System.nanoTime() - startNanos) : null);
	}

	/**
	 * Gets the maximum attempts for an operation: the configured maximum, capped by
	 * the number of endpoints (one if failover is disabled).
	 *
	 * @param  endpointsCount Number of endpoints.
	 * @return                The maximum attempts for an operation.
	 */
	public int getMaxAttempts(
			final int endpointsCount) {
		return (this.isEnabled ? Math.max(1, Math.min(this.maxAttempts, endpointsCount)) : 1);
	}

	/**
	 * Chooses the endpoint of the next attempt, among the endpoints not tried yet
	 * (see {@link ServiceClientEndpointBalancer}).
	 *
	 * @param  endpoints      Endpoints (not empty).
	 * @param  triedEndpoints Endpoints already tried.
	 * @return                The endpoint of the next attempt.
	 */
	public String chooseEndpoint(
			final List<String> endpoints,
			final List<String> triedEndpoints) {
		return this.balancer.choose(endpoints, triedEndpoints);
	}

	/**
	 * Gets if a request body can be written more than once: a stream, an open
	 * resource (or such a part of a multipart body) can be read only once.
	 *
	 * @param  body Request body.
	 * @return      If the request body can be written more than once.
	 */
	private static boolean isRepeatableBody(
			final Object body) {
		final Object actualBody = (body instanceof final HttpEntity<?> entity ? entity.getBody() : body);
		final boolean isRepeatable;
		if (actualBody instanceof final MultiValueMap<?, ?> parts) {
			isRepeatable = parts.values().stream().flatMap(Collection::stream).allMatch(ServiceClientFailoverExecutor::isRepeatableBody);
		}
		else {
			isRepeatable = !((actualBody instanceof final Resource resource) && resource.isOpen()) && !(actualBody instanceof InputStream);
		}
		return isRepeatable;
	}

	/**
	 * Gets if a template writes the body into a buffer before connecting
	 * (interceptors or a buffering request factory): a stream body is then read by
	 * the first attempt, even if it never connects.
	 *
	 * @param  restTemplate Template.
	 * @return              If the template buffers the body before connecting.
	 */
	private static boolean isBufferingTemplate(
			final RestOperations restTemplate) {
		return (restTemplate instanceof final InterceptingHttpAccessor accessor)
				&& (!accessor.getInterceptors().isEmpty() || (accessor.getRequestFactory() instanceof BufferingClientHttpRequestFactory));
	}

	/**
	 * Gets if a failure (or any of its causes) is of one of the given types.
	 *
	 * @param  failure      Failure.
	 * @param  failureTypes Failure types.
	 * @return              If the failure (or any of its causes) is of one of the
	 *                      given types.
	 */
	protected static boolean isCausedBy(
			final Throwable failure,
			final List<Class<? extends Throwable>> failureTypes) {
		boolean isCaused = false;
		final Set<Throwable> visitedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable currentFailure = failure;
		while ((currentFailure != null) && !isCaused && visitedFailures.add(currentFailure)) {
			final Throwable checkedFailure = currentFailure;
			isCaused = failureTypes.stream().anyMatch(failureType -> failureType.isInstance(checkedFailure));
			currentFailure = currentFailure.getCause();
		}
		return isCaused;
	}

	/**
	 * Gets the kind of an attempt failure.
	 *
	 * @param  failure Attempt failure.
	 * @return         The kind of the failure.
	 */
	private static FailureKind getFailureKind(
			final Exception failure) {
		final FailureKind failureKind;
		if (ServiceClientFailoverExecutor.isCausedBy(failure, ServiceClientFailoverExecutor.NOT_CONNECTED_FAILURES)) {
			failureKind = FailureKind.NOT_CONNECTED;
		}
		else if (failure instanceof RestClientResponseException) {
			failureKind = FailureKind.STATUS;
		}
		else if (ServiceClientFailoverExecutor.isCausedBy(failure, List.of(SocketTimeoutException.class))) {
			failureKind = FailureKind.READ_TIMEOUT;
		}
		else if (ServiceClientFailoverExecutor.isCausedBy(failure, ServiceClientFailoverExecutor.DROPPED_CONNECTION_FAILURES)) {
			failureKind = FailureKind.DROPPED;
		}
		else {
			failureKind = FailureKind.OTHER;
		}
		return failureKind;
	}

	/**
	 * Gets if a failed attempt should be retried on another endpoint.
	 * <ul>
	 * <li>Connection never established (refused, connect timeout, no route,
	 * unknown host, no pooled connection in time): always, nothing was sent.</li>
	 * <li>A body that can be written only once: nothing else (it was read).</li>
	 * <li>Read timeout: only if enabled and the operation is idempotent.</li>
	 * <li>Connection dropped before a response, or a retry status: only if the
	 * operation is idempotent.</li>
	 * <li>Anything else: never.</li>
	 * </ul>
	 *
	 * @param  failure          Attempt failure.
	 * @param  isIdempotent     If the operation is idempotent.
	 * @param  isBodyRepeatable If the body can be written more than once.
	 * @return                  If the attempt should be retried on another
	 *                          endpoint.
	 */
	public boolean shouldRetry(
			final Exception failure,
			final boolean isIdempotent,
			final boolean isBodyRepeatable) {
		final FailureKind failureKind = ServiceClientFailoverExecutor.getFailureKind(failure);
		final boolean isRetriable;
		if (failureKind == FailureKind.NOT_CONNECTED) {
			isRetriable = true;
		}
		else if (!isIdempotent || !isBodyRepeatable) {
			isRetriable = false;
		}
		else if (failureKind == FailureKind.STATUS) {
			isRetriable = this.retryStatuses.contains(((RestClientResponseException) failure).getStatusCode().value());
		}
		else if (failureKind == FailureKind.READ_TIMEOUT) {
			isRetriable = this.shouldRetryOnReadTimeout;
		}
		else {
			isRetriable = (failureKind == FailureKind.DROPPED);
		}
		return isRetriable;
	}

	/**
	 * Gets an endpoint (or URL, URI templates included) as logged: its scheme, host
	 * and port only (any user information, path or query it holds is left out).
	 * Never fails: a value that cannot be parsed is logged as a placeholder.
	 *
	 * @param  endpoint Endpoint.
	 * @return          The endpoint as logged.
	 */
	public static String getLoggableEndpoint(
			final String endpoint) {
		String loggableEndpoint = "(endpoint not parsed)";
		try {
			// Only the part before any path template or query is parsed.
			final String endpointPrefix = StringUtils.substringBefore(StringUtils.substringBefore(Objects.toString(endpoint, ""), "?"), "{");
			final URI endpointUri = URI.create(endpointPrefix);
			if ((endpointUri.getScheme() != null) && (endpointUri.getHost() != null)) {
				loggableEndpoint = endpointUri.getScheme() + "://" + endpointUri.getHost() + (endpointUri.getPort() < 0 ? "" : ":" + endpointUri.getPort());
			}
		}
		catch (final IllegalArgumentException exception) {
			ServiceClientFailoverExecutor.LOGGER.debug("Endpoint not parsed for the log.", exception);
		}
		return loggableEndpoint;
	}

	/**
	 * Records metrics. A failure to record is logged and does not change the call
	 * (its outcome, or the balancer's bookkeeping): the metrics only observe it.
	 *
	 * @param recording Metrics recording.
	 */
	private void recordMetrics(
			final Runnable recording) {
		try {
			recording.run();
		}
		catch (final RuntimeException exception) {
			ServiceClientFailoverExecutor.LOGGER.warn("Service client metrics not recorded ({}).", exception.getClass().getSimpleName());
			ServiceClientFailoverExecutor.LOGGER.debug("Service client metrics not recorded.", exception);
		}
	}

	/**
	 * Records the outcome of an attempt for the balancing: an endpoint that could
	 * not be connected to or answered a cooldown status starts its cooldown, an
	 * endpoint that answered anything else ends it (if the request was sent after
	 * the cooldown started). An attempt that says nothing about the endpoint's
	 * health (the base of an absolute path, which is no listed instance, or a
	 * connect timeout of a later attempt, which the time budget may have cut)
	 * starts none.
	 *
	 * @param target           Target of the call (for the metrics).
	 * @param inFlightRequest  The attempt's request.
	 * @param canStartCooldown If the attempt can start the endpoint's cooldown.
	 * @param failure          Attempt failure ({@code null} if it succeeded).
	 */
	private void recordOutcome(
			final String target,
			final ServiceClientEndpointBalancer.InFlightRequest inFlightRequest,
			final boolean canStartCooldown,
			final Exception failure) {
		final boolean shouldStartCooldown;
		if ((failure instanceof final RestClientResponseException responseFailure) && this.cooldownStatuses.contains(responseFailure.getStatusCode().value())) {
			shouldStartCooldown = true;
		}
		else if ((failure == null) || (failure instanceof RestClientResponseException)) {
			shouldStartCooldown = false;
			this.balancer.endCooldown(inFlightRequest);
		}
		else {
			shouldStartCooldown = ServiceClientFailoverExecutor.isCausedBy(failure, ServiceClientFailoverExecutor.COOLDOWN_FAILURES);
		}
		if (shouldStartCooldown && canStartCooldown) {
			this.balancer.startCooldown(inFlightRequest.getEndpoint());
			this.recordMetrics(() -> this.metrics.recordCooldown(target));
		}
		final String outcome = (failure == null ? ServiceClientFailoverExecutor.SUCCESS : ServiceClientFailoverExecutor.getFailureKind(failure).tagValue);
		this.recordMetrics(() -> this.metrics.recordAttempt(target, outcome));
	}

	/**
	 * Gets if an attempt failed on a connect timeout that may have been cut short
	 * by the time budget: a later attempt of an operation with a budget, through a
	 * scope that applies it (its waits are then capped to what is left of it). The first attempt's waits are never cut
	 * (the budget starts with it, and the shipped one exceeds the pool wait plus
	 * the connect timeout), so its connect timeout is the endpoint's; a refused
	 * connection, no route or an unknown host are the endpoint's on any attempt.
	 * This holds while the budget covers the pool wait plus the connect timeout
	 * (warned about at startup otherwise).
	 *
	 * @param  failure       Attempt failure ({@code null} if it succeeded).
	 * @param  attemptIndex  Index of the attempt (from zero).
	 * @param  remainingTime Connection wait limit of the attempt ({@code null}
	 *                           with no budget).
	 * @return               If the attempt failed on a connect timeout the budget
	 *                       may have cut.
	 */
	private boolean isConnectMaybeCutByBudget(
			final Exception failure,
			final int attemptIndex,
			final Duration remainingTime) {
		return (failure != null) && (attemptIndex > 0) && (remainingTime != null) && this.readTimeoutScope.appliesConnectionWaitLimit()
				&& ServiceClientFailoverExecutor.isCausedBy(failure, List.of(ConnectTimeoutException.class));
	}

	/**
	 * Gets if an attempt failed on a connect timeout cut short by the request's
	 * deadline: through a scope that applies the limit, it took (about) all the
	 * time the deadline left it, so the deadline, not the endpoint's own connect
	 * timeout, ended it. A connect timeout well within the deadline is the
	 * endpoint's.
	 *
	 * @param  failure               Attempt failure ({@code null} if it
	 *                                   succeeded).
	 * @param  deadlineRemainingTime What was left of the deadline when the attempt
	 *                                   started ({@code null} for none).
	 * @param  attemptStartNanos     When the attempt started
	 *                                   ({@link System#nanoTime()}).
	 * @return                       If the attempt failed on a connect timeout cut
	 *                               short by the deadline.
	 */
	private boolean isConnectCutByDeadline(
			final Exception failure,
			final Duration deadlineRemainingTime,
			final long attemptStartNanos) {
		return (failure != null) && (deadlineRemainingTime != null) && this.readTimeoutScope.appliesConnectionWaitLimit()
				&& ServiceClientFailoverExecutor.isCausedBy(failure, List.of(ConnectTimeoutException.class))
				&& (Duration.ofNanos(System.nanoTime() - attemptStartNanos).compareTo(deadlineRemainingTime.minus(ServiceClientFailoverExecutor.DEADLINE_CUT_MARGIN)) >= 0);
	}

	/**
	 * Gets if a remaining time is spent (under one millisecond).
	 *
	 * @param  remainingTime Remaining time ({@code null} for no limit).
	 * @return               If there is a limit and it is spent.
	 */
	private static boolean isSpent(
			final Duration remainingTime) {
		return (remainingTime != null) && (remainingTime.compareTo(ServiceClientFailoverExecutor.LEAST_REMAINING_TIME) < 0);
	}

	/**
	 * Gets the shorter of two limits.
	 *
	 * @param  first  First limit ({@code null} for none).
	 * @param  second Second limit ({@code null} for none).
	 * @return        The shorter limit ({@code null} if there is none).
	 */
	private static Duration getShorter(
			final Duration first,
			final Duration second) {
		return (first == null ? second : ((second == null) || (first.compareTo(second) <= 0) ? first : second));
	}

	/**
	 * Gets if an operation's time budget is spent.
	 *
	 * @param  startNanos When the operation started ({@link System#nanoTime()}).
	 * @return            If the operation has a time budget and it is spent.
	 */
	private boolean isTimeSpent(
			final long startNanos) {
		return ServiceClientFailoverExecutor.isSpent(this.getRemainingTime(startNanos));
	}

	/**
	 * Fails a call at once, with no request, when the deadline of the request the
	 * current thread serves is spent.
	 *
	 * @param  target               Target of the call.
	 * @param  method               Method of the call.
	 * @param  deadlineRemainingTime What is left of the deadline ({@code null}
	 *                                  for none).
	 * @throws IntegrationException If the deadline is spent (status
	 *                                  {@value #DEADLINE_EXPIRED_STATUS}).
	 */
	private void failIfDeadlineSpent(
			final String target,
			final HttpMethod method,
			final Duration deadlineRemainingTime) throws IntegrationException {
		if (ServiceClientFailoverExecutor.isSpent(deadlineRemainingTime)) {
			this.recordMetrics(() -> this.metrics.recordDeadlineExpired(target, ServiceClientFailoverExecutor.DEADLINE_STAGE_CALL));
			ServiceClientFailoverExecutor.LOGGER.debug("REST operation {} to {} not made: the request's deadline is spent ({} ms left, margin {} ms).", method, target,
					deadlineRemainingTime.toMillis(), this.deadlinePolicy.getMargin().toMillis());
			throw new IntegrationException(new SimpleMessage(ServiceClientFailoverExecutor.DEADLINE_EXPIRED_CODE,
					"Call to " + target + " not made: the request's deadline is spent."), ServiceClientFailoverExecutor.DEADLINE_EXPIRED_STATUS);
		}
	}

	/**
	 * Takes the concurrency limit permit of an operation. A failure inside the
	 * limiter is counted and the operation proceeds without a permit (deliberate
	 * fail-open); only the limiter's rejection propagates.
	 *
	 * @param  target               Target of the operation.
	 * @return                      The permit of the operation ({@code null} for
	 *                              none).
	 * @throws IntegrationException If the limiter rejects the operation.
	 */
	private ServiceClientConcurrencyLimiter.Permit acquireConcurrencyPermit(
			final String target) throws IntegrationException {
		ServiceClientConcurrencyLimiter.Permit concurrencyPermit = null;
		try {
			concurrencyPermit = this.concurrencyLimiter.acquire(target);
		}
		catch (final RuntimeException exception) {
			if (ServiceClientConcurrencyLimiter.isRejection(exception)) {
				throw exception;
			}
			this.concurrencyLimiter.recordFailure(target, exception);
		}
		return concurrencyPermit;
	}

	/**
	 * Records an attempt in the concurrency limit. A latency sample: the target
	 * answered (any status), or did not in the read timeout. An overload outcome: a
	 * read timeout, no pooled connection in time, or an overload status. Anything
	 * else (connect failures, a dropped connection, a connect cut by the budget)
	 * records nothing. A failure inside the limiter is counted and ignored.
	 *
	 * @param target            Target of the operation.
	 * @param concurrencyPermit Permit of the operation ({@code null} for none).
	 * @param failure           Attempt failure ({@code null} if it succeeded).
	 * @param attemptStartNanos When the attempt started
	 *                              ({@link System#nanoTime()}).
	 */
	private void recordConcurrencyLimitAttempt(
			final String target,
			final ServiceClientConcurrencyLimiter.Permit concurrencyPermit,
			final Exception failure,
			final long attemptStartNanos) {
		// No permit: the outcome is not even classified.
		if (concurrencyPermit != null) {
			try {
				final boolean isLatencySample;
				final boolean isOverload;
				if (failure == null) {
					isLatencySample = true;
					isOverload = false;
				}
				else if (failure instanceof final RestClientResponseException responseFailure) {
					isLatencySample = true;
					isOverload = this.concurrencyLimiter.isOverloadStatus(responseFailure.getStatusCode().value());
				}
				else if (ServiceClientFailoverExecutor.isCausedBy(failure, ServiceClientFailoverExecutor.POOL_WAIT_FAILURES)) {
					isLatencySample = false;
					isOverload = true;
				}
				else {
					isLatencySample = (ServiceClientFailoverExecutor.getFailureKind(failure) == FailureKind.READ_TIMEOUT);
					isOverload = isLatencySample;
				}
				this.concurrencyLimiter.recordAttempt(concurrencyPermit, System.nanoTime() - attemptStartNanos, isLatencySample, isOverload);
			}
			catch (final RuntimeException exception) {
				this.concurrencyLimiter.recordFailure(target, exception);
			}
		}
	}

	/**
	 * Gives the concurrency limit permit of an operation back. A failure inside
	 * the limiter is counted and ignored.
	 *
	 * @param target      Target of the operation.
	 * @param concurrencyPermit Permit of the operation ({@code null} for none).
	 */
	private void releaseConcurrencyPermit(
			final String target,
			final ServiceClientConcurrencyLimiter.Permit concurrencyPermit) {
		try {
			this.concurrencyLimiter.release(concurrencyPermit);
		}
		catch (final RuntimeException exception) {
			this.concurrencyLimiter.recordFailure(target, exception);
		}
	}

	/**
	 * Executes the attempts of an operation (see {@link #executeOverEndpoints}).
	 *
	 * @param  <ResponseType>       Response type.
	 * @param  endpoints            Endpoints (base URLs).
	 * @param  path                 Path (appended to the endpoint).
	 * @param  method               Method.
	 * @param  requestEntity        Request headers and body.
	 * @param  responseType         Response type.
	 * @param  uriParameters        URI parameters.
	 * @param  declaredIdempotent   If the operation is idempotent, as declared
	 *                                  ({@code null} if not: decided by the
	 *                                  method).
	 * @param  readTimeout          Read timeout of the attempts ({@code null},
	 *                                  zero or negative keeps the template's).
	 * @param  restTemplate         Template.
	 * @param  concurrencyPermit    Permit of the operation ({@code null} for
	 *                                  none).
	 * @param  firstDeadlineRemainingTime What was left of the request's deadline
	 *                                  at the check before the permit (used by the
	 *                                  first attempt; {@code null} for none).
	 * @return                      The outcome: the response, or the failure of
	 *                              the last attempt.
	 * @throws IntegrationException If there is no endpoint to go to.
	 */
	private <ResponseType> Outcome<ResponseType> executeAttempts(
			final List<String> endpoints,
			final String path,
			final HttpMethod method,
			final HttpEntity<?> requestEntity,
			final ParameterizedTypeReference<ResponseType> responseType,
			final Map<String, Object> uriParameters,
			final Boolean declaredIdempotent,
			final Duration readTimeout,
			final RestOperations restTemplate,
			final ServiceClientConcurrencyLimiter.Permit concurrencyPermit,
			final Duration firstDeadlineRemainingTime) throws IntegrationException {
		final long startNanos = System.nanoTime();
		final String target = ServiceClientTarget.getCurrent();
		// With no endpoint in the list, a path that is an absolute URL is called as
		// is (its base as the only endpoint); it is not the target's list.
		final Matcher absoluteUrl = ServiceClientFailoverExecutor.ABSOLUTE_URL.matcher(Objects.toString(path, ""));
		final boolean isAbsolutePathCall = ServiceClientEndpointResolver.getAvailableEndpoints(endpoints).isEmpty() && absoluteUrl.matches();
		final List<String> availableEndpoints = (isAbsolutePathCall ? List.of(absoluteUrl.group(1)) : this.endpointResolver.resolve(target, endpoints));
		final String actualPath = (isAbsolutePathCall ? absoluteUrl.group(2) : Objects.toString(path, ""));
		final boolean isBodyRepeatable = ServiceClientFailoverExecutor.isRepeatableBody(requestEntity.getBody());
		final int maxAttemptsForCall = (isBodyRepeatable || !ServiceClientFailoverExecutor.isBufferingTemplate(restTemplate)
				? this.getMaxAttempts(availableEndpoints.size())
				: 1);
		final boolean isIdempotent = (declaredIdempotent == null ? this.isIdempotentMethod(method) : declaredIdempotent);
		final List<String> triedEndpoints = new ArrayList<>();
		Outcome<ResponseType> outcome = null;
		for (int attemptIndex = 0; outcome == null; attemptIndex++) {
			final String endpoint = this.chooseEndpoint(availableEndpoints, triedEndpoints);
			final String url = endpoint + actualPath;
			triedEndpoints.add(endpoint);
			final ServiceClientEndpointBalancer.InFlightRequest inFlightRequest = this.balancer.startRequest(endpoint);
			ResponseEntity<ResponseType> response = null;
			Exception attemptFailure = null;
			// The request's deadline caps the attempt's waits, and so the budget.
			final Duration deadlineRemainingTime = (attemptIndex == 0 ? firstDeadlineRemainingTime : this.deadlinePolicy.getRemainingTime());
			final Duration budgetRemainingTime = this.getRemainingTime(startNanos);
			final Duration remainingTime = ServiceClientFailoverExecutor.getShorter(budgetRemainingTime, deadlineRemainingTime);
			final HttpEntity<?> attemptEntity = ((deadlineRemainingTime == null) || isAbsolutePathCall ? requestEntity
					: this.deadlinePolicy.withHeader(requestEntity, readTimeout, deadlineRemainingTime));
			final long attemptStartNanos = System.nanoTime();
			try {
				this.recordMetrics(() -> this.metrics.recordRequestStarted(target));
				response = this.readTimeoutScope.runWithTimeouts(restTemplate, readTimeout, deadlineRemainingTime, remainingTime, () -> (maxAttemptsForCall > 1
						? ServiceClientRetryStrategy.runWithoutRetries(() -> restTemplate.exchange(url, method, attemptEntity, responseType, uriParameters))
						: restTemplate.exchange(url, method, attemptEntity, responseType, uriParameters)));
			}
			catch (final Exception exception) {
				attemptFailure = exception;
			}
			finally {
				inFlightRequest.close();
				this.recordMetrics(() -> this.metrics.recordRequestEnded(target));
			}
			// Records the outcome after the attempt, so the bookkeeping cannot change it.
			this.recordOutcome(target, inFlightRequest,
					!isAbsolutePathCall && !this.isConnectMaybeCutByBudget(attemptFailure, attemptIndex, budgetRemainingTime)
							&& !this.isConnectCutByDeadline(attemptFailure, deadlineRemainingTime, attemptStartNanos),
					attemptFailure);
			this.recordConcurrencyLimitAttempt(target, concurrencyPermit, attemptFailure, attemptStartNanos);
			if (attemptFailure == null) {
				outcome = new Outcome<>(url, response, null);
			}
			else if (((attemptIndex + 1) >= maxAttemptsForCall) || !this.shouldRetry(attemptFailure, isIdempotent, isBodyRepeatable)) {
				outcome = new Outcome<>(url, null, attemptFailure);
			}
			else if (this.isTimeSpent(startNanos)) {
				outcome = new Outcome<>(url, null, attemptFailure);
				final String spentReason = ServiceClientFailoverExecutor.getFailureKind(attemptFailure).tagValue;
				this.recordMetrics(() -> this.metrics.recordAttemptsStoppedByTimeLimit(target, spentReason));
				ServiceClientFailoverExecutor.LOGGER.warn("REST operation {} on {} failed on attempt {} of {} ({}), and its time budget ({} ms) is spent: no other endpoint tried.",
						method, ServiceClientFailoverExecutor.getLoggableEndpoint(endpoint), attemptIndex + 1, maxAttemptsForCall,
						attemptFailure.getClass().getSimpleName(), this.maxTotalTime.toMillis());
			}
			else if (ServiceClientFailoverExecutor.isSpent(this.deadlinePolicy.getRemainingTime())) {
				outcome = new Outcome<>(url, null, attemptFailure);
				this.recordMetrics(() -> this.metrics.recordDeadlineExpired(target, ServiceClientFailoverExecutor.DEADLINE_STAGE_RETRY));
				ServiceClientFailoverExecutor.LOGGER.warn(
						"REST operation {} on {} failed on attempt {} of {} ({}), and the request's deadline is spent: no other endpoint tried.", method,
						ServiceClientFailoverExecutor.getLoggableEndpoint(endpoint), attemptIndex + 1, maxAttemptsForCall, attemptFailure.getClass().getSimpleName());
			}
			else {
				final String retryReason = ServiceClientFailoverExecutor.getFailureKind(attemptFailure).tagValue;
				this.recordMetrics(() -> this.metrics.recordRetry(target, retryReason));
				// Logs the endpoint's scheme, host and port only, not the URL nor the
				// message (they may carry path parameters or credentials).
				ServiceClientFailoverExecutor.LOGGER.warn("REST operation {} on {} failed on attempt {} of {} ({}), trying another endpoint.", method,
						ServiceClientFailoverExecutor.getLoggableEndpoint(endpoint), attemptIndex + 1, maxAttemptsForCall, attemptFailure.getClass().getSimpleName());
			}
		}
		return outcome;
	}

	/**
	 * Executes an operation over a list of endpoints, up to the maximum attempts,
	 * each on an endpoint not tried yet. With more than one attempt, the HTTP client
	 * retries are suspended (see {@link ServiceClientRetryStrategy}), so attempts
	 * are not doubled; a single attempt keeps them. A stream body gets a single
	 * attempt when the template buffers it. Attempts, retries, cooldowns and
	 * requests in flight are recorded in the {@link ServiceClientMetrics}, under the
	 * current thread's {@link ServiceClientTarget}. The endpoints come from
	 * the {@link ServiceClientEndpointResolver} (no request when there are none),
	 * except that a path that is an absolute URL, with no endpoint in the list, is
	 * called as is (its host is then not put in cooldown: it is no listed
	 * instance).
	 * With a time budget, no further attempt starts once it is spent, and every
	 * attempt waits for a pooled connection and for its connection no longer than
	 * what is left of it (through the executor's
	 * {@link ServiceClientReadTimeoutScope}).
	 * The operation holds one permit of its target's
	 * {@link ServiceClientConcurrencyLimiter} across its attempts; a rejection
	 * (enforce mode only) fails it before any request, with no attempt.
	 * With a request deadline ({@link ServiceClientRequestDeadline}), what is left
	 * of it, less the margin, is checked before the permit and before each
	 * attempt: spent before the first one, the call fails at once with no request
	 * (and takes no permit); spent before a retry, no other endpoint is tried.
	 * Otherwise it caps the attempt's read timeout and connection waits, and the
	 * shorter of it and the attempt's read timeout (the call's own, or else the
	 * default template's) is sent in the
	 * {@value ServiceClientRequestDeadline#HEADER} header (not to the base of an
	 * absolute path, which may be a third party).
	 *
	 * @param  <ResponseType> Response type.
	 * @param  endpoints      Endpoints (base URLs).
	 * @param  path           Path (appended to the endpoint).
	 * @param  method         Method.
	 * @param  requestEntity  Request headers and body.
	 * @param  responseType   Response type.
	 * @param  uriParameters  URI parameters.
	 * @param  declaredIdempotent If the operation is idempotent, as declared
	 *                                ({@code null} if not: decided by the method).
	 * @param  readTimeout    Read timeout of the attempts ({@code null}, zero or
	 *                            negative keeps the template's; applied by the
	 *                            executor's {@link ServiceClientReadTimeoutScope}).
	 * @param  restTemplate   Template.
	 * @return                The outcome: the response, or the failure of the last
	 *                        attempt.
	 * @throws IntegrationException If there is no endpoint to go to (see
	 *                                  {@link ServiceClientEndpointResolver}), or
	 *                                  the concurrency limiter rejects the
	 *                                  operation
	 *                                  ({@value ServiceClientConcurrencyLimiter#REJECTED_CODE}),
	 *                                  or the request's deadline is spent before
	 *                                  the first attempt (status
	 *                                  {@value #DEADLINE_EXPIRED_STATUS}).
	 */
	public <ResponseType> Outcome<ResponseType> executeOverEndpoints(
			final List<String> endpoints,
			final String path,
			final HttpMethod method,
			final HttpEntity<?> requestEntity,
			final ParameterizedTypeReference<ResponseType> responseType,
			final Map<String, Object> uriParameters,
			final Boolean declaredIdempotent,
			final Duration readTimeout,
			final RestOperations restTemplate) throws IntegrationException {
		final String target = ServiceClientTarget.getCurrent();
		// The deadline is checked before the permit, so a call not made never
		// counts in the concurrency limit; read once for the check and the first
		// attempt, so a call that passes the check is made with what it saw.
		final Duration firstDeadlineRemainingTime = this.deadlinePolicy.getRemainingTime();
		this.failIfDeadlineSpent(target, method, firstDeadlineRemainingTime);
		final ServiceClientConcurrencyLimiter.Permit concurrencyPermit = this.acquireConcurrencyPermit(target);
		try {
			return this.executeAttempts(endpoints, path, method, requestEntity, responseType, uriParameters, declaredIdempotent, readTimeout, restTemplate,
					concurrencyPermit, firstDeadlineRemainingTime);
		}
		finally {
			this.releaseConcurrencyPermit(target, concurrencyPermit);
		}
	}

}
