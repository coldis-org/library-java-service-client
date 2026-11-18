package org.coldis.library.test.service.client;

import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.client.generator.ServiceClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestOperations;

/**
 * Test integration: a service client source that extends
 * {@link GenericRestServiceClient} (as the integrations with third-party
 * services do) and declares two operations calling
 * {@link TestService#test4(Long)}, one of them an overload (not an override) of
 * a generic client method name. Its generated client must expose those
 * operations only, none of the methods inherited from
 * {@link GenericRestServiceClient} nor the ones overriding them
 * ({@link #getRestTemplate()}, not marked as ignored on purpose).
 */
@ServiceClient(
		targetPath = "src/test/java",
		namespace = "org.coldis.library.test.service.client",
		endpoint = "http://localhost:${local.server.port}/test"
)
public class TestRestIntegration extends GenericRestServiceClient {

	/**
	 * Overrides a generic REST service client method (infrastructure, never an
	 * operation, even with no annotation saying so).
	 *
	 * @see GenericRestServiceClient#getRestTemplate()
	 */
	@Override
	public RestOperations getRestTemplate() {
		return super.getRestTemplate();
	}

	/**
	 * Test operation: echoes the argument.
	 *
	 * @param  test Test argument.
	 * @return      The test argument.
	 */
	@RequestMapping(
			path = "test",
			method = RequestMethod.GET
	)
	public Long test4(
			@RequestParam
			final Long test) {
		return test;
	}

	/**
	 * Test operation named as a generic client method, but overloading it (a
	 * different signature), so still an operation: echoes the argument.
	 *
	 * @param  test Test argument.
	 * @return      The test argument.
	 */
	@RequestMapping(
			path = "test",
			method = RequestMethod.GET
	)
	public Long executeOperation(
			@RequestParam
			final Long test) {
		return test;
	}

}
