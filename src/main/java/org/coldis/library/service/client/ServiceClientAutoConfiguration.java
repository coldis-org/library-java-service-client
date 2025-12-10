package org.coldis.library.service.client;

import java.time.Duration;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
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
			httpClientPoolingManager.closeIdle(TimeValue.ofSeconds(30)); // close idle connections.
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
			final PoolingHttpClientConnectionManager httpClientPoolingManager) {
		return HttpClients.custom().setConnectionManager(httpClientPoolingManager).evictExpiredConnections().evictIdleConnections(TimeValue.ofSeconds(30))
				.build();
	}

	/**
	 * Creates the HTTP request factory.
	 *
	 * @param  httpClient HTTP client.
	 * @return            The HTTP request factory.
	 */
	@Bean
	public ClientHttpRequestFactory httpClientRequestFactory(
			final HttpClient httpClient) {
		return new HttpComponentsClientHttpRequestFactory(httpClient);

	}

	/**
	 * Creates the rest template.
	 *
	 * @param  restTemplateBuilder Rest template builder.
	 * @return                     The rest template.
	 */
	@Bean
	@Qualifier(value = "restTemplate")
	@ConditionalOnMissingBean(value = RestOperations.class)
	public RestTemplate restTemplate(
			final RestTemplateBuilder restTemplateBuilder,
			final ClientHttpRequestFactory httpClientRequestFactory,
			@Value(value = "${org.coldis.library.service-client.timeout}")
			final Long serviceClientTimeout) {
		return restTemplateBuilder.connectTimeout(Duration.ofSeconds(serviceClientTimeout)).readTimeout(Duration.ofSeconds(serviceClientTimeout))
				.requestFactory(() -> httpClientRequestFactory).build();

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
