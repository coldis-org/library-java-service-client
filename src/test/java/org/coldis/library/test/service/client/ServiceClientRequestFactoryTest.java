package org.coldis.library.test.service.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.coldis.library.service.client.ServiceClientRequestFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Service client request factory test (plain objects).
 */
public class ServiceClientRequestFactoryTest {

	/**
	 * Request factory that exposes the request configuration it creates.
	 */
	private static class InspectableRequestFactory extends ServiceClientRequestFactory {

		/**
		 * Constructor.
		 *
		 * @param httpClient HTTP client.
		 */
		InspectableRequestFactory(final CloseableHttpClient httpClient) {
			super(httpClient);
		}

		/**
		 * Gets the request configuration of a request made now.
		 *
		 * @return The request configuration.
		 */
		RequestConfig getRequestConfig() {
			return this.createRequestConfig(this.getHttpClient());
		}

	}

	/**
	 * The read timeout of the current call replaces only the response timeout (the
	 * wait for a pooled connection is kept), an inner call's timeout gives way to
	 * the outer one when it ends, and none is left after the outer one.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testReadTimeoutOverride() throws Exception {
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			final InspectableRequestFactory requestFactory = new InspectableRequestFactory(httpClient);
			requestFactory.setConnectionRequestTimeout(Duration.ofSeconds(5));
			requestFactory.setReadTimeout(Duration.ofSeconds(9));
			Assertions.assertEquals(Timeout.of(9, TimeUnit.SECONDS), requestFactory.getRequestConfig().getResponseTimeout());
			ServiceClientRequestFactory.runWithReadTimeout(Duration.ofSeconds(2), () -> {
				final RequestConfig outerConfig = requestFactory.getRequestConfig();
				Assertions.assertEquals(Timeout.of(2, TimeUnit.SECONDS), outerConfig.getResponseTimeout());
				Assertions.assertEquals(Timeout.of(5, TimeUnit.SECONDS), outerConfig.getConnectionRequestTimeout());
				ServiceClientRequestFactory.runWithReadTimeout(Duration.ofSeconds(1), () -> {
					Assertions.assertEquals(Timeout.of(1, TimeUnit.SECONDS), requestFactory.getRequestConfig().getResponseTimeout());
					return null;
				});
				Assertions.assertEquals(Timeout.of(2, TimeUnit.SECONDS), requestFactory.getRequestConfig().getResponseTimeout());
				return null;
			});
			Assertions.assertEquals(Timeout.of(9, TimeUnit.SECONDS), requestFactory.getRequestConfig().getResponseTimeout());
		}
	}

	/**
	 * A zero or negative read timeout is not one of its own: the factory's applies,
	 * also inside an outer call with a read timeout.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testNonPositiveReadTimeoutKeepsFactoryTimeout() throws Exception {
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			final InspectableRequestFactory requestFactory = new InspectableRequestFactory(httpClient);
			requestFactory.setReadTimeout(Duration.ofSeconds(9));
			for (final Duration readTimeout : new Duration[] { Duration.ZERO, Duration.ofSeconds(-1) }) {
				Assertions.assertFalse(ServiceClientRequestFactory.hasOwnReadTimeout(readTimeout));
				ServiceClientRequestFactory.runWithReadTimeout(Duration.ofSeconds(2), () -> ServiceClientRequestFactory.runWithReadTimeout(readTimeout, () -> {
					Assertions.assertEquals(Timeout.of(9, TimeUnit.SECONDS), requestFactory.getRequestConfig().getResponseTimeout());
					return null;
				}));
			}
		}
	}

	/**
	 * Without any factory timeouts, the current call's read timeout still applies
	 * (over the default request configuration).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testReadTimeoutWithoutFactoryTimeouts() throws Exception {
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			final InspectableRequestFactory requestFactory = new InspectableRequestFactory(httpClient);
			ServiceClientRequestFactory.runWithReadTimeout(Duration.ofSeconds(3), () -> {
				Assertions.assertEquals(Timeout.of(3, TimeUnit.SECONDS), requestFactory.getRequestConfig().getResponseTimeout());
				return null;
			});
			// Under one millisecond rounds up to one (zero would mean no timeout).
			ServiceClientRequestFactory.runWithReadTimeout(Duration.ofNanos(500_000), () -> {
				Assertions.assertEquals(Timeout.of(1, TimeUnit.MILLISECONDS), requestFactory.getRequestConfig().getResponseTimeout());
				return null;
			});
		}
	}

	/**
	 * The read timeout scope applies the read timeout to the requests made while
	 * the action runs, through the template given.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testReadTimeoutScope() throws Exception {
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			final InspectableRequestFactory requestFactory = new InspectableRequestFactory(httpClient);
			ServiceClientRequestFactory.READ_TIMEOUT_SCOPE.runWithReadTimeout(new RestTemplate(requestFactory), Duration.ofSeconds(4), () -> {
				Assertions.assertEquals(Timeout.of(4, TimeUnit.SECONDS), requestFactory.getRequestConfig().getResponseTimeout());
				return null;
			});
		}
	}

	/**
	 * A read timeout given for a template whose factory is visibly not a service
	 * client request factory is warned about once per template; no warning without
	 * a read timeout of its own, for a service client request factory, or when the
	 * template hides its factory (interceptors or a wrapping factory).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testWarningForIgnoredReadTimeout() throws Exception {
		final Duration readTimeout = Duration.ofSeconds(1);
		final RestTemplate simpleTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
		Assertions.assertTrue(ServiceClientRequestFactory.warnIfReadTimeoutIgnored(simpleTemplate, readTimeout));
		Assertions.assertFalse(ServiceClientRequestFactory.warnIfReadTimeoutIgnored(simpleTemplate, readTimeout));
		for (final Duration ignoredReadTimeout : new Duration[] { null, Duration.ZERO, Duration.ofSeconds(-1) }) {
			Assertions.assertFalse(
					ServiceClientRequestFactory.warnIfReadTimeoutIgnored(new RestTemplate(new SimpleClientHttpRequestFactory()), ignoredReadTimeout));
		}
		final RestTemplate interceptedTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
		interceptedTemplate.setInterceptors(List.of((request, body, execution) -> execution.execute(request, body)));
		Assertions.assertFalse(ServiceClientRequestFactory.warnIfReadTimeoutIgnored(interceptedTemplate, readTimeout));
		Assertions.assertFalse(ServiceClientRequestFactory
				.warnIfReadTimeoutIgnored(new RestTemplate(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())), readTimeout));
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			Assertions.assertFalse(ServiceClientRequestFactory.warnIfReadTimeoutIgnored(new RestTemplate(new ServiceClientRequestFactory(httpClient)), readTimeout));
		}
	}

}
