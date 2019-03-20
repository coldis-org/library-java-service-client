package org.coldis.library.test.service.client;

import java.util.List;

import org.coldis.library.service.client.generator.ServiceClient;
import org.coldis.library.service.client.generator.ServiceClientOperation;
import org.coldis.library.service.client.generator.ServiceClientOperationParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test service.
 */
@RestController
@RequestMapping(path = "test")
@ServiceClient(namespace = "org.coldis.library.test.service.client",
superclass = "org.coldis.library.service.client.GenericRestServiceClient",
endpoint = "http://localhost:8080/test")
public class TestService {

	/**
	 * Test service.
	 */
	@RequestMapping(method = RequestMethod.GET)
	public void test1() {

	}

	/**
	 * Test service.
	 *
	 * @param  test1 Test parameter.
	 * @param  test2 Test parameter.
	 * @param  test3 Test parameter.
	 * @param  test4 Test parameter.
	 * @param  test5 Test parameter.
	 *
	 * @param  test  Test argument.
	 * @return       Test object.
	 */
	@ServiceClientOperation(method = "PUT")
	@RequestMapping(method = RequestMethod.PUT)
	public DtoTestObject test2(@RequestBody @ServiceClientOperationParameter(kind = "body") final DtoTestObject test1,
			@RequestHeader @ServiceClientOperationParameter(kind = "header") final String test2,
			@RequestParam @ServiceClientOperationParameter(kind = "uriParameter") final String test3,
			@RequestHeader @ServiceClientOperationParameter(kind = "header") final Integer test4,
			@RequestParam @ServiceClientOperationParameter(kind = "uriParameter") final int[] test5) {
		test1.setTest3(test2);
		test1.setTest5(test3);
		test1.setTest7(test4);
		test1.setTest8(test5);
		return test1;
	}

	/**
	 * Test service.
	 *
	 * @param  test Test argument.
	 * @return      Test object.
	 */
	@ServiceClientOperation(path = "/test", method = "PUT")
	@RequestMapping(path = "test", method = RequestMethod.PUT)
	public List<DtoTestObject> test3(
			@RequestBody @ServiceClientOperationParameter(kind = "body") final List<DtoTestObject> test) {
		return test;
	}

}
