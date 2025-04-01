package org.coldis.library.test.service.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.coldis.library.service.client.generator.ServiceClient;
import org.coldis.library.service.client.generator.ServiceClientOperation;
import org.coldis.library.service.client.generator.ServiceClientOperationParameter;
import org.coldis.library.service.client.generator.ServiceOperationParameterKind;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test service properties.
 */ 
@Component
public class TestService2Properties {

	/**
	 * Service endpoint.
	 */
	private String endpoint = "http://localhost:${local.server.port}/test2";

	/**
	 * Gets the endpoint.
	 * @return The endpoint.
	 */
	public String getEndpoint() {
		return endpoint;
	}

	/**
	 * Sets the endpoint.
	 * @param endpoint New endpoint.
	 */
	public void setEndpoint(
			String endpoint) {
		this.endpoint = endpoint;
	}
	
}