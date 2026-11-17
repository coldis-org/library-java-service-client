package org.coldis.library.test.service.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.NoHttpResponseException;
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
	 * A call with no endpoint list (the path is the whole URL) that fails to
	 * connect puts nothing in cooldown.
	 */
	@Test
	public void testBlankEndpointStartsNoCooldown() {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT,
				Duration.ofSeconds(10));
		final ServiceClientFailoverExecutor executor = new ServiceClientFailoverExecutor(true, 3, new String[] { "GET" }, new String[] { "503" }, new String[] { "503" }, false,
				balancer, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE);
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		executor.executeOverEndpoints(List.of(), "http://host/path", HttpMethod.GET, HttpEntity.EMPTY, ServiceClientFailoverExecutorTest.STRING_RESPONSE,
				Map.of(), null, null, ServiceClientFailoverExecutorTest
						.createFailingTemplate(new ResourceAccessException("Refused.", new ConnectException("Refused.")), attemptedUrls));
		Assertions.assertEquals(List.of("http://host/path"), attemptedUrls);
		Assertions.assertEquals(0, balancer.getCooldownEndpointsCount());
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
		return new ServiceClientAutoConfiguration().serviceClientFailoverExecutor(true, 3, new String[] { "GET" }, null, null, false,
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
