package org.coldis.library.service.configuration;

import java.time.Duration;

import org.coldis.library.service.client.GenericRestServiceClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * Service client auto configuration.
 */
@Configuration
@PropertySource(value = { "classpath:service-client.properties" }, ignoreResourceNotFound = true)
public class ServiceClientAutoConfiguration {

	/**
	 * Service client timeout.)
	 */
	@Value(value = "${org.coldis.library.service-client.timeout}")
	private Long serviceClientTimeout;

	/**
	 * Creates the rest template.
	 *
	 * @param  restTemplateBuilder Rest template builder.
	 * @return                     The rest template.
	 */
	@Bean(name = "restTemplate")
	@Qualifier(value = "restTemplate")
	@ConditionalOnMissingBean(value = RestOperations.class)
	public RestTemplate createRestTemplate(final RestTemplateBuilder restTemplateBuilder) {
		return restTemplateBuilder.setConnectTimeout(Duration.ofSeconds(this.serviceClientTimeout))
				.setReadTimeout(Duration.ofSeconds(this.serviceClientTimeout)).build();
	}

	/**
	 * Creates the service client.
	 *
	 * @return The service client.
	 */
	@Bean(name = "restServiceClient")
	@Qualifier(value = "restServiceClient")
	public GenericRestServiceClient createRestServiceClient() {
		return new GenericRestServiceClient();
	}

}
