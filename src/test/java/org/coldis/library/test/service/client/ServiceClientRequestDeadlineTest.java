package org.coldis.library.test.service.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.client.ServiceClientAutoConfiguration;
import org.coldis.library.service.client.ServiceClientConcurrencyLimiter;
import org.coldis.library.service.client.MicrometerServiceClientMetrics;
import org.coldis.library.service.client.ServiceClientEndpointBalancer;
import org.coldis.library.service.client.ServiceClientEndpointResolver;
import org.coldis.library.service.client.ServiceClientFailoverExecutor;
import org.coldis.library.service.client.ServiceClientMetrics;
import org.coldis.library.service.client.ServiceClientReadTimeoutScope;
import org.coldis.library.service.client.ServiceClientRequestDeadline;
import org.coldis.library.service.client.ServiceClientRequestDeadlinePolicy;
import org.coldis.library.service.client.ServiceClientRequestFactory;
import org.coldis.library.service.client.ServiceClientTarget;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Request deadline of the generated clients (plain objects, a real HTTP server):
 * what is left of the deadline caps each attempt's timeouts and is sent on in
 * the header; a spent one makes no request.
 */
public class ServiceClientRequestDeadlineTest {

	/**
	 * Response type.
	 */
	private static final ParameterizedTypeReference<String> STRING_RESPONSE = new ParameterizedTypeReference<>() {};

	/**
	 * Answer of the echo endpoint when the request has no deadline header.
	 */
	private static final String NO_HEADER = "none";

	/**
	 * How long the slow endpoint takes to answer.
	 */
	private static final Duration SLOW_RESPONSE_DELAY = Duration.ofSeconds(2);

	/**
	 * Test HTTP server.
	 */
	private static HttpServer SERVER;

	/**
	 * Test HTTP server request handlers.
	 */
	private static ExecutorService SERVER_EXECUTOR;

	/**
	 * Deadline headers received by the server ({@link #NO_HEADER} when absent).
	 */
	private static final List<String> RECEIVED_HEADERS = new CopyOnWriteArrayList<>();

