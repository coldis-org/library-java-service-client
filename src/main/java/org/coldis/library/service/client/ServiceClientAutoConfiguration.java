package org.coldis.library.service.client;

import java.time.Duration;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * Service client auto configuration.
 */
@Order(-700)
@Configuration
@PropertySource(
		value = { "classpath:service-client.properties" },
		ignoreResourceNotFound = true
)
public class ServiceClientAutoConfiguration {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientAutoConfiguration.class);

	/**
	 * HTTP client pooling manager.
	 *
	 * @return HTTP client pooling manager.
	 */
	@Bean
	public PoolingHttpClientConnectionManager httpClientPoolingManager(
			@Value("${org.coldis.library.service-client.max-total}")
			final Integer maxTotal,
			@Value("${org.coldis.library.service-client.default-max-per-route}")
			final Integer defaultMaxPerRoute,
			@Value("${org.coldis.library.service-client.close-idle}")
			final Integer closeIdle,
			@Value("${org.coldis.library.service-client.close-expired}")
			final Boolean closeExpired) {
		final PoolingHttpClientConnectionManager httpClientPoolingManager = new PoolingHttpClientConnectionManager();
		httpClientPoolingManager.setMaxTotal(maxTotal); // maximum total connections.
		httpClientPoolingManager.setDefaultMaxPerRoute(defaultMaxPerRoute); // maximum connections per host.
		if (closeIdle != null) {
			httpClientPoolingManager.closeIdle(TimeValue.ofSeconds(closeIdle)); // close idle connections.
		}
		if (closeExpired) {
			httpClientPoolingManager.closeExpired(); // close expired connections.
		}
		return httpClientPoolingManager;
	}

	/**
	 * Creates the HTTP client.
	 *
	 * @param  pcm HTTP client pooling manager.
	 * @return     The HTTP client.
	 */
	@Bean
	public HttpClient httpClient(
			final PoolingHttpClientConnectionManager httpClientPoolingManager,
			@Value("${org.coldis.library.service-client.close-idle}")
			final Integer closeIdle) {
		return HttpClients.custom().setConnectionManager(httpClientPoolingManager).evictExpiredConnections().evictIdleConnections(TimeValue.ofSeconds(closeIdle))
				.build();
	}

	/**
	 * Creates an HTTP request factory over an HTTP client (every request factory of
	 * the library is created here).
	 *
	 * @param  httpClient               HTTP client.
	 * @param  connectionRequestTimeout Time to wait for a pooled connection
	 *                                      ({@code null} keeps the HTTP client
	 *                                      default).
	 * @return                          The HTTP request factory.
	 */
	private static HttpComponentsClientHttpRequestFactory createRequestFactory(
			final HttpClient httpClient,
			final Duration connectionRequestTimeout) {
		final HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
		if (connectionRequestTimeout != null) {
			requestFactory.setConnectionRequestTimeout(connectionRequestTimeout);
		}
		return requestFactory;
	}

	/**
	 * Creates an HTTP request factory over the pooled HTTP client, with no pool
	 * wait set (the HTTP client default, as before). It is prototype scoped:
	 * {@link RestTemplateBuilder} sets the connect and read timeouts on the factory
	 * it is given, so a factory shared by two templates would carry the timeouts of
	 * whichever was built last. Each injection point gets its own factory; all of
	 * them share the same HTTP client and connection pool. One injected factory
	 * must back one template only: to build several templates, inject it once per
	 * template, or use {@link #buildRestTemplate}.
	 *
	 * @param  httpClient HTTP client.
	 * @return            The HTTP request factory.
	 */
	@Bean
	@Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public ClientHttpRequestFactory httpClientRequestFactory(
			final HttpClient httpClient) {
		return ServiceClientAutoConfiguration.createRequestFactory(httpClient, null);
	}

	/**
	 * Builds a rest template with the given timeouts, over a request factory of its
	 * own (a new one at each build, so templates never share timeouts).
	 *
	 * @param  restTemplateBuilder      Rest template builder.
	 * @param  httpClient               HTTP client.
	 * @param  connectTimeout           Connect timeout.
	 * @param  connectionRequestTimeout Time to wait for a pooled connection.
	 * @param  readTimeout              Read timeout.
	 * @return                          The rest template.
	 */
	public static RestTemplate buildRestTemplate(
			final RestTemplateBuilder restTemplateBuilder,
			final HttpClient httpClient,
			final Duration connectTimeout,
			final Duration connectionRequestTimeout,
			final Duration readTimeout) {
		return restTemplateBuilder.connectTimeout(connectTimeout).readTimeout(readTimeout)
				.requestFactory(() -> ServiceClientAutoConfiguration.createRequestFactory(httpClient, connectionRequestTimeout)).build();
	}

	/**
	 * Creates the rest template.
	 *
	 * @param  restTemplateBuilder             Rest template builder.
	 * @param  httpClient                      HTTP client.
	 * @param  readTimeoutSeconds              Read timeout (seconds).
	 * @param  connectTimeoutSeconds           Connect timeout (seconds).
	 * @param  connectionRequestTimeoutSeconds Time to wait for a pooled connection
	 *                                             (seconds).
	 * @return                                 The rest template.
	 */
	@Bean
	@Qualifier(value = "restTemplate")
	@ConditionalOnMissingBean(value = RestOperations.class)
	public RestTemplate restTemplate(
			final RestTemplateBuilder restTemplateBuilder,
			final HttpClient httpClient,
			@Value(value = "${org.coldis.library.service-client.timeout}")
			final Long readTimeoutSeconds,
			@Value(value = "${org.coldis.library.service-client.connect-timeout}")
			final Long connectTimeoutSeconds,
			@Value(value = "${org.coldis.library.service-client.connection-request-timeout}")
			final Long connectionRequestTimeoutSeconds) {
		final RestTemplate restTemplate = ServiceClientAutoConfiguration.buildRestTemplate(restTemplateBuilder, httpClient,
				Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(connectionRequestTimeoutSeconds), Duration.ofSeconds(readTimeoutSeconds));
		ServiceClientAutoConfiguration.LOGGER.info("Default service client REST template timeouts: connect {}s, wait for a pooled connection {}s, read {}s.",
				connectTimeoutSeconds, connectionRequestTimeoutSeconds, readTimeoutSeconds);
		return restTemplate;
	}

	/**
	 * Creates the service client.
	 *
	 * @return The service client.
	 */
	@Bean
	@Qualifier(value = "restServiceClient")
	public GenericRestServiceClient restServiceClient() {
		return new GenericRestServiceClient();
	}

}
