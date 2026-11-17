package org.coldis.library.service.client;

import java.time.Duration;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.convert.DurationStyle;
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

import io.micrometer.core.instrument.MeterRegistry;

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
	 * Service client metrics configuration (only with Micrometer).
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
	public static class ServiceClientMetricsConfiguration {

		/**
		 * Creates the service client metrics (a service may declare its own):
		 * recorded in the meter registry if there is one, none otherwise.
		 *
		 * @param  meterRegistry Meter registry.
		 * @return               The service client metrics.
		 */
		@Bean
		@ConditionalOnMissingBean
		public ServiceClientMetrics serviceClientMetrics(
				final ObjectProvider<MeterRegistry> meterRegistry) {
			final MeterRegistry availableMeterRegistry = meterRegistry.getIfAvailable();
			return (availableMeterRegistry == null ? ServiceClientMetrics.NONE : new MicrometerServiceClientMetrics(availableMeterRegistry));
		}

	}

	/**
	 * Converts a duration to a time value.
	 *
	 * @param  duration Duration (in the {@link DurationStyle} formats, e.g.
	 *                      {@code 15s}).
	 * @return          The time value.
	 */
	private static TimeValue toTimeValue(
			final String duration) {
		return TimeValue.ofMilliseconds(DurationStyle.detectAndParse(duration).toMillis());
	}

	/**
	 * HTTP client pooling manager.
	 *
	 * @param  maxTotal                Maximum total connections.
	 * @param  defaultMaxPerRoute      Maximum connections per host.
	 * @param  closeIdle               Idle time (seconds) after which connections
	 *                                     are closed.
	 * @param  closeExpired            If expired connections are closed.
	 * @param  timeToLive              How long a connection is kept, however used
	 *                                     (e.g. {@code 60s}).
	 * @param  validateAfterInactivity Idle time after which a pooled connection is
	 *                                     checked before it is reused (e.g.
	 *                                     {@code 1s}).
	 * @return                         HTTP client pooling manager.
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
			final Boolean closeExpired,
			@Value("${org.coldis.library.service-client.connection-time-to-live:60s}")
			final String timeToLive,
			@Value("${org.coldis.library.service-client.connection-validate-after-inactivity:1s}")
			final String validateAfterInactivity) {
		final PoolingHttpClientConnectionManager httpClientPoolingManager = new PoolingHttpClientConnectionManager();
		httpClientPoolingManager.setMaxTotal(maxTotal); // maximum total connections.
		httpClientPoolingManager.setDefaultMaxPerRoute(defaultMaxPerRoute); // maximum connections per host.
		httpClientPoolingManager.setDefaultConnectionConfig(ConnectionConfig.custom().setTimeToLive(ServiceClientAutoConfiguration.toTimeValue(timeToLive))
				.setValidateAfterInactivity(ServiceClientAutoConfiguration.toTimeValue(validateAfterInactivity)).build());
		if (closeIdle != null) {
			httpClientPoolingManager.closeIdle(TimeValue.ofSeconds(closeIdle)); // close idle connections.
		}
		if (closeExpired) {
			httpClientPoolingManager.closeExpired(); // close expired connections.
		}
		return httpClientPoolingManager;
	}

	/**
	 * Creates the keep-alive strategy of the HTTP client.
	 *
	 * @param  maxKeepAlive How long a pooled connection may stay idle and still be
	 *                          reused, at most (e.g. {@code 15s}).
	 * @return              The keep-alive strategy.
	 */
	@Bean
	public ServiceClientKeepAliveStrategy serviceClientKeepAliveStrategy(
			@Value("${org.coldis.library.service-client.connection-max-keep-alive:15s}")
			final String maxKeepAlive) {
		return new ServiceClientKeepAliveStrategy(DurationStyle.detectAndParse(maxKeepAlive));
	}

	/**
	 * Creates the HTTP client.
	 *
	 * @param  httpClientPoolingManager HTTP client pooling manager.
	 * @param  keepAliveStrategy        Keep-alive strategy.
	 * @param  closeIdle                Idle time (seconds) after which
	 *                                      connections are closed.
	 * @return                          The HTTP client.
	 */
	@Bean
	public HttpClient httpClient(
			final PoolingHttpClientConnectionManager httpClientPoolingManager,
			final ServiceClientKeepAliveStrategy keepAliveStrategy,
			@Value("${org.coldis.library.service-client.close-idle}")
			final Integer closeIdle) {
		return HttpClients.custom().setConnectionManager(httpClientPoolingManager).setKeepAliveStrategy(keepAliveStrategy)
				.setRetryStrategy(new ServiceClientRetryStrategy()).evictExpiredConnections().evictIdleConnections(TimeValue.ofSeconds(closeIdle)).build();
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
		final HttpComponentsClientHttpRequestFactory requestFactory = new ServiceClientRequestFactory(httpClient);
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
	 * Creates the service client endpoint balancer (a service may declare its own).
	 *
	 * @param  strategy        Balancing strategy ({@code least-in-flight} or
	 *                             {@code random}).
	 * @param  failureCooldown How long an endpoint that failed is skipped (e.g.
	 *                             {@code 10s}).
	 * @return                 The service client endpoint balancer.
	 */
	@Bean
	@ConditionalOnMissingBean
	public ServiceClientEndpointBalancer serviceClientEndpointBalancer(
			@Value(value = "${org.coldis.library.service-client.balancing.strategy:least-in-flight}")
			final String strategy,
			@Value(value = "${org.coldis.library.service-client.balancing.failure-cooldown:10s}")
			final String failureCooldown) {
		return new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.fromPropertyValue(strategy), DurationStyle.detectAndParse(failureCooldown));
	}

	/**
	 * Creates the scope that applies a generated client's own read timeout: the
	 * one of {@link ServiceClientRequestFactory}, the default template's factory.
	 * The scope and the template's request factory come as a pair: a replacement
	 * scope needs a factory that reads the timeout it sets.
	 *
	 * @return The read timeout scope.
	 */
	@Bean
	@ConditionalOnMissingBean
	public ServiceClientReadTimeoutScope serviceClientReadTimeoutScope() {
		return ServiceClientRequestFactory.READ_TIMEOUT_SCOPE;
	}

	/**
	 * Creates the service client failover executor (a service may declare its own).
	 *
	 * @param  isEnabled                If failover is enabled.
	 * @param  maxAttempts              Maximum attempts per operation.
	 * @param  idempotentMethods        Methods taken as idempotent by default.
	 * @param  retryStatuses            Response statuses retried for idempotent
	 *                                      operations (none by default).
	 * @param  cooldownStatuses         Response statuses that put the endpoint in
	 *                                      its cooldown (none by default).
	 * @param  shouldRetryOnReadTimeout If read timeouts are retried for idempotent
	 *                                      operations.
	 * @param  balancer                 Endpoint balancer.
	 * @param  readTimeoutScope         Scope that applies a call's own read
	 *                                      timeout to its attempts.
	 * @param  metrics                  Metrics (none without Micrometer).
	 * @return                          The service client failover executor.
	 */
	@Bean
	@ConditionalOnMissingBean(value = ServiceClientFailoverExecutor.class)
	public ServiceClientFailoverExecutor serviceClientFailoverExecutor(
			@Value(value = "${org.coldis.library.service-client.failover.enabled:true}")
			final Boolean isEnabled,
			@Value(value = "${org.coldis.library.service-client.failover.max-attempts:3}")
			final Integer maxAttempts,
			@Value(value = "${org.coldis.library.service-client.failover.idempotent-methods:GET,HEAD,OPTIONS}")
			final String[] idempotentMethods,
			@Value(value = "${org.coldis.library.service-client.failover.retry-statuses:}")
			final String[] retryStatuses,
			@Value(value = "${org.coldis.library.service-client.balancing.cooldown-statuses:}")
			final String[] cooldownStatuses,
			@Value(value = "${org.coldis.library.service-client.failover.retry-on-read-timeout:false}")
			final Boolean shouldRetryOnReadTimeout,
			final ServiceClientEndpointBalancer balancer,
			final ServiceClientReadTimeoutScope readTimeoutScope,
			final ObjectProvider<ServiceClientMetrics> metrics) {
		return new ServiceClientFailoverExecutor(isEnabled, maxAttempts, idempotentMethods, retryStatuses, cooldownStatuses, shouldRetryOnReadTimeout, balancer,
				readTimeoutScope, metrics.getIfAvailable(() -> ServiceClientMetrics.NONE));
	}

	/**
	 * Creates the observation filter that tags the HTTP client request
	 * observations ({@code http.client.requests}) with the target of the call.
	 *
	 * @return The observation filter.
	 */
	@Bean
	public ServiceClientTargetObservationFilter serviceClientTargetObservationFilter() {
		return new ServiceClientTargetObservationFilter();
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
