package org.coldis.library.test.service.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.ConnectionEndpoint;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.MicrometerServiceClientMetrics;
import org.coldis.library.service.client.ServiceClientAutoConfiguration;
import org.coldis.library.service.client.ServiceClientEndpointBalancer;
import org.coldis.library.service.client.ServiceClientFailoverExecutor;
import org.coldis.library.service.client.ServiceClientMetrics;
import org.coldis.library.service.client.ServiceClientReadTimeoutScope;
import org.coldis.library.service.client.ServiceClientRequestFactory;
import org.coldis.library.service.client.ServiceClientTarget;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Failover executor test (plain objects): the retry decision and the attempt
 * limits of a real call.
 */
public class ServiceClientFailoverExecutorTest {

	/**
	 * Response type.
	 */
	private static final ParameterizedTypeReference<String> STRING_RESPONSE = new ParameterizedTypeReference<>() {};

	/**
	 * Executor with the shipped defaults.
	 */
	private static final ServiceClientFailoverExecutor EXECUTOR = new ServiceClientFailoverExecutor(true, 3, new String[] { "GET", "HEAD", "OPTIONS" },
			new String[] { "502", "503", "504" }, new String[] { "502", "503", "504" }, false,
			new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE);

	/**
	 * Creates a template whose every call fails with the given failure, recording
	 * the URL of each call.
	 *
	 * @param  failure       Failure.
	 * @param  attemptedUrls Where the URLs are recorded.
	 * @return               The template.
	 */
	private static RestTemplate createFailingTemplate(
			final RuntimeException failure,
			final List<String> attemptedUrls) {
		return ServiceClientFailoverExecutorTest.createFailingTemplate(new SimpleClientHttpRequestFactory(), failure, attemptedUrls);
	}

	/**
	 * Creates a template over a request factory whose every call fails with the
	 * given failure, recording the URL of each call.
	 *
	 * @param  requestFactory Request factory.
	 * @param  failure        Failure.
	 * @param  attemptedUrls  Where the URLs are recorded.
	 * @return                The template.
	 */
	private static RestTemplate createFailingTemplate(
			final ClientHttpRequestFactory requestFactory,
			final RuntimeException failure,
			final List<String> attemptedUrls) {
		return new RestTemplate(requestFactory) {

			@Override
			public <ResponseType> ResponseEntity<ResponseType> exchange(
					final String url,
					final HttpMethod method,
					final HttpEntity<?> requestEntity,
					final ParameterizedTypeReference<ResponseType> responseType,
					final Map<String, ?> uriVariables) {
				attemptedUrls.add(url);
				throw failure;
			}
		};
	}

	/**
	 * Executes a call through a failing template.
	 *
	 * @param  endpoints     Endpoints.
	 * @param  method        Method.
	 * @param  body          Body.
	 * @param  failure       Failure of every attempt.
	 * @param  attemptedUrls Where the attempted URLs are recorded.
	 * @return               The outcome.
	 */
	private static ServiceClientFailoverExecutor.Outcome<String> execute(
			final List<String> endpoints,
			final HttpMethod method,
			final Object body,
			final RuntimeException failure,
			final List<String> attemptedUrls) {
		return ServiceClientFailoverExecutorTest.EXECUTOR.executeOverEndpoints(endpoints, "/path", method, new HttpEntity<>(body),
				ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null, ServiceClientFailoverExecutorTest.createFailingTemplate(failure, attemptedUrls));
	}