	/**
	 * Answers with a text.
	 *
	 * @param  exchange    HTTP exchange.
	 * @param  text        Text.
	 * @throws IOException If the response cannot be sent.
	 */
	private static void respond(
			final HttpExchange exchange,
			final String text) throws IOException {
		final byte[] response = text.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, response.length);
		try (OutputStream responseBody = exchange.getResponseBody()) {
			responseBody.write(response);
		}
	}

	/**
	 * Starts a server: {@code /echo} answers the deadline header it received,
	 * {@code /slow} answers after two seconds.
	 *
	 * @throws IOException If the server cannot start.
	 */
	@BeforeAll
	public static void startServer() throws IOException {
		ServiceClientRequestDeadlineTest.SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		ServiceClientRequestDeadlineTest.SERVER_EXECUTOR = Executors.newCachedThreadPool();
		ServiceClientRequestDeadlineTest.SERVER.setExecutor(ServiceClientRequestDeadlineTest.SERVER_EXECUTOR);
		ServiceClientRequestDeadlineTest.SERVER.createContext("/echo", exchange -> {
			final String header = Objects.toString(exchange.getRequestHeaders().getFirst(ServiceClientRequestDeadline.HEADER),
					ServiceClientRequestDeadlineTest.NO_HEADER);
			ServiceClientRequestDeadlineTest.RECEIVED_HEADERS.add(header);
			ServiceClientRequestDeadlineTest.respond(exchange, header);
		});
		ServiceClientRequestDeadlineTest.SERVER.createContext("/slow", exchange -> {
			try {
				Thread.sleep(ServiceClientRequestDeadlineTest.SLOW_RESPONSE_DELAY.toMillis());
			}
			catch (final InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			ServiceClientRequestDeadlineTest.respond(exchange, "slow");
		});
		ServiceClientRequestDeadlineTest.SERVER.start();
	}

	/**
	 * Stops the server.
	 */
	@AfterAll
	public static void stopServer() {
		ServiceClientRequestDeadlineTest.SERVER.stop(0);
		ServiceClientRequestDeadlineTest.SERVER_EXECUTOR.shutdownNow();
	}

	/**
	 * Clears the deadline and the received headers after each test.
	 */
	@AfterEach
	public void clear() {
		ServiceClientRequestDeadline.setDeadlineNanos(null);
		ServiceClientRequestDeadlineTest.RECEIVED_HEADERS.clear();
	}

	/**
	 * Gets the server's base URL.
	 *
	 * @return The server's base URL.
	 */
	private static String getServerUrl() {
		return "http://localhost:" + ServiceClientRequestDeadlineTest.SERVER.getAddress().getPort();
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
	 * Timeouts an attempt ran with, as given to the scope.
	 *
	 * @param readTimeout         Read timeout.
	 * @param readTimeoutLimit    Read timeout limit.
	 * @param connectionWaitLimit Connection wait limit.
	 */
	private record AttemptTimeouts(
			Optional<Duration> readTimeout,
			Optional<Duration> readTimeoutLimit,
			Optional<Duration> connectionWaitLimit) {}

	/**
	 * Creates a scope that records the timeouts of each attempt, then applies them
	 * as the library's scope does.
	 *
	 * @param  attemptTimeouts Where the timeouts are recorded.
	 * @return                 The scope.
	 */
	private static ServiceClientReadTimeoutScope createRecordingScope(
			final List<AttemptTimeouts> attemptTimeouts) {
		return new ServiceClientReadTimeoutScope() {

			@Override
			public <ResultType> ResultType runWithReadTimeout(
					final RestOperations template,
					final Duration readTimeout,
					final Supplier<ResultType> action) {
				return ServiceClientRequestFactory.READ_TIMEOUT_SCOPE.runWithReadTimeout(template, readTimeout, action);
			}

			@Override
			public <ResultType> ResultType runWithTimeouts(
					final RestOperations template,
					final Duration readTimeout,
					final Duration readTimeoutLimit,
					final Duration connectionWaitLimit,
					final Supplier<ResultType> action) {
				attemptTimeouts.add(new AttemptTimeouts(Optional.ofNullable(readTimeout), Optional.ofNullable(readTimeoutLimit),
						Optional.ofNullable(connectionWaitLimit)));
				return ServiceClientRequestFactory.READ_TIMEOUT_SCOPE.runWithTimeouts(template, readTimeout, readTimeoutLimit, connectionWaitLimit, action);
			}

		};
	}

	/**
	 * Creates an executor of up to five attempts.
	 *
	 * @param  maxTotalTime      Time budget.
	 * @param  scope             Timeout scope.
	 * @param  metrics           Metrics.
	 * @param  isDeadlineEnabled If the request's deadline is applied.
	 * @return                   The executor.
	 */
	private static ServiceClientFailoverExecutor createExecutor(
			final Duration maxTotalTime,
			final ServiceClientReadTimeoutScope scope,
			final ServiceClientMetrics metrics,
			final boolean isDeadlineEnabled) {
		return new ServiceClientFailoverExecutor(true, 5, new String[] { "GET" }, null, null, false, maxTotalTime,
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO), ServiceClientEndpointResolver.WITHOUT_LAST_ENDPOINTS,
				scope, metrics, ServiceClientConcurrencyLimiter.NONE, new ServiceClientRequestDeadlinePolicy(isDeadlineEnabled, Duration.ofMillis(100), null));
	}

	/**
	 * Creates a template over the library's request factory, with a 9 seconds
	 * read timeout (the shipped default).
	 *
	 * @param  client HTTP client.
	 * @return        The template.
	 */
	private static RestTemplate createTemplate(
			final CloseableHttpClient client) {
		final ServiceClientRequestFactory factory = new ServiceClientRequestFactory(client);
		factory.setReadTimeout(Duration.ofSeconds(9));
		return new RestTemplate(factory);
	}

	/**
	 * Calls the echo endpoint through an executor.
	 *
	 * @param  executor    Executor.
	 * @param  readTimeout The call's own read timeout.
	 * @param  template    Template.
	 * @return             The deadline header the server received.
	 */
	private static String callEcho(
			final ServiceClientFailoverExecutor executor,
			final Duration readTimeout,
			final RestTemplate template) {
		final ServiceClientFailoverExecutor.Outcome<String> outcome = executor.executeOverEndpoints(
				List.of(ServiceClientRequestDeadlineTest.getServerUrl()), "/echo", HttpMethod.GET, HttpEntity.EMPTY, ServiceClientRequestDeadlineTest.STRING_RESPONSE,
				Map.of(), null, readTimeout, template);
		Assertions.assertNull(outcome.failure());
		return outcome.response().getBody();
	}

	/**
	 * Without a deadline, a call is as before: no header, its own read timeout
	 * only, no read timeout limit, no connection wait limit with no budget.
	 *
	 * @throws IOException If the client cannot be closed.
	 */
	@Test
	public void testNoDeadlineUnchanged() throws IOException {
		final List<AttemptTimeouts> attemptTimeouts = new CopyOnWriteArrayList<>();
		final ServiceClientFailoverExecutor executor = ServiceClientRequestDeadlineTest.createExecutor(Duration.ZERO,
				ServiceClientRequestDeadlineTest.createRecordingScope(attemptTimeouts), ServiceClientMetrics.NONE, true);
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			Assertions.assertEquals(ServiceClientRequestDeadlineTest.NO_HEADER,
					ServiceClientRequestDeadlineTest.callEcho(executor, Duration.ofSeconds(2), ServiceClientRequestDeadlineTest.createTemplate(client)));
		}
		Assertions.assertEquals(List.of(new AttemptTimeouts(Optional.of(Duration.ofSeconds(2)), Optional.empty(), Optional.empty())), attemptTimeouts);
	}

	/**
	 * With a deadline far enough, the call is made with its read timeout capped to
	 * what is left of the deadline (less the margin), and that is sent in the
	 * header; a shorter read timeout of the call's own is kept, and sent.
	 *
	 * @throws IOException If the client cannot be closed.
	 */
	@Test
	public void testDeadlineCapsReadTimeoutAndIsSent() throws IOException {
		final List<AttemptTimeouts> attemptTimeouts = new CopyOnWriteArrayList<>();
		final ServiceClientFailoverExecutor executor = ServiceClientRequestDeadlineTest.createExecutor(Duration.ofSeconds(10),
				ServiceClientRequestDeadlineTest.createRecordingScope(attemptTimeouts), ServiceClientMetrics.NONE, true);
		ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(2000));
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			final RestTemplate template = ServiceClientRequestDeadlineTest.createTemplate(client);
			// The deadline (2 s less the 100 ms margin) is shorter than the call's own 5 s.
			final long sentMillis = Long.parseLong(ServiceClientRequestDeadlineTest.callEcho(executor, Duration.ofSeconds(5), template));
			Assertions.assertTrue((sentMillis > 1000) && (sentMillis <= 1900), Long.toString(sentMillis));
			final AttemptTimeouts farTimeouts = attemptTimeouts.get(0);
			Assertions.assertTrue(farTimeouts.readTimeoutLimit().orElseThrow().compareTo(Duration.ofMillis(1900)) <= 0, farTimeouts::toString);
			// The deadline also caps the budget (10 s) of the connection waits.
			Assertions.assertTrue(farTimeouts.connectionWaitLimit().orElseThrow().compareTo(Duration.ofMillis(1900)) <= 0, farTimeouts::toString);
			// The call's own 500 ms is shorter than the deadline: it is kept, and sent.
			Assertions.assertEquals("500", ServiceClientRequestDeadlineTest.callEcho(executor, Duration.ofMillis(500), template));
			// With no read timeout of its own, the deadline is sent.
			final long sentWithoutOwnMillis = Long.parseLong(ServiceClientRequestDeadlineTest.callEcho(executor, null, template));
			Assertions.assertTrue((sentWithoutOwnMillis > 1000) && (sentWithoutOwnMillis <= 1900), Long.toString(sentWithoutOwnMillis));
		}
	}

	/**
	 * The header is bounded by the attempt's read timeout: the call's own, or else
	 * the default template's (when known); with neither, the deadline is sent.
	 *
	 * @throws IOException If the client cannot be closed.
	 */
	@Test
	public void testHeaderBoundedByDefaultReadTimeout() throws IOException {
		final ServiceClientFailoverExecutor executor = new ServiceClientFailoverExecutor(true, 5, new String[] { "GET" }, null, null, false, Duration.ZERO,
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO),
				ServiceClientEndpointResolver.WITHOUT_LAST_ENDPOINTS, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE, ServiceClientMetrics.NONE, ServiceClientConcurrencyLimiter.NONE,
				new ServiceClientRequestDeadlinePolicy(true, Duration.ofMillis(100), Duration.ofMillis(800)));
		ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(3000));
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			final RestTemplate template = ServiceClientRequestDeadlineTest.createTemplate(client);
			// No read timeout of its own: the default template's (800 ms) is shorter.
			Assertions.assertEquals("800", ServiceClientRequestDeadlineTest.callEcho(executor, null, template));
			// Its own (300 ms) wins over the default.
			Assertions.assertEquals("300", ServiceClientRequestDeadlineTest.callEcho(executor, Duration.ofMillis(300), template));
			// Its own, longer than what is left: what is left.
			final long sentMillis = Long.parseLong(ServiceClientRequestDeadlineTest.callEcho(executor, Duration.ofSeconds(5), template));
			Assertions.assertTrue((sentMillis > 2000) && (sentMillis <= 2900), Long.toString(sentMillis));
		}
	}

	/**
	 * The deadline also cuts the template's own read timeout (9 s) of a call that
	 * has none: a 2 s answer with 600 ms left fails on the read timeout, well
	 * before the answer.
	 *
	 * @throws IOException If the client cannot be closed.
	 */
	@Test
	public void testDeadlineCutsTemplateReadTimeout() throws IOException {
		final ServiceClientFailoverExecutor executor = ServiceClientRequestDeadlineTest.createExecutor(Duration.ZERO, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
				ServiceClientMetrics.NONE, true);
		ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(600));
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			final long startNanos = System.nanoTime();
			final ServiceClientFailoverExecutor.Outcome<String> outcome = executor.executeOverEndpoints(List.of(ServiceClientRequestDeadlineTest.getServerUrl()),
					"/slow", HttpMethod.GET, HttpEntity.EMPTY, ServiceClientRequestDeadlineTest.STRING_RESPONSE, Map.of(), null, null,
					ServiceClientRequestDeadlineTest.createTemplate(client));
			final Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
			Assertions.assertInstanceOf(ResourceAccessException.class, outcome.failure());
			Assertions.assertTrue(elapsed.compareTo(Duration.ofMillis(1500)) < 0, elapsed::toString);
		}
	}

	/**
	 * The library's request factory caps the read timeout in effect to the limit:
	 * the factory's own, or the call's own, whichever applies, and keeps it when
	 * shorter.
	 *
	 * @throws IOException If the client cannot be closed.
	 */
	@Test
	public void testRequestFactoryCapsReadTimeout() throws IOException {
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			final ExposedRequestFactory factory = new ExposedRequestFactory(client);
			factory.setReadTimeout(Duration.ofSeconds(9));
			Assertions.assertEquals(9000, factory.getRequestConfig().getResponseTimeout().toMilliseconds());
			final RestOperations template = new RestTemplate(factory);
			Assertions.assertEquals(1500, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE
					.runWithTimeouts(template, null, Duration.ofMillis(1500), null, factory::getRequestConfig).getResponseTimeout().toMilliseconds());
			Assertions.assertEquals(9000, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE
					.runWithTimeouts(template, null, Duration.ofSeconds(20), null, factory::getRequestConfig).getResponseTimeout().toMilliseconds());
			Assertions.assertEquals(700, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE
					.runWithTimeouts(template, Duration.ofMillis(700), Duration.ofMillis(1500), null, factory::getRequestConfig).getResponseTimeout().toMilliseconds());
			Assertions.assertEquals(1, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE
					.runWithTimeouts(template, Duration.ofMillis(700), Duration.ofMillis(-5), null, factory::getRequestConfig).getResponseTimeout().toMilliseconds());
			// Outside the scope, the factory's own read timeout is back.
			Assertions.assertEquals(9000, factory.getRequestConfig().getResponseTimeout().toMilliseconds());
		}
	}

	/**
	 * A scope that only knows the call's own read timeout (the interface default)
	 * caps it to the limit, and keeps the template's when the call has none.
	 */
	@Test
	public void testDefaultScopeCapsOwnReadTimeout() {
		final List<Optional<Duration>> readTimeouts = new CopyOnWriteArrayList<>();
		final ServiceClientReadTimeoutScope scope = new ServiceClientReadTimeoutScope() {

			@Override
			public <ResultType> ResultType runWithReadTimeout(
					final RestOperations template,
					final Duration readTimeout,
					final Supplier<ResultType> action) {
				readTimeouts.add(Optional.ofNullable(readTimeout));
				return action.get();
			}

		};
		scope.runWithTimeouts(null, Duration.ofSeconds(5), Duration.ofMillis(1500), null, () -> null);
		scope.runWithTimeouts(null, Duration.ofMillis(700), Duration.ofMillis(1500), null, () -> null);
		scope.runWithTimeouts(null, null, Duration.ofMillis(1500), null, () -> null);
		scope.runWithTimeouts(null, Duration.ofMillis(700), Duration.ofMillis(-5), null, () -> null);
		Assertions.assertEquals(List.of(Optional.of(Duration.ofMillis(1500)), Optional.of(Duration.ofMillis(700)), Optional.empty(), Optional.of(Duration.ofMillis(1))),
				readTimeouts);
	}

	/**
	 * With the deadline spent (or under the margin), the call fails at once with a
	 * clear integration error (status 504) and no request, and is counted.
	 *
	 * @throws IOException If the client cannot be closed.
	 */
	@Test
	public void testExpiredDeadlineFailsFast() throws IOException {
		final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		final ServiceClientFailoverExecutor executor = ServiceClientRequestDeadlineTest.createExecutor(Duration.ZERO, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
				new MicrometerServiceClientMetrics(meterRegistry), true);
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			final RestTemplate template = ServiceClientRequestDeadlineTest.createTemplate(client);
			for (final long timeoutMillis : new long[] { -1000, 0, 50 }) {
				ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(timeoutMillis));
				final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
						() -> ServiceClientTarget.runFor("DeadlineClient", () -> ServiceClientRequestDeadlineTest.callEcho(executor, null, template)));
				Assertions.assertEquals(ServiceClientFailoverExecutor.DEADLINE_EXPIRED_CODE, exception.getInternalMessage().getCode());
				Assertions.assertEquals(504, exception.getStatusCode());
				Assertions.assertEquals("Call to DeadlineClient not made: the request's deadline is spent.", exception.getInternalMessage().getContent());
			}
		}
		Assertions.assertEquals(List.of(), ServiceClientRequestDeadlineTest.RECEIVED_HEADERS);
		Assertions.assertEquals(3, meterRegistry.get(MicrometerServiceClientMetrics.DEADLINE_EXPIRED).tag("target", "DeadlineClient")
				.tag("stage", ServiceClientFailoverExecutor.DEADLINE_STAGE_CALL).counter().count());
	}

	/**
	 * A call not made because the deadline is spent takes no concurrency limit
	 * permit (it is checked before the permit), so it never counts in the limit; a
	 * call made takes one.
	 *
	 * @throws IOException If the client cannot be closed.
	 */
	@Test
	public void testExpiredDeadlineTakesNoPermit() throws IOException {
		final AtomicInteger acquiredPermits = new AtomicInteger();
		final ServiceClientConcurrencyLimiter countingLimiter = new ServiceClientConcurrencyLimiter(
				ServiceClientConcurrencyLimiter.Settings.DEFAULTS.withMode(ServiceClientConcurrencyLimiter.Mode.OFF), null, ServiceClientMetrics.NONE) {

			@Override
			public Permit acquire(
					final String target) throws IntegrationException {
				acquiredPermits.incrementAndGet();
				return super.acquire(target);
			}

		};
		final ServiceClientFailoverExecutor executor = new ServiceClientFailoverExecutor(true, 5, new String[] { "GET" }, null, null, false, Duration.ZERO,
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO),
				ServiceClientEndpointResolver.WITHOUT_LAST_ENDPOINTS, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE, ServiceClientMetrics.NONE, countingLimiter,
				new ServiceClientRequestDeadlinePolicy(true, Duration.ofMillis(100), null));
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			final RestTemplate template = ServiceClientRequestDeadlineTest.createTemplate(client);
			ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(0));
			Assertions.assertThrows(IntegrationException.class, () -> ServiceClientRequestDeadlineTest.callEcho(executor, null, template));
			Assertions.assertEquals(0, acquiredPermits.get());
			ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(3000));
			ServiceClientRequestDeadlineTest.callEcho(executor, null, template);
			Assertions.assertEquals(1, acquiredPermits.get());
		}
	}

	/**
	 * A read timeout that took all the time the deadline left is the caller's,
	 * not the target's: the concurrency limit records neither a latency sample
	 * nor an overload outcome for it; one well within the deadline records both.
	 */
	@Test
	public void testReadTimeoutCutByDeadlineNotOverload() {
		final List<String> recordedOutcomes = new CopyOnWriteArrayList<>();
		final ServiceClientConcurrencyLimiter recordingLimiter = new ServiceClientConcurrencyLimiter(ServiceClientConcurrencyLimiter.Settings.DEFAULTS, null,
				ServiceClientMetrics.NONE) {

			@Override
			public void recordAttempt(
					final Permit permit,
					final long latencyNanos,
					final boolean isLatencySample,
					final boolean isOverload) {
				recordedOutcomes.add(isLatencySample + "/" + isOverload);
				super.recordAttempt(permit, latencyNanos, isLatencySample, isOverload);
			}

		};
		final ServiceClientFailoverExecutor executor = new ServiceClientFailoverExecutor(true, 1, new String[] { "GET" }, null, null, false, Duration.ZERO,
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO),
				ServiceClientEndpointResolver.WITHOUT_LAST_ENDPOINTS, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE, ServiceClientMetrics.NONE, recordingLimiter,
				new ServiceClientRequestDeadlinePolicy(true, Duration.ofMillis(100), null));
		final RestTemplate readTimingOutTemplate = new RestTemplate(new SimpleClientHttpRequestFactory()) {

			@Override
			public <ResponseType> ResponseEntity<ResponseType> exchange(
					final String url,
					final HttpMethod method,
					final HttpEntity<?> requestEntity,
					final ParameterizedTypeReference<ResponseType> responseType,
					final Map<String, ?> uriVariables) {
				// The cut call times out at the limit it was given (200 ms left).
				if (url.startsWith("http://cut")) {
					try {
						Thread.sleep(250);
					}
					catch (final InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
				}
				throw new ResourceAccessException("Read timeout.", new SocketTimeoutException("Read timeout."));
			}
		};
		ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(300));
		ServiceClientTarget.runFor("CutTarget", () -> executor.executeOverEndpoints(List.of("http://cut"), "/path", HttpMethod.GET, HttpEntity.EMPTY,
				ServiceClientRequestDeadlineTest.STRING_RESPONSE, Map.of(), null, null, readTimingOutTemplate));
		ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(5000));
		ServiceClientTarget.runFor("SlowTarget", () -> executor.executeOverEndpoints(List.of("http://slow"), "/path", HttpMethod.GET, HttpEntity.EMPTY,
				ServiceClientRequestDeadlineTest.STRING_RESPONSE, Map.of(), null, null, readTimingOutTemplate));
		Assertions.assertEquals(List.of("false/false", "true/true"), recordedOutcomes);
	}

	/**
	 * A failure adding the header is logged and the request goes as it is.
	 */
	@Test
	public void testHeaderFailureKeepsRequest() {
		final HttpEntity<String> brokenEntity = new HttpEntity<>("body") {

			@Override
			public HttpHeaders getHeaders() {
				throw new IllegalStateException("Broken headers.");
			}

		};
		Assertions.assertSame(brokenEntity, new ServiceClientRequestDeadlinePolicy(true, Duration.ofMillis(100), null).withHeader(brokenEntity, null,
				Duration.ofSeconds(1)));
	}

	/**
	 * Failover attempts stop once the deadline is spent (not at the attempt limit
	 * nor at the larger budget), each attempt's waits capped to what is left of
	 * it, and the stop is counted.
	 */
	@Test
	public void testFailoverRespectsDeadline() {
		final List<AttemptTimeouts> attemptTimeouts = new CopyOnWriteArrayList<>();
		final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		final ServiceClientFailoverExecutor executor = ServiceClientRequestDeadlineTest.createExecutor(Duration.ofSeconds(10),
				ServiceClientRequestDeadlineTest.createRecordingScope(attemptTimeouts), new MicrometerServiceClientMetrics(meterRegistry), true);
		final List<String> sentHeaders = new CopyOnWriteArrayList<>();
		final List<String> otherHeaders = new CopyOnWriteArrayList<>();
		final RestTemplate refusingTemplate = new RestTemplate(new SimpleClientHttpRequestFactory()) {

			@Override
			public <ResponseType> ResponseEntity<ResponseType> exchange(
					final String url,
					final HttpMethod method,
					final HttpEntity<?> requestEntity,
					final ParameterizedTypeReference<ResponseType> responseType,
					final Map<String, ?> uriVariables) {
				sentHeaders.add(requestEntity.getHeaders().getFirst(ServiceClientRequestDeadline.HEADER));
				otherHeaders.add(Objects.toString(requestEntity.getHeaders().getFirst("X-Other")));
				try {
					Thread.sleep(1000);
				}
				catch (final InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				throw new ResourceAccessException("Refused.", new ConnectException("Refused."));
			}
		};
		ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(2600));
		final HttpHeaders headers = new HttpHeaders();
		headers.set(ServiceClientRequestDeadline.HEADER, "999999");
		headers.set("X-Other", "kept");
		final ServiceClientFailoverExecutor.Outcome<String> outcome = ServiceClientTarget.runFor("DeadlineClient",
				() -> executor.executeOverEndpoints(List.of("http://first", "http://second", "http://third", "http://fourth", "http://fifth"), "/path",
						HttpMethod.GET, new HttpEntity<>(headers), ServiceClientRequestDeadlineTest.STRING_RESPONSE, Map.of(), null, null, refusingTemplate));
		Assertions.assertNotNull(outcome.failure());
		// 1 s each over 2.5 s (2.6 s less the margin): fewer attempts than the five allowed.
		Assertions.assertTrue((sentHeaders.size() >= 2) && (sentHeaders.size() < 5), sentHeaders::toString);
		Duration previousLimit = Duration.ofMillis(2500);
		for (int attemptIndex = 0; attemptIndex < attemptTimeouts.size(); attemptIndex++) {
			final AttemptTimeouts timeouts = attemptTimeouts.get(attemptIndex);
			Assertions.assertTrue(timeouts.connectionWaitLimit().orElseThrow().compareTo(previousLimit) <= 0, attemptTimeouts::toString);
			Assertions.assertEquals(timeouts.connectionWaitLimit(), timeouts.readTimeoutLimit());
			// The caller's header is replaced by what is left.
			Assertions.assertTrue(Long.parseLong(sentHeaders.get(attemptIndex)) <= previousLimit.toMillis(), sentHeaders::toString);
			previousLimit = timeouts.connectionWaitLimit().orElseThrow();
		}
		Assertions.assertEquals("999999", headers.getFirst(ServiceClientRequestDeadline.HEADER));
		// The caller's other headers are kept.
		Assertions.assertTrue(otherHeaders.stream().allMatch("kept"::equals), otherHeaders::toString);
		Assertions.assertEquals(1, meterRegistry.get(MicrometerServiceClientMetrics.DEADLINE_EXPIRED).tag("target", "DeadlineClient")
				.tag("stage", ServiceClientFailoverExecutor.DEADLINE_STAGE_RETRY).counter().count());
	}

	/**
	 * A generated client call to an absolute path with no endpoint list (maybe a
	 * third party) is bounded by the deadline but does not get the header; with
	 * the deadline spent, it is not made.
	 *
	 * @throws IOException If the client cannot be closed.
	 */
	@Test
	public void testAbsolutePathGetsNoHeader() throws IOException {
		final List<AttemptTimeouts> attemptTimeouts = new CopyOnWriteArrayList<>();
		final ServiceClientFailoverExecutor executor = ServiceClientRequestDeadlineTest.createExecutor(Duration.ZERO,
				ServiceClientRequestDeadlineTest.createRecordingScope(attemptTimeouts), ServiceClientMetrics.NONE, true);
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			final RestTemplate template = ServiceClientRequestDeadlineTest.createTemplate(client);
			ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(3000));
			final ServiceClientFailoverExecutor.Outcome<String> outcome = executor.executeOverEndpoints(List.of(),
					ServiceClientRequestDeadlineTest.getServerUrl() + "/echo", HttpMethod.GET, HttpEntity.EMPTY, ServiceClientRequestDeadlineTest.STRING_RESPONSE,
					Map.of(), null, null, template);
			Assertions.assertEquals(ServiceClientRequestDeadlineTest.NO_HEADER, outcome.response().getBody());
			Assertions.assertTrue(attemptTimeouts.get(0).readTimeoutLimit().isPresent());
			Assertions.assertTrue(attemptTimeouts.get(0).connectionWaitLimit().isPresent());
			ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(0));
			final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
					() -> executor.executeOverEndpoints(List.of(), ServiceClientRequestDeadlineTest.getServerUrl() + "/echo", HttpMethod.GET, HttpEntity.EMPTY,
							ServiceClientRequestDeadlineTest.STRING_RESPONSE, Map.of(), null, null, template));
			Assertions.assertEquals(ServiceClientFailoverExecutor.DEADLINE_EXPIRED_CODE, exception.getInternalMessage().getCode());
		}
		Assertions.assertEquals(List.of(ServiceClientRequestDeadlineTest.NO_HEADER), ServiceClientRequestDeadlineTest.RECEIVED_HEADERS);
	}

	/**
	 * The deadline properties reach the executor through the auto-configuration:
	 * the switch and the margin (a negative margin is none).
	 */
	@Test
	public void testDeadlineProperties() {
		for (final Map.Entry<String, Duration> margin : Map.of("250ms", Duration.ofMillis(250), "0", Duration.ZERO, "-1s", Duration.ZERO).entrySet()) {
			for (final boolean isEnabled : new boolean[] { true, false }) {
				final ServiceClientRequestDeadlinePolicy policy = new ServiceClientAutoConfiguration().serviceClientRequestDeadlinePolicy(isEnabled,
						margin.getKey(), 9L);
				Assertions.assertEquals(isEnabled, policy.isEnabled());
				Assertions.assertEquals(margin.getValue(), policy.getMargin());
				Assertions.assertEquals(Duration.ofSeconds(9), policy.getDefaultReadTimeout());
				// A runtime switch.
				policy.setEnabled(!isEnabled);
				Assertions.assertEquals(!isEnabled, policy.isEnabled());
				// The executor bean applies the policy it is given.
				Assertions.assertSame(policy, new ServiceClientAutoConfiguration().serviceClientFailoverExecutor(true, 3, new String[] { "GET" }, null, null,
						false, "10s", 9L, 3L, 5L, new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.RANDOM, Duration.ZERO),
						new ServiceClientEndpointResolver(Duration.ZERO, ServiceClientMetrics.NONE), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
						new DefaultListableBeanFactory().getBeanProvider(ServiceClientMetrics.class), ServiceClientConcurrencyLimiter.NONE, policy).getDeadlinePolicy());
			}
		}
		Assertions.assertFalse(new ServiceClientAutoConfiguration().serviceClientRequestDeadlinePolicy(null, "100ms", null).isEnabled());
	}

	/**
	 * A connect timeout that took all the time the deadline left puts no endpoint
	 * in cooldown, on the first attempt too; one well within the deadline (the
	 * endpoint's own) does, as one with no deadline does.
	 */
	@Test
	public void testConnectCutByDeadlineStartsNoCooldown() {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT,
				Duration.ofSeconds(10));
		final ServiceClientFailoverExecutor executor = new ServiceClientFailoverExecutor(true, 1, new String[] { "GET" }, null, null, false, Duration.ZERO,
				balancer, ServiceClientEndpointResolver.WITHOUT_LAST_ENDPOINTS, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE, ServiceClientMetrics.NONE, ServiceClientConcurrencyLimiter.NONE,
				new ServiceClientRequestDeadlinePolicy(true, Duration.ofMillis(100), null));
		final RestTemplate connectTimingOutTemplate = new RestTemplate(new SimpleClientHttpRequestFactory()) {

			@Override
			public <ResponseType> ResponseEntity<ResponseType> exchange(
					final String url,
					final HttpMethod method,
					final HttpEntity<?> requestEntity,
					final ParameterizedTypeReference<ResponseType> responseType,
					final Map<String, ?> uriVariables) {
				// The cut endpoint times out at the limit it was given (200 ms left).
				if (url.startsWith("http://cut")) {
					try {
						Thread.sleep(250);
					}
					catch (final InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
				}
				throw new ResourceAccessException("Connect timeout.", new ConnectTimeoutException("Connect timeout."));
			}
		};
		ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(300));
		Assertions.assertNotNull(executor.executeOverEndpoints(List.of("http://cut"), "/path", HttpMethod.GET, HttpEntity.EMPTY,
				ServiceClientRequestDeadlineTest.STRING_RESPONSE, Map.of(), null, null, connectTimingOutTemplate).failure());
		Assertions.assertFalse(balancer.isCoolingDown("http://cut"));
		// A connect timeout well within the deadline (5 s left) is the endpoint's.
		ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(5000));
		Assertions.assertNotNull(executor.executeOverEndpoints(List.of("http://dead"), "/path", HttpMethod.GET, HttpEntity.EMPTY,
				ServiceClientRequestDeadlineTest.STRING_RESPONSE, Map.of(), null, null, connectTimingOutTemplate).failure());
		Assertions.assertTrue(balancer.isCoolingDown("http://dead"));
		// On a retry too: with a long deadline and no budget, a connect timeout on
		// the second attempt is the endpoint's.
		final ServiceClientFailoverExecutor retryingExecutor = new ServiceClientFailoverExecutor(true, 2, new String[] { "GET" }, null, null, false,
				Duration.ZERO, balancer, ServiceClientEndpointResolver.WITHOUT_LAST_ENDPOINTS, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
				ServiceClientMetrics.NONE, ServiceClientConcurrencyLimiter.NONE, new ServiceClientRequestDeadlinePolicy(true, Duration.ofMillis(100), null));
		Assertions.assertNotNull(retryingExecutor.executeOverEndpoints(List.of("http://dead-first", "http://dead-second"), "/path", HttpMethod.GET,
				HttpEntity.EMPTY, ServiceClientRequestDeadlineTest.STRING_RESPONSE, Map.of(), null, null, connectTimingOutTemplate).failure());
		Assertions.assertTrue(balancer.isCoolingDown("http://dead-first"));
		Assertions.assertTrue(balancer.isCoolingDown("http://dead-second"));
		ServiceClientRequestDeadline.setDeadlineNanos(null);
		Assertions.assertNotNull(executor.executeOverEndpoints(List.of("http://slow"), "/path", HttpMethod.GET, HttpEntity.EMPTY,
				ServiceClientRequestDeadlineTest.STRING_RESPONSE, Map.of(), null, null, connectTimingOutTemplate).failure());
		Assertions.assertTrue(balancer.isCoolingDown("http://slow"));
	}

	/**
	 * With the deadline disabled, a call is as if there were none, even when it is
	 * spent.
	 *
	 * @throws IOException If the client cannot be closed.
	 */
	@Test
	public void testDeadlineDisabled() throws IOException {
		final ServiceClientFailoverExecutor executor = ServiceClientRequestDeadlineTest.createExecutor(Duration.ZERO, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
				ServiceClientMetrics.NONE, false);
		ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(-1000));
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			Assertions.assertEquals(ServiceClientRequestDeadlineTest.NO_HEADER,
					ServiceClientRequestDeadlineTest.callEcho(executor, null, ServiceClientRequestDeadlineTest.createTemplate(client)));
		}
	}

	/**
	 * A full-URL call (third parties) neither reads nor sends the deadline: with
	 * the deadline on and spent, it is still made, without the header, while an
	 * endpoint-list call through the same client fails at once.
	 *
	 * @throws Exception If the call fails.
	 */
	@Test
	public void testFullUrlCallUnchanged() throws Exception {
		final GenericRestServiceClient serviceClient = new GenericRestServiceClient();
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			serviceClient.setRestTemplate(ServiceClientRequestDeadlineTest.createTemplate(client));
			serviceClient.setFailoverExecutor(ServiceClientRequestDeadlineTest.createExecutor(Duration.ZERO, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
					ServiceClientMetrics.NONE, true));
			ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(-1000));
			Assertions.assertEquals(ServiceClientRequestDeadlineTest.NO_HEADER, serviceClient.executeOperation(ServiceClientRequestDeadlineTest.getServerUrl() + "/echo",
					HttpMethod.GET, null, null, null, ServiceClientRequestDeadlineTest.STRING_RESPONSE).getBody());
			final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
					() -> serviceClient.executeOperation(List.of(ServiceClientRequestDeadlineTest.getServerUrl()), "/echo", HttpMethod.GET, null, null, null,
							ServiceClientRequestDeadlineTest.STRING_RESPONSE, null));
			Assertions.assertEquals(ServiceClientFailoverExecutor.DEADLINE_EXPIRED_CODE, exception.getInternalMessage().getCode());
		}
	}

	/**
	 * The deadline is per thread: set, read and cleared; a time from now beyond a
	 * day is taken as a day.
	 */
	@Test
	public void testDeadlineHolder() {
		Assertions.assertNull(ServiceClientRequestDeadline.getDeadlineNanos());
		final long deadlineNanos = ServiceClientRequestDeadline.getDeadlineNanosIn(1000);
		ServiceClientRequestDeadline.setDeadlineNanos(deadlineNanos);
		Assertions.assertEquals(deadlineNanos, ServiceClientRequestDeadline.getDeadlineNanos());
		final long remainingNanos = ServiceClientRequestDeadline.getRemainingNanos(deadlineNanos);
		Assertions.assertTrue((remainingNanos > 0) && (remainingNanos <= Duration.ofSeconds(1).toNanos()));
		ServiceClientRequestDeadline.setDeadlineNanos(null);
		Assertions.assertNull(ServiceClientRequestDeadline.getDeadlineNanos());
		Assertions.assertTrue(ServiceClientRequestDeadline.getRemainingNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(Long.MAX_VALUE)) <= Duration
				.ofDays(1).toNanos());
		Assertions.assertTrue(ServiceClientRequestDeadline.getRemainingNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(Long.MIN_VALUE)) < 0);
	}

}
