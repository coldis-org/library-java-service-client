package org.coldis.library.test.service.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.coldis.library.service.client.ServiceClientEndpointBalancer;
import org.coldis.library.service.client.ServiceClientFailoverExecutor;
import org.coldis.library.service.client.ServiceClientReadTimeoutScope;
import org.coldis.library.service.client.ServiceClientRequestFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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

}