	/**
	 * Retry decisions of the shipped defaults: 502 and 504 like 503 (idempotent
	 * only), no pooled connection in time and an unknown host for any method
	 * (nothing was sent), a 400 never.
	 */
	@Test
	public void testRetryDecisions() {
		final ServiceClientFailoverExecutor executor = ServiceClientFailoverExecutorTest.EXECUTOR;
		for (final HttpStatus status : List.of(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT)) {
			Assertions.assertTrue(executor.shouldRetry(new HttpServerErrorException(status), true, true), status::toString);
			Assertions.assertFalse(executor.shouldRetry(new HttpServerErrorException(status), false, true), status::toString);
		}
		Assertions.assertFalse(executor.shouldRetry(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR), true, true));
		Assertions.assertTrue(
				executor.shouldRetry(new ResourceAccessException("No pooled connection.", new ConnectionRequestTimeoutException("Timeout.")), false, false));
		Assertions.assertTrue(executor.shouldRetry(new ResourceAccessException("Unknown host.", new UnknownHostException("host")), false, false));
	}

	/**
	 * A call over four endpoints that all fail makes three attempts, each on a
	 * different endpoint.
	 */
	@Test
	public void testAttemptsCappedOnDistinctEndpoints() {
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		final ServiceClientFailoverExecutor.Outcome<String> outcome = ServiceClientFailoverExecutorTest.execute(
				List.of("http://first", "http://second", "http://third", "http://fourth"), HttpMethod.GET, null,
				new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE), attemptedUrls);
		Assertions.assertNotNull(outcome.failure());
		Assertions.assertEquals(3, attemptedUrls.size());
		Assertions.assertEquals(3, Set.copyOf(attemptedUrls).size());
	}

	/**
	 * A body that can be read once is not sent again after an answer, even for an
	 * idempotent method, but is after a connection that was never established.
	 *
	 * @throws IOException If the body cannot be created.
	 */
	@Test
	public void testReadOnceBody() throws IOException {
		final List<String> answeredUrls = new CopyOnWriteArrayList<>();
		ServiceClientFailoverExecutorTest.execute(List.of("http://first", "http://second"), HttpMethod.GET,
				new InputStreamResource(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8))),
				new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE), answeredUrls);
		Assertions.assertEquals(1, answeredUrls.size());
		final List<String> refusedUrls = new CopyOnWriteArrayList<>();
		ServiceClientFailoverExecutorTest.execute(List.of("http://first", "http://second"), HttpMethod.POST,
				new InputStreamResource(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8))),
				new ResourceAccessException("Refused.", new ConnectException("Refused.")), refusedUrls);
		Assertions.assertEquals(2, refusedUrls.size());
	}

	/**
	 * Through a template whose request factory buffers the body (no interceptors),
	 * a stream body gets one attempt, even after a refused connection.
	 */
	@Test
	public void testStreamBodyThroughBufferingFactory() {
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		ServiceClientFailoverExecutorTest.EXECUTOR.executeOverEndpoints(List.of("http://first", "http://second"), "/path", HttpMethod.POST,
				new HttpEntity<>(new InputStreamResource(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)))),
				ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
				ServiceClientFailoverExecutorTest.createFailingTemplate(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()),
						new ResourceAccessException("Refused.", new ConnectException("Refused.")), attemptedUrls));
		Assertions.assertEquals(1, attemptedUrls.size());
	}

	/**
	 * Repeated and padded endpoints count once: the attempts reach distinct
	 * endpoints.
	 */
	@Test
	public void testRepeatedEndpoints() {
		Assertions.assertEquals(List.of("http://first", "http://second"),
				ServiceClientFailoverExecutorTest.EXECUTOR.getAvailableEndpoints(List.of("http://first", " http://first ", "http://second", "")));
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		ServiceClientFailoverExecutorTest.execute(List.of("http://first", " http://first ", "http://second"), HttpMethod.GET, null,
				new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE), attemptedUrls);
		Assertions.assertEquals(2, attemptedUrls.size());
		Assertions.assertEquals(2, Set.copyOf(attemptedUrls).size());
	}

	/**
	 * The endpoint logged on a failure keeps only scheme, host and port, from an
	 * endpoint or a templated URL, and a value that cannot be parsed (or none) gives
	 * a placeholder.
	 */
	@Test
	public void testLoggableEndpoint() {
		Assertions.assertEquals("http://host:8080", ServiceClientFailoverExecutor.getLoggableEndpoint("http://user-info@host:8080/base?query=value"));
		Assertions.assertEquals("http://host:8080",
				ServiceClientFailoverExecutor.getLoggableEndpoint("http://host:8080/customers/{id}?document={document}&"));
		Assertions.assertEquals("https://host", ServiceClientFailoverExecutor.getLoggableEndpoint("https://host"));
		Assertions.assertEquals("(endpoint not parsed)", ServiceClientFailoverExecutor.getLoggableEndpoint("host_name:8080"));
		Assertions.assertEquals("(endpoint not parsed)", ServiceClientFailoverExecutor.getLoggableEndpoint("not an endpoint"));
		Assertions.assertEquals("(endpoint not parsed)", ServiceClientFailoverExecutor.getLoggableEndpoint(null));
	}

	/**
	 * Running out of local pooled connections is retried on another endpoint (no
	 * request was sent) but puts no endpoint in its cooldown (it is not the
	 * endpoint's failure); a read timeout, not retried by default, does not
	 * either.
	 */
	@Test
	public void testLocalFailuresStartNoCooldown() {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT,
				Duration.ofSeconds(10));
		final ServiceClientFailoverExecutor executor = new ServiceClientFailoverExecutor(true, 3, new String[] { "GET" }, new String[] { "503" }, new String[] { "503" }, false,
				balancer, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE);
		final List<String> endpoints = List.of("http://first", "http://second");
		final List<String> poolWaitUrls = new CopyOnWriteArrayList<>();
		final ServiceClientFailoverExecutor.Outcome<String> poolWaitOutcome = executor.executeOverEndpoints(endpoints, "/", HttpMethod.GET, HttpEntity.EMPTY,
				ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
				ServiceClientFailoverExecutorTest.createFailingTemplate(new ResourceAccessException("Attempt failed.", new ConnectionRequestTimeoutException("Pool exhausted.")), poolWaitUrls));
		Assertions.assertNotNull(poolWaitOutcome.failure());
		Assertions.assertEquals(2, poolWaitUrls.size());
		Assertions.assertEquals(0, balancer.getCooldownEndpointsCount());
		final List<String> readTimeoutUrls = new CopyOnWriteArrayList<>();
		final ServiceClientFailoverExecutor.Outcome<String> readTimeoutOutcome = executor.executeOverEndpoints(endpoints, "/", HttpMethod.GET, HttpEntity.EMPTY,
				ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
				ServiceClientFailoverExecutorTest.createFailingTemplate(new ResourceAccessException("Attempt failed.", new SocketTimeoutException("Read timed out.")), readTimeoutUrls));
		Assertions.assertNotNull(readTimeoutOutcome.failure());
		Assertions.assertEquals(1, readTimeoutUrls.size());
		Assertions.assertEquals(0, balancer.getCooldownEndpointsCount());
		Assertions.assertEquals(0, balancer.getInFlightRequests("http://first"));
		Assertions.assertEquals(0, balancer.getInFlightRequests("http://second"));
	}

	/**
	 * Creates an executor with no time budget over a balancer with a cooldown.
	 *
	 * @param  balancer Balancer.
	 * @return          The executor.
	 */
	private static ServiceClientFailoverExecutor createExecutor(
			final ServiceClientEndpointBalancer balancer) {
		return new ServiceClientFailoverExecutor(true, 3, new String[] { "GET" }, new String[] { "503" }, new String[] { "503" }, false, balancer,
				ServiceClientRequestFactory.READ_TIMEOUT_SCOPE);
	}

	/**
	 * Executes a call of a target that fails once (not retried), recording the
	 * attempted URLs.
	 *
	 * @param  executor      Executor.
	 * @param  target        Target of the call.
	 * @param  endpoints     Endpoints of the list.
	 * @param  attemptedUrls Where the attempted URLs are recorded.
	 * @return               The outcome.
	 */
	private static ServiceClientFailoverExecutor.Outcome<String> executeFor(
			final ServiceClientFailoverExecutor executor,
			final String target,
			final List<String> endpoints,
			final List<String> attemptedUrls) {
		return ServiceClientTarget.runFor(target,
				() -> executor.executeOverEndpoints(endpoints, "/path", HttpMethod.GET, HttpEntity.EMPTY, ServiceClientFailoverExecutorTest.STRING_RESPONSE,
						Map.of(), null, null, ServiceClientFailoverExecutorTest.createFailingTemplate(new IllegalStateException("Failed."), attemptedUrls)));
	}

	/**
	 * A target whose endpoint list never had an endpoint fails at once, with a clear
	 * integration error and no request (and so does a call with no target, which
	 * has no last list); nothing is put in cooldown.
	 */
	@Test
	public void testNeverConfiguredEndpointsFailFast() {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT,
				Duration.ofSeconds(10));
		final ServiceClientFailoverExecutor executor = ServiceClientFailoverExecutorTest.createExecutor(balancer);
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		for (final List<String> endpoints : Arrays.<List<String>> asList(null, List.of(), List.of(" ", ""))) {
			final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
					() -> ServiceClientFailoverExecutorTest.executeFor(executor, "NeverConfiguredClient", endpoints, attemptedUrls));
			Assertions.assertEquals(ServiceClientFailoverExecutor.NO_ENDPOINTS_CODE, exception.getInternalMessage().getCode());
			Assertions.assertEquals("No endpoints configured for NeverConfiguredClient.", exception.getInternalMessage().getContent());
		}
		ServiceClientFailoverExecutorTest.executeFor(executor, null, List.of("http://first"), attemptedUrls);
		Assertions.assertThrows(IntegrationException.class, () -> ServiceClientFailoverExecutorTest.executeFor(executor, null, List.of(), attemptedUrls));
		Assertions.assertEquals(List.of("http://first/path"), attemptedUrls);
	}

	/**
	 * With no endpoint in the list, a path that is an absolute URL is called as is
	 * (as before the last-list fallback), and it does not become the target's last
	 * list.
	 */
	@Test
	public void testAbsolutePathWithoutList() {
		final ServiceClientFailoverExecutor executor = ServiceClientFailoverExecutorTest.createExecutor(
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO));
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		ServiceClientTarget.runFor("AbsoluteClient", () -> executor.executeOverEndpoints(List.of(" "), "https://user@host:8443/base/{id}?query={query}",
				HttpMethod.GET, HttpEntity.EMPTY, ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
				ServiceClientFailoverExecutorTest.createFailingTemplate(new IllegalStateException("Failed."), attemptedUrls)));
		ServiceClientTarget.runFor(null, () -> executor.executeOverEndpoints(null, "http://host", HttpMethod.GET, HttpEntity.EMPTY,
				ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
				ServiceClientFailoverExecutorTest.createFailingTemplate(new IllegalStateException("Failed."), attemptedUrls)));
		Assertions.assertEquals(List.of("https://user@host:8443/base/{id}?query={query}", "http://host"), attemptedUrls);
		Assertions.assertThrows(IntegrationException.class, () -> executor.resolveEndpoints("AbsoluteClient", List.of()));
	}

	/**
	 * An empty (or blank) endpoint list after a non-empty one keeps the calls on the
	 * last non-empty list, and a new non-empty list replaces it; blank entries of a
	 * list are ignored. The lists are kept per target.
	 */
	@Test
	public void testEmptyEndpointsKeepLastList() {
		final ServiceClientFailoverExecutor executor = ServiceClientFailoverExecutorTest.createExecutor(
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO));
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		ServiceClientFailoverExecutorTest.executeFor(executor, "Client", List.of(" ", "", " http://first "), attemptedUrls);
		ServiceClientFailoverExecutorTest.executeFor(executor, "Client", List.of(), attemptedUrls);
		ServiceClientFailoverExecutorTest.executeFor(executor, "Client", List.of(" ", ""), attemptedUrls);
		ServiceClientFailoverExecutorTest.executeFor(executor, "Client", null, attemptedUrls);
		Assertions.assertEquals(List.of("http://first/path", "http://first/path", "http://first/path", "http://first/path"), attemptedUrls);
		// The list recovers, and becomes the last good one.
		attemptedUrls.clear();
		ServiceClientFailoverExecutorTest.executeFor(executor, "Client", List.of("http://second"), attemptedUrls);
		ServiceClientFailoverExecutorTest.executeFor(executor, "Client", List.of(), attemptedUrls);
		Assertions.assertEquals(List.of("http://second/path", "http://second/path"), attemptedUrls);
		Assertions.assertEquals(List.of("http://second"), executor.resolveEndpoints("Client", List.of()));
		// Another target has no last list of its own.
		Assertions.assertThrows(IntegrationException.class, () -> executor.resolveEndpoints("OtherClient", List.of()));
	}

	/**
	 * Calls on the last list are warned about once per transition to an empty list,
	 * not once per call.
	 */
	@Test
	public void testEmptyEndpointsWarnedOncePerTransition() {
		final ServiceClientFailoverExecutor executor = ServiceClientFailoverExecutorTest.createExecutor(
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO));
		final Logger logger = (Logger) LoggerFactory.getLogger(ServiceClientFailoverExecutor.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			executor.resolveEndpoints("WarnedClient", List.of("http://first"));
			for (int callIndex = 0; callIndex < 3; callIndex++) {
				executor.resolveEndpoints("WarnedClient", List.of());
			}
			executor.resolveEndpoints("WarnedClient", List.of("http://first"));
			executor.resolveEndpoints("WarnedClient", List.of(""));
			executor.resolveEndpoints("WarnedClient", List.of(""));
		}
		finally {
			logger.detachAppender(appender);
		}
		Assertions.assertEquals(2, appender.list.stream()
				.filter(event -> (event.getLevel() == Level.WARN) && event.getFormattedMessage().contains("Endpoint list of WarnedClient is empty")).count());
		Assertions.assertEquals(1, appender.list.stream()
				.filter(event -> (event.getLevel() == Level.INFO) && event.getFormattedMessage().contains("Endpoint list of WarnedClient is back")).count());
	}

	/**
	 * Creates a template whose every call waits, then fails without connecting (no
	 * pooled connection in time).
	 *
	 * @param  wait          Wait of each call.
	 * @param  attemptedUrls Where the URLs are recorded.
	 * @return               The template.
	 */
	private static RestTemplate createSlowPoolTemplate(
			final Duration wait,
			final List<String> attemptedUrls) {
		return new RestTemplate(new SimpleClientHttpRequestFactory()) {

			@Override
			public <ResponseType> ResponseEntity<ResponseType> exchange(
					final String url,
					final HttpMethod method,
					final HttpEntity<?> requestEntity,
					final ParameterizedTypeReference<ResponseType> responseType,
					final Map<String, ?> uriVariables) {
				attemptedUrls.add(url);
				try {
					Thread.sleep(wait.toMillis());
				}
				catch (final InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				throw new ResourceAccessException("Pool exhausted.", new ConnectionRequestTimeoutException("Pool exhausted."));
			}
		};
	}

	/**
	 * Creates a scope that records the connection wait limit of each attempt.
	 *
	 * @param  connectionWaitLimits Where the limits are recorded.
	 * @return                      The scope.
	 */
	private static ServiceClientReadTimeoutScope createLimitRecordingScope(
			final List<Optional<Duration>> connectionWaitLimits) {
		return new ServiceClientReadTimeoutScope() {

			@Override
			public <ResultType> ResultType runWithReadTimeout(
					final RestOperations template,
					final Duration readTimeout,
					final Supplier<ResultType> action) {
				return action.get();
			}

			@Override
			public <ResultType> ResultType runWithTimeouts(
					final RestOperations template,
					final Duration readTimeout,
					final Duration connectionWaitLimit,
					final Supplier<ResultType> action) {
				connectionWaitLimits.add(Optional.ofNullable(connectionWaitLimit));
				return action.get();
			}

		};
	}

	/**
	 * Creates an executor of up to five attempts with a time budget.
	 *
	 * @param  maxTotalTime Time budget.
	 * @param  scope        Timeout scope.
	 * @return              The executor.
	 */
	private static ServiceClientFailoverExecutor createBudgetExecutor(
			final Duration maxTotalTime,
			final ServiceClientReadTimeoutScope scope) {
		return ServiceClientFailoverExecutorTest.createBudgetExecutor(maxTotalTime, scope, ServiceClientMetrics.NONE);
	}

	/**
	 * Creates an executor of up to five attempts with a time budget and metrics.
	 *
	 * @param  maxTotalTime Time budget.
	 * @param  scope        Timeout scope.
	 * @param  metrics      Metrics.
	 * @return              The executor.
	 */
	private static ServiceClientFailoverExecutor createBudgetExecutor(
			final Duration maxTotalTime,
			final ServiceClientReadTimeoutScope scope,
			final ServiceClientMetrics metrics) {
		return new ServiceClientFailoverExecutor(true, 5, new String[] { "GET" }, null, null, false, maxTotalTime,
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO), scope, metrics);
	}

	/**
	 * Attempts that each wait for a pooled connection stop once the time budget is
	 * spent, not at the attempt limit, and each attempt's connection wait is capped
	 * to what is left of the budget; the operation cut short is counted.
	 */
	@Test
	public void testBudgetStopsAttempts() {
		final List<Optional<Duration>> connectionWaitLimits = new CopyOnWriteArrayList<>();
		final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		final ServiceClientFailoverExecutor executor = ServiceClientFailoverExecutorTest.createBudgetExecutor(Duration.ofMillis(1000),
				ServiceClientFailoverExecutorTest.createLimitRecordingScope(connectionWaitLimits), new MicrometerServiceClientMetrics(meterRegistry));
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		final ServiceClientFailoverExecutor.Outcome<String> outcome = executor.executeOverEndpoints(
				List.of("http://first", "http://second", "http://third", "http://fourth", "http://fifth"), "/path", HttpMethod.POST, HttpEntity.EMPTY,
				ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
				ServiceClientFailoverExecutorTest.createSlowPoolTemplate(Duration.ofMillis(400), attemptedUrls));
		Assertions.assertNotNull(outcome.failure());
		// 400 ms each over a 1 s budget: three attempts (two if the machine is slow), not five.
		Assertions.assertTrue((attemptedUrls.size() >= 2) && (attemptedUrls.size() <= 3), attemptedUrls::toString);
		Assertions.assertEquals(attemptedUrls.size(), connectionWaitLimits.size());
		Duration previousLimit = Duration.ofMillis(1000);
		for (final Optional<Duration> connectionWaitLimit : connectionWaitLimits) {
			Assertions.assertTrue(connectionWaitLimit.isPresent());
			Assertions.assertTrue(connectionWaitLimit.get().compareTo(previousLimit) <= 0, connectionWaitLimits::toString);
			previousLimit = connectionWaitLimit.get();
		}
		Assertions.assertTrue(connectionWaitLimits.get(connectionWaitLimits.size() - 1).get().compareTo(Duration.ofMillis(600)) <= 0);
		Assertions.assertEquals(1, meterRegistry.get(MicrometerServiceClientMetrics.BUDGET_SPENT).tag("target", ServiceClientTarget.NONE)
				.tag("reason", "not-connected").counter().count());
	}

	/**
	 * The budget property is read as a duration: {@code 10s} is ten seconds and
	 * {@code 0} turns it off.
	 */
	@Test
	public void testBudgetProperty() {
		for (final Map.Entry<String, Duration> budget : Map.of("10s", Duration.ofSeconds(10), "0", Duration.ZERO, "1500ms", Duration.ofMillis(1500)).entrySet()) {
			Assertions.assertEquals(budget.getValue(), new ServiceClientAutoConfiguration().serviceClientFailoverExecutor(true, 3, new String[] { "GET" }, null, null,
					false, budget.getKey(), 9L, new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.RANDOM, Duration.ZERO),
					ServiceClientRequestFactory.READ_TIMEOUT_SCOPE, new DefaultListableBeanFactory().getBeanProvider(ServiceClientMetrics.class)).getMaxTotalTime());
		}
	}

	/**
	 * Retrying read timeouts with a budget under twice the read timeout is warned
	 * about when the executor is created; a budget large enough, or none, is not.
	 */
	@Test
	public void testReadTimeoutRetryBudgetWarning() {
		final Logger logger = (Logger) LoggerFactory.getLogger(ServiceClientAutoConfiguration.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			for (final String maxTotalTime : List.of("10s", "18s", "0")) {
				new ServiceClientAutoConfiguration().serviceClientFailoverExecutor(true, 3, new String[] { "GET" }, null, null, true, maxTotalTime, 9L,
						new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.RANDOM, Duration.ZERO), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
						new DefaultListableBeanFactory().getBeanProvider(ServiceClientMetrics.class));
			}
		}
		finally {
			logger.detachAppender(appender);
		}
		Assertions.assertEquals(List.of("10s"), appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
				.map(event -> event.getFormattedMessage().contains("time budget (10s)") ? "10s" : event.getFormattedMessage()).toList());
	}

	/**
	 * A budget of zero (or none) disables it: every attempt is made, with the
	 * template's own connection waits.
	 */
	@Test
	public void testBudgetDisabledWhenZero() {
		for (final Duration maxTotalTime : Arrays.asList(Duration.ZERO, null)) {
			final List<Optional<Duration>> connectionWaitLimits = new CopyOnWriteArrayList<>();
			final ServiceClientFailoverExecutor executor = ServiceClientFailoverExecutorTest.createBudgetExecutor(maxTotalTime,
					ServiceClientFailoverExecutorTest.createLimitRecordingScope(connectionWaitLimits));
			final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
			executor.executeOverEndpoints(List.of("http://first", "http://second", "http://third", "http://fourth", "http://fifth"), "/path", HttpMethod.POST,
					HttpEntity.EMPTY, ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
					ServiceClientFailoverExecutorTest.createSlowPoolTemplate(Duration.ofMillis(50), attemptedUrls));
			Assertions.assertEquals(5, attemptedUrls.size());
			Assertions.assertEquals(List.of(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
					Optional.empty()), connectionWaitLimits);
		}
	}

	/**
	 * A fast success is not changed by the budget: one attempt, its response, and a
	 * connection wait limit of (about) the whole budget.
	 */
	@Test
	public void testBudgetLeavesFastSuccess() {
		final List<Optional<Duration>> connectionWaitLimits = new CopyOnWriteArrayList<>();
		final ServiceClientFailoverExecutor executor = ServiceClientFailoverExecutorTest.createBudgetExecutor(Duration.ofSeconds(10),
				ServiceClientFailoverExecutorTest.createLimitRecordingScope(connectionWaitLimits));
		final RestTemplate template = new RestTemplate(new SimpleClientHttpRequestFactory()) {

			@Override
			@SuppressWarnings("unchecked")
			public <ResponseType> ResponseEntity<ResponseType> exchange(
					final String url,
					final HttpMethod method,
					final HttpEntity<?> requestEntity,
					final ParameterizedTypeReference<ResponseType> responseType,
					final Map<String, ?> uriVariables) {
				return (ResponseEntity<ResponseType>) ResponseEntity.ok(url);
			}
		};
		final ServiceClientFailoverExecutor.Outcome<String> outcome = executor.executeOverEndpoints(List.of("http://first", "http://second"), "/path",
				HttpMethod.GET, HttpEntity.EMPTY, ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null, template);
		Assertions.assertNull(outcome.failure());
		Assertions.assertEquals(outcome.url(), outcome.response().getBody());
		Assertions.assertEquals(1, connectionWaitLimits.size());
		Assertions.assertTrue(connectionWaitLimits.get(0).get().compareTo(Duration.ofSeconds(9)) > 0);
		Assertions.assertTrue(connectionWaitLimits.get(0).get().compareTo(Duration.ofSeconds(10)) <= 0);
	}

	/**
	 * Request factory that exposes its request configuration.
	 */
	private static class ExposedRequestFactory extends ServiceClientRequestFactory {

		/**
		 * HTTP client.
		 */
		private final CloseableHttpClient client;

		/**
		 * Constructor.
		 *
		 * @param client HTTP client.
		 */
		ExposedRequestFactory(final CloseableHttpClient client) {
			super(client);
			this.client = client;
		}

		/**
		 * Gets the request configuration of a request made now.
		 *
		 * @return The request configuration.
		 */
		@SuppressWarnings("deprecation")
		RequestConfig getRequestConfig() {
			return this.createRequestConfig(this.client);
		}

	}

	/**
	 * The library's request factory caps the wait for a pooled connection and the
	 * connect timeout to the connection wait limit (keeping shorter ones of its
	 * own), and leaves the read timeout as it is.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@SuppressWarnings("deprecation")
	public void testRequestFactoryCapsConnectionWaits() throws Exception {
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			final ExposedRequestFactory factory = new ExposedRequestFactory(client);
			factory.setConnectionRequestTimeout(Duration.ofSeconds(5));
			factory.setConnectTimeout(Duration.ofSeconds(3));
			factory.setReadTimeout(Duration.ofSeconds(9));
			final RequestConfig ownConfig = factory.getRequestConfig();
			Assertions.assertEquals(Timeout.ofSeconds(5).toMilliseconds(), ownConfig.getConnectionRequestTimeout().toMilliseconds());
			Assertions.assertEquals(Timeout.ofSeconds(3).toMilliseconds(), ownConfig.getConnectTimeout().toMilliseconds());
			final RequestConfig cappedConfig = ServiceClientRequestFactory.runWithConnectionWaitLimit(Duration.ofMillis(1500), factory::getRequestConfig);
			Assertions.assertEquals(1500, cappedConfig.getConnectionRequestTimeout().toMilliseconds());
			Assertions.assertEquals(1500, cappedConfig.getConnectTimeout().toMilliseconds());
			Assertions.assertEquals(9000, cappedConfig.getResponseTimeout().toMilliseconds());
			final RequestConfig partlyCappedConfig = ServiceClientRequestFactory.runWithConnectionWaitLimit(Duration.ofSeconds(4), factory::getRequestConfig);
			Assertions.assertEquals(4000, partlyCappedConfig.getConnectionRequestTimeout().toMilliseconds());
			Assertions.assertEquals(3000, partlyCappedConfig.getConnectTimeout().toMilliseconds());
			final RequestConfig spentConfig = ServiceClientRequestFactory.runWithConnectionWaitLimit(Duration.ofMillis(-5), factory::getRequestConfig);
			Assertions.assertEquals(1, spentConfig.getConnectionRequestTimeout().toMilliseconds());
			// Outside the scope, the factory's own waits are back.
			Assertions.assertEquals(5000, factory.getRequestConfig().getConnectionRequestTimeout().toMilliseconds());
		}
	}

	/**
	 * With a real exhausted connection pool (5 s pool wait, the shipped default),
	 * three attempts would wait about 15 s; with a 1 s budget the call gives up
	 * after about 1 s, on the pool wait.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testBudgetCapsRealPoolWait() throws Exception {
		final PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
		connectionManager.setMaxTotal(1);
		connectionManager.setDefaultMaxPerRoute(1);
		final ConnectionEndpoint heldConnection = connectionManager.lease("held", new HttpRoute(new HttpHost("localhost", 1)), Timeout.ofSeconds(1), null)
				.get(Timeout.ofSeconds(1));
		try (CloseableHttpClient client = HttpClients.custom().setConnectionManager(connectionManager).setConnectionManagerShared(true).build()) {
			final ServiceClientRequestFactory factory = new ServiceClientRequestFactory(client);
			factory.setConnectionRequestTimeout(Duration.ofSeconds(5));
			factory.setConnectTimeout(Duration.ofSeconds(3));
			final List<String> outcomes = new CopyOnWriteArrayList<>();
			final ServiceClientFailoverExecutor executor = new ServiceClientFailoverExecutor(true, 3, new String[] { "GET" }, null, null, false,
					Duration.ofSeconds(1), new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO),
					ServiceClientRequestFactory.READ_TIMEOUT_SCOPE, new ServiceClientMetrics() {

						@Override
						public void recordAttempt(
								final String target,
								final String outcome) {
							outcomes.add(outcome);
						}

					});
			final long startNanos = System.nanoTime();
			final ServiceClientFailoverExecutor.Outcome<String> outcome = executor.executeOverEndpoints(
					List.of("http://localhost:2", "http://localhost:3", "http://localhost:4"), "/path", HttpMethod.GET, HttpEntity.EMPTY,
					ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null, new RestTemplate(factory));
			final Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
			Assertions.assertNotNull(outcome.failure());
			Assertions.assertTrue(elapsed.compareTo(Duration.ofMillis(900)) > 0, elapsed::toString);
			// Well under the 5 s of a single uncapped pool wait (15 s for three).
			Assertions.assertTrue(elapsed.compareTo(Duration.ofMillis(4500)) < 0, elapsed::toString);
			Assertions.assertFalse(outcomes.isEmpty());
			Assertions.assertTrue(outcomes.stream().allMatch("not-connected"::equals), outcomes::toString);
		}
		finally {
			heldConnection.close(CloseMode.IMMEDIATE);
			connectionManager.close(CloseMode.IMMEDIATE);
		}
	}

	/**
	 * The executor's read timeout scope wraps every attempt, failover attempts
	 * included, with the call's template and read timeout.
	 */
	@Test
	public void testReadTimeoutScopeWrapsEveryAttempt() {
		final List<Duration> scopedReadTimeouts = new CopyOnWriteArrayList<>();
		final List<RestOperations> scopedTemplates = new CopyOnWriteArrayList<>();
		final ServiceClientReadTimeoutScope recordingScope = new ServiceClientReadTimeoutScope() {

			@Override
			public <ResultType> ResultType runWithReadTimeout(
					final RestOperations template,
					final Duration readTimeout,
					final Supplier<ResultType> action) {
				scopedTemplates.add(template);
				scopedReadTimeouts.add(readTimeout);
				return action.get();
			}

		};
		final ServiceClientFailoverExecutor executor = new ServiceClientFailoverExecutor(true, 3, new String[] { "GET" }, new String[] { "503" }, new String[] { "503" }, false,
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO), recordingScope);
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		final RestTemplate template = ServiceClientFailoverExecutorTest
				.createFailingTemplate(new ResourceAccessException("Refused.", new ConnectException("Refused.")), attemptedUrls);
		executor.executeOverEndpoints(List.of("http://first", "http://second"), "/", HttpMethod.GET, HttpEntity.EMPTY,
				ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, Duration.ofSeconds(2), template);
		Assertions.assertEquals(2, attemptedUrls.size());
		Assertions.assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(2)), scopedReadTimeouts);
		Assertions.assertEquals(List.of(template, template), scopedTemplates);
	}

	/**
	 * Every kind of attempt failure is recorded under the call's target: as the
	 * attempt outcome, and as the retry reason when it is retried on the other
	 * endpoint (a GET, with a retry status and read timeouts retried).
	 */
	@Test
	public void testFailureKindsRecorded() {
		final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		final ServiceClientFailoverExecutor executor = new ServiceClientFailoverExecutor(true, 2, new String[] { "GET" }, new String[] { "503" }, null, true,
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
				new MicrometerServiceClientMetrics(meterRegistry));
		final Map<String, RuntimeException> failures = Map.of("not-connected", new ResourceAccessException("refused", new ConnectException()), "status",
				HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "", null, null, null), "read-timeout",
				new ResourceAccessException("timeout", new SocketTimeoutException()), "dropped", new ResourceAccessException("dropped", new NoHttpResponseException("")),
				"other", new IllegalStateException());
		failures.forEach((kind, failure) -> {
			final List<String> attemptedUrls = new ArrayList<>();
			ServiceClientTarget.runFor("target", () -> executor.executeOverEndpoints(List.of("http://first", "http://second"), "/path", HttpMethod.GET,
					new HttpEntity<>(null), ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
					ServiceClientFailoverExecutorTest.createFailingTemplate(failure, attemptedUrls)));
			final int retries = ("other".equals(kind) ? 0 : 1);
			Assertions.assertEquals(retries + 1, attemptedUrls.size(), kind);
			Assertions.assertEquals(retries + 1,
					meterRegistry.get(MicrometerServiceClientMetrics.ATTEMPTS).tag("target", "target").tag("outcome", kind).counter().count(), kind);
			Assertions.assertEquals(retries, meterRegistry.find(MicrometerServiceClientMetrics.RETRIES).tag("target", "target").tag("reason", kind).counters()
					.stream().mapToDouble(Counter::count).sum(), kind);
		});
		Assertions.assertEquals(2, meterRegistry.get(MicrometerServiceClientMetrics.COOLDOWNS).tag("target", "target").counter().count());
		Assertions.assertEquals(0, meterRegistry.get(MicrometerServiceClientMetrics.IN_FLIGHT).tag("target", "target").gauge().value());
	}

	/**
	 * Creates the failover executor bean over the metrics beans of a bean factory.
	 *
	 * @param  beanFactory Bean factory.
	 * @return             The failover executor.
	 */
	private static ServiceClientFailoverExecutor createExecutorBean(
			final DefaultListableBeanFactory beanFactory) {
		return new ServiceClientAutoConfiguration().serviceClientFailoverExecutor(true, 3, new String[] { "GET" }, null, null, false, "10s", 9L,
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.RANDOM, Duration.ZERO), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
				beanFactory.getBeanProvider(ServiceClientMetrics.class));
	}

	/**
	 * Without a meter registry, the metrics bean records nothing, and the failover
	 * executor built over it still makes its attempts.
	 */
	@Test
	public void testNoMeterRegistry() {
		final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		final ServiceClientMetrics metrics = new ServiceClientAutoConfiguration.ServiceClientMetricsConfiguration()
				.serviceClientMetrics(beanFactory.getBeanProvider(MeterRegistry.class));
		Assertions.assertSame(ServiceClientMetrics.NONE, metrics);
		beanFactory.registerSingleton("serviceClientMetrics", metrics);
		final List<String> attemptedUrls = new ArrayList<>();
		ServiceClientFailoverExecutorTest.createExecutorBean(beanFactory).executeOverEndpoints(List.of("http://first"), "/path", HttpMethod.GET,
				new HttpEntity<>(null), ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
				ServiceClientFailoverExecutorTest.createFailingTemplate(new IllegalStateException(), attemptedUrls));
		Assertions.assertEquals(1, attemptedUrls.size());
	}

	/**
	 * A service's own metrics bean, ordered, wins over the library's (lowest
	 * precedence): the failover executor records in it only.
	 */
	@Test
	public void testServiceMetricsBeanWins() {
		final SimpleMeterRegistry libraryRegistry = new SimpleMeterRegistry();
		final List<String> recordedOutcomes = new ArrayList<>();
		final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		beanFactory.registerSingleton("serviceClientMetrics", new MicrometerServiceClientMetrics(libraryRegistry));
		beanFactory.registerSingleton("serviceMetrics", new OrderedRecordingMetrics(recordedOutcomes));
		ServiceClientFailoverExecutorTest.createExecutorBean(beanFactory).executeOverEndpoints(List.of("http://first"), "/path", HttpMethod.GET,
				new HttpEntity<>(null), ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
				ServiceClientFailoverExecutorTest.createFailingTemplate(new IllegalStateException(), new ArrayList<>()));
		Assertions.assertEquals(List.of("other"), recordedOutcomes);
		Assertions.assertTrue(libraryRegistry.find(MicrometerServiceClientMetrics.ATTEMPTS).counters().isEmpty());
	}

	/**
	 * Metrics that fail to record do not change the call: the failed attempt is
	 * still retried on the other endpoint, and the outcome is returned.
	 */
	@Test
	public void testFailingMetricsDoNotChangeTheCall() {
		final ServiceClientMetrics failingMetrics = new ServiceClientMetrics() {

			@Override
			public void recordRequestStarted(
					final String target) {
				throw new IllegalStateException("Metrics down.");
			}

			@Override
			public void recordAttempt(
					final String target,
					final String outcome) {
				throw new IllegalStateException("Metrics down.");
			}

		};
		final ServiceClientFailoverExecutor executor = new ServiceClientFailoverExecutor(true, 2, new String[] { "GET" }, null, null, false,
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
				failingMetrics);
		final List<String> attemptedUrls = new ArrayList<>();
		final RuntimeException failure = new ResourceAccessException("refused", new ConnectException());
		final ServiceClientFailoverExecutor.Outcome<String> outcome = executor.executeOverEndpoints(List.of("http://first", "http://second"), "/path",
				HttpMethod.GET, new HttpEntity<>(null), ServiceClientFailoverExecutorTest.STRING_RESPONSE, Map.of(), null, null,
				ServiceClientFailoverExecutorTest.createFailingTemplate(failure, attemptedUrls));
		Assertions.assertSame(failure, outcome.failure());
		Assertions.assertEquals(2, attemptedUrls.size());
	}

	/**
	 * Metrics of a service, ordered first, that record the attempt outcomes.
	 */
	private static class OrderedRecordingMetrics implements ServiceClientMetrics, Ordered {

		/**
		 * Recorded attempt outcomes.
		 */
		private final List<String> recordedOutcomes;

		/**
		 * Constructor.
		 *
		 * @param recordedOutcomes Where the attempt outcomes are recorded.
		 */
		OrderedRecordingMetrics(final List<String> recordedOutcomes) {
			this.recordedOutcomes = recordedOutcomes;
		}

		/**
		 * @see Ordered#getOrder()
		 */
		@Override
		public int getOrder() {
			return 0;
		}

		/**
		 * @see ServiceClientMetrics#recordAttempt(String, String)
		 */
		@Override
		public void recordAttempt(
				final String target,
				final String outcome) {
			this.recordedOutcomes.add(outcome);
		}

	}

	/**
	 * A target set inside another one is the current one while its action runs,
	 * then the outer one is back; a blank target, or none, is
	 * {@value ServiceClientTarget#NONE}.
	 */
	@Test
	public void testNestedTargets() {
		Assertions.assertEquals(ServiceClientTarget.NONE, ServiceClientTarget.getCurrent());
		Assertions.assertEquals("outer", ServiceClientTarget.runFor("outer", () -> {
			Assertions.assertEquals("inner", ServiceClientTarget.runFor("inner", ServiceClientTarget::getCurrent));
			Assertions.assertEquals(ServiceClientTarget.NONE, ServiceClientTarget.runFor(" ", ServiceClientTarget::getCurrent));
			return ServiceClientTarget.getCurrent();
		}));
		Assertions.assertEquals(ServiceClientTarget.NONE, ServiceClientTarget.getCurrent());
	}

}
