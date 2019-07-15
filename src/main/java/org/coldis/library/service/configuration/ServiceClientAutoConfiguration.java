package org.coldis.library.service.configuration;

import org.coldis.library.service.client.GenericRestServiceClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * Service client auto configuration.
 */
@Configuration
public class ServiceClientAutoConfiguration {

	/**
	 * Creates the rest template.
	 *
	 * @return The rest template.
	 */
	@Bean(name = "restTemplate")
	@Qualifier(value = "restTemplate")
	@ConditionalOnMissingBean(value = RestOperations.class)
	public RestTemplate createRestTemplate() {
		return new RestTemplate();
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
