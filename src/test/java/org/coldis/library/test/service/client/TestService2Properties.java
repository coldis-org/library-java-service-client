package org.coldis.library.test.service.client;

import org.springframework.stereotype.Component;

/**
 * Test service properties.
 */
@Component
public class TestService2Properties {

	/**
	 * Service endpoint.
	 */
	private String endpoint = "http://localhost:${local.server.port},";

	/**
	 * Gets the endpoint.
	 *
	 * @return The endpoint.
	 */
	public String getEndpoint() {
		return this.endpoint;
	}

	/**
	 * Sets the endpoint.
	 *
	 * @param endpoint New endpoint.
	 */
	public void setEndpoint(
			final String endpoint) {
		this.endpoint = endpoint;
	}

}
