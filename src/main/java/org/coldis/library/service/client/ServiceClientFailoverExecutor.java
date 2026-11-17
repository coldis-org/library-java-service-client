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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.NoHttpResponseException;
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
	public static final ServiceClientFailoverExecutor SINGLE_ATTEMPT = new ServiceClientFailoverExecutor(false, 1, null, null, false,
			new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.RANDOM, Duration.ZERO), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE);

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
	 * Failures where the connection dropped before a response (the request may have
	 * been processed).
	 */
	private static final List<Class<? extends Throwable>> DROPPED_CONNECTION_FAILURES = List.of(NoHttpResponseException.class,
			ConnectionClosedException.class, SocketException.class);

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
			final boolean shouldRetryOnReadTimeout,
			final ServiceClientEndpointBalancer balancer,
			final ServiceClientReadTimeoutScope readTimeoutScope) {
		super();
		this.balancer = Objects.requireNonNull(balancer, "balancer");
		this.readTimeoutScope = Objects.requireNonNull(readTimeoutScope, "readTimeoutScope");
		this.isEnabled = isEnabled;
		this.maxAttempts = maxAttempts;
		this.idempotentMethods = Arrays.stream(idempotentMethods == null ? new String[] {} : idempotentMethods).filter(StringUtils::isNotBlank)
				.map(method -> HttpMethod.valueOf(method.trim().toUpperCase(Locale.ROOT))).collect(Collectors.toSet());
		this.retryStatuses = Arrays.stream(retryStatuses == null ? new String[] {} : retryStatuses).filter(StringUtils::isNotBlank)
				.map(status -> Integer.valueOf(status.trim())).collect(Collectors.toSet());
		this.shouldRetryOnReadTimeout = shouldRetryOnReadTimeout;
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
	 * Gets the endpoints of a list: trimmed, without blank or repeated ones, and a
	 * single blank endpoint when none is left (the path is then the whole URL).
	 *
	 * @param  endpoints Endpoints of the list.
	 * @return           The endpoints of the list.
	 */
	public List<String> getAvailableEndpoints(
			final List<String> endpoints) {
		final List<String> availableEndpoints = (endpoints == null ? new ArrayList<>()
				: endpoints.stream().filter(Objects::nonNull).map(String::trim).filter(StringUtils::isNotBlank).distinct().collect(Collectors.toList()));
		return (availableEndpoints.isEmpty() ? List.of("") : availableEndpoints);
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
		final boolean isRetriable;
		if (ServiceClientFailoverExecutor.isCausedBy(failure, ServiceClientFailoverExecutor.NOT_CONNECTED_FAILURES)) {
			isRetriable = true;
		}
		else if (!isIdempotent || !isBodyRepeatable) {
			isRetriable = false;
		}
		else if (failure instanceof final RestClientResponseException responseFailure) {
			isRetriable = this.retryStatuses.contains(responseFailure.getStatusCode().value());
		}
		else if (ServiceClientFailoverExecutor.isCausedBy(failure, List.of(SocketTimeoutException.class))) {
			isRetriable = this.shouldRetryOnReadTimeout;
		}
		else {
			isRetriable = ServiceClientFailoverExecutor.isCausedBy(failure, ServiceClientFailoverExecutor.DROPPED_CONNECTION_FAILURES);
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
	 * Records the outcome of an attempt for the balancing: an endpoint that could
	 * not be connected to or answered a retry status starts its cooldown, an
	 * endpoint that answered anything else ends it (if the request was sent after
	 * the cooldown started).
	 *
	 * @param inFlightRequest The attempt's request.
	 * @param failure         Attempt failure ({@code null} if it succeeded).
	 */
	private void recordOutcome(
			final ServiceClientEndpointBalancer.InFlightRequest inFlightRequest,
			final Exception failure) {
		if ((failure instanceof final RestClientResponseException responseFailure) && this.retryStatuses.contains(responseFailure.getStatusCode().value())) {
			this.balancer.startCooldown(inFlightRequest.getEndpoint());
		}
		else if ((failure == null) || (failure instanceof RestClientResponseException)) {
			this.balancer.endCooldown(inFlightRequest);
		}
		else if (ServiceClientFailoverExecutor.isCausedBy(failure, ServiceClientFailoverExecutor.COOLDOWN_FAILURES)) {
			this.balancer.startCooldown(inFlightRequest.getEndpoint());
		}
	}

	/**
	 * Executes an operation over a list of endpoints, up to the maximum attempts,
	 * each on an endpoint not tried yet. With more than one attempt, the HTTP client
	 * retries are suspended (see {@link ServiceClientRetryStrategy}), so attempts
	 * are not doubled; a single attempt keeps them. A stream body gets a single
	 * attempt when the template buffers it.
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
			final RestOperations restTemplate) {
		final List<String> availableEndpoints = this.getAvailableEndpoints(endpoints);
		final boolean isBodyRepeatable = ServiceClientFailoverExecutor.isRepeatableBody(requestEntity.getBody());
		final int maxAttemptsForCall = (isBodyRepeatable || !ServiceClientFailoverExecutor.isBufferingTemplate(restTemplate)
				? this.getMaxAttempts(availableEndpoints.size())
				: 1);
		final boolean isIdempotent = (declaredIdempotent == null ? this.isIdempotentMethod(method) : declaredIdempotent);
		final List<String> triedEndpoints = new ArrayList<>();
		Outcome<ResponseType> outcome = null;
		for (int attemptIndex = 0; outcome == null; attemptIndex++) {
			final String endpoint = this.chooseEndpoint(availableEndpoints, triedEndpoints);
			final String url = endpoint + path;
			triedEndpoints.add(endpoint);
			final ServiceClientEndpointBalancer.InFlightRequest inFlightRequest = this.balancer.startRequest(endpoint);
			ResponseEntity<ResponseType> response = null;
			Exception attemptFailure = null;
			try {
				response = this.readTimeoutScope.runWithReadTimeout(restTemplate, readTimeout, () -> (maxAttemptsForCall > 1
						? ServiceClientRetryStrategy.runWithoutRetries(() -> restTemplate.exchange(url, method, requestEntity, responseType, uriParameters))
						: restTemplate.exchange(url, method, requestEntity, responseType, uriParameters)));
			}
			catch (final Exception exception) {
				attemptFailure = exception;
			}
			finally {
				inFlightRequest.close();
			}
			// Records the outcome after the attempt, so the bookkeeping cannot change it.
			this.recordOutcome(inFlightRequest, attemptFailure);
			if (attemptFailure == null) {
				outcome = new Outcome<>(url, response, null);
			}
			else if (((attemptIndex + 1) >= maxAttemptsForCall) || !this.shouldRetry(attemptFailure, isIdempotent, isBodyRepeatable)) {
				outcome = new Outcome<>(url, null, attemptFailure);
			}
			else {
				// Logs the endpoint's scheme, host and port only, not the URL nor the
				// message (they may carry path parameters or credentials).
				ServiceClientFailoverExecutor.LOGGER.warn("REST operation {} on {} failed on attempt {} of {} ({}), trying another endpoint.", method,
						ServiceClientFailoverExecutor.getLoggableEndpoint(endpoint), attemptIndex + 1, maxAttemptsForCall, attemptFailure.getClass().getSimpleName());
			}
		}
		return outcome;
	}

}
