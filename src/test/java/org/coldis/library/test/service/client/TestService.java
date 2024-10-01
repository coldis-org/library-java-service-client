package org.coldis.library.test.service.client;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.coldis.library.service.client.generator.ServiceClient;
import org.coldis.library.service.client.generator.ServiceClientOperation;
import org.coldis.library.service.client.generator.ServiceClientOperationParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonView;

/**
 * Test service.
 */
@RestController
@RequestMapping(path = "test")
@ServiceClient(
		targetPath = "src/test/java",
		namespace = "org.coldis.library.test.service.client",
		endpoint = "http://localhost:8080/test"
)
public class TestService {

	/**
	 * Internal state.
	 */
	private final Map<String, Object> state = new HashMap<>();

	/**
	 * Gets the current state.
	 *
	 * @return State.
	 */
	@ServiceClientOperation(ignore = true)
	public Map<String, Object> getState() {
		return this.state;
	}

	/**
	 * Test service.
	 */
	@JsonView
	@ServiceClientOperation(copiedAnnotations = { JsonView.class })
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
	 * @param  test6 Test parameter.
	 * @return       Test object.
	 */
	@RequestMapping(method = RequestMethod.PUT)
	public DtoTestObject test2(
			@RequestBody
			final DtoTestObject test1,
			@RequestHeader
			final String test2,
			@RequestParam
			final String test3,
			@RequestHeader
			final Integer test4,
			@RequestParam
			final int[] test5,
			@RequestParam
			final List<Integer> test6) {
		test1.setTest3(test2);
		test1.setTest5(test3);
		test1.setTest7(test4);
		test1.setTest8(test5);
		test1.setTest10(test6);
		return test1;
	}

	/**
	 * Test service.
	 *
	 * @param  test1 Test parameter.
	 * @param  test2 Test parameter.
	 * @param  test3 Test parameter.
	 * @param  test4 Test parameter.
	 * @param  test5 Test parameter.
	 * @param  test6 Test parameter.
	 * @return       Test object.
	 */
	@PutMapping(path = "22")
	public DtoTestObject test22(
			@RequestBody
			final DtoTestObject test1,
			@RequestHeader(required = false)
			final String test2,
			@RequestParam(required = false)
			final String test3,
			@RequestHeader
			final Integer test4,
			@RequestParam
			final int[] test5,
			@RequestParam(required = false)
			final List<Integer> test6) {
		test1.setTest3(test2);
		test1.setTest5(test3);
		test1.setTest7(test4);
		test1.setTest8(test5);
		test1.setTest10(test6);
		return test1;
	}

	/**
	 * Test service.
	 *
	 * @param  test Test argument.
	 * @return      Test object.
	 */
	@RequestMapping(
			path = "test",
			method = RequestMethod.PUT,
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE
	)
	public Resource test3(
			@RequestPart(
					name = "teste",
					required = false
			)
			@ServiceClientOperationParameter(type = "org.coldis.library.service.model.FileResource")
			final Resource test) {
		return test;
	}
	
	/**
	 * Test service.
	 *
	 * @param  test Test argument.
	 * @return      Test object.
	 */
	@RequestMapping(
			path = "test",
			method = RequestMethod.GET
	)
	@ServiceClientOperation(returnType = Integer.class)
	public Long test4(
			@RequestParam
			final Long test) {
		return test;
	}

	/**
	 * Test service.
	 *
	 * @param test Test argument.
	 */
	@Transactional
	@JmsListener(destination = "test5Async")
	@ServiceClientOperation(asynchronousDestination = "test5Async")
	public void test5Async(
			final Long test) {
		this.state.put("test5", test);
	}

	/**
	 * Test service.
	 *
	 * @param test Test argument.
	 */
	@Transactional
	@PostMapping(
			path = "test5"
	)
	public void test5(
			@RequestBody
			final Long test) {
		this.state.put("test5", test);
	}

	/**
	 * Test service.
	 *
	 * @param  test Test argument.
	 * @return      Test object.
	 */
	@RequestMapping(
			path = "a/{test}",
			method = RequestMethod.GET
	)
	public Map<String, Object> test6(
			@PathVariable
			final Long test) {
		this.state.put("test6", test);
		return this.state;
	}

	/**
	 * Test service.
	 *
	 * @param  test        Test argument.
	 * @return             Test object.
	 * @throws IOException Exception.
	 */
	@RequestMapping(
			path = "parts",
			method = RequestMethod.POST,
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE
	)
	public String test7(
			@RequestPart
			@ServiceClientOperationParameter(type = "java.util.List<org.springframework.core.io.Resource>")
			final MultipartFile[] test) throws IOException {
		return StreamUtils.copyToString(test[0].getInputStream(), Charset.forName("UTF-8"));
	}

}
