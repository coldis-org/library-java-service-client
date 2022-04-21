package org.coldis.library.test.service.client;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.helper.UrlHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringValueResolver;

import java.util.*;

/**
  * Test service.
  */
@Service
public class TestServiceClient implements EmbeddedValueResolverAware {
	
	/**
	 * Value resolver.
	 */
	private StringValueResolver valueResolver;

	/**
	 * JMS template.
	 */
	@Autowired(required = false)
	private JmsTemplate jmsTemplate;
	
	/**
	 * Service client.
	 */
	@Autowired
	@Qualifier(value = "restServiceClient")
	private GenericRestServiceClient serviceClient;

	/**
	 * No arguments constructor.
	 */
	public TestServiceClient() {
		super();
	}
	
	/**
	 * @see org.springframework.context.EmbeddedValueResolverAware#
	 *      setEmbeddedValueResolver(org.springframework.util.StringValueResolver)
	 */
	@Override
	public void setEmbeddedValueResolver(final StringValueResolver resolver) {
		valueResolver = resolver;
	}
	private StringBuilder preparePath(String uri) {
		return new StringBuilder(this.valueResolver.resolveStringValue("http://localhost:8080/test/" + uri + "?"));
	}
	/**
	 * Test service.
  */
	public void test1(
			) throws BusinessException {
		// Operation parameters.
		StringBuilder path = preparePath("");
		final HttpMethod method = HttpMethod.GET;
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<?> returnType =
				new ParameterizedTypeReference<Void>() {};
		// Adds the content type headers.
		final MultiValueMap<String, String> headers = GenericRestServiceClient.createDefaultHeader(MediaType.APPLICATION_JSON_VALUE);
		path.append(UrlHelper.convertToQueryParameters(uriParameters.entrySet()));
		// Executes the operation and returns the response.
		this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType);

	}

	/**
	 * Test service.

 @param  test1 Test parameter.
 @param  test2 Test parameter.
 @param  test3 Test parameter.
 @param  test4 Test parameter.
 @param  test5 Test parameter.
 @param  test6 Test parameter.
 @return       Test object.
  */
	public org.coldis.library.test.service.client.dto.DtoTestObjectDto test2(
			org.coldis.library.test.service.client.dto.DtoTestObjectDto test1,
			java.lang.String test2,
			java.lang.String test3,
			java.lang.Integer test4,
			int[] test5,
			java.util.List<java.lang.Integer> test6) throws BusinessException {
		// Operation parameters.
		StringBuilder path = preparePath("");
		final HttpMethod method = HttpMethod.PUT;
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<org.coldis.library.test.service.client.dto.DtoTestObjectDto> returnType =
				new ParameterizedTypeReference<org.coldis.library.test.service.client.dto.DtoTestObjectDto>() {};
		// Adds the content type headers.
		final MultiValueMap<String, String> headers = GenericRestServiceClient.createDefaultHeader(MediaType.APPLICATION_JSON_VALUE);
		// Sets the operation body.
		body = test1;
		// Adds the header to the map.
		GenericRestServiceClient.addHeaders(headers, false, "test2", (test2 == null ? new String[] {} :
						List.of(test2.toString()).toArray(new String[] {})));
		GenericRestServiceClient.appendUriParameters(uriParameters, "test3", test3);
		// Adds the header to the map.
		GenericRestServiceClient.addHeaders(headers, false, "test4", (test4 == null ? new String[] {} :
						List.of(test4.toString()).toArray(new String[] {})));
		GenericRestServiceClient.appendUriParameters(uriParameters, "test5", test5);
		GenericRestServiceClient.appendUriParameters(uriParameters, "test6", test6);
		path.append(UrlHelper.convertToQueryParameters(uriParameters.entrySet()));
		// Executes the operation and returns the response.
		return this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();

	}

	/**
	 * Test service.

 @param  test Test argument.
 @return      Test object.
  */
	public org.springframework.core.io.Resource test3(
			org.coldis.library.service.model.FileResource test) throws BusinessException {
		// Operation parameters.
		StringBuilder path = preparePath("test");
		final HttpMethod method = HttpMethod.PUT;
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<org.springframework.core.io.Resource> returnType =
				new ParameterizedTypeReference<org.springframework.core.io.Resource>() {};
		// Adds the content type headers.
		final MultiValueMap<String, String> headers = GenericRestServiceClient.createDefaultHeader("MULTIPART/FORM-DATA");
		// Adds the part parameter to the map.
		partParameters.put("teste",
				(test == null ? List.of() : ((java.util.Collection.class.isAssignableFrom(test.getClass()) ?
						new ArrayList((java.util.Collection)(java.lang.Object)test) :
						List.of(test)))));
		path.append(UrlHelper.convertToQueryParameters(uriParameters.entrySet()));
		// Executes the operation and returns the response.
		return this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();

	}

	/**
	 * Test service.

 @param  test Test argument.
 @return      Test object.
  */
	public java.lang.Integer test4(
			java.lang.Long test) throws BusinessException {
		// Operation parameters.
		StringBuilder path = preparePath("test");
		final HttpMethod method = HttpMethod.GET;
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.Integer> returnType =
				new ParameterizedTypeReference<java.lang.Integer>() {};
		// Adds the content type headers.
		final MultiValueMap<String, String> headers = GenericRestServiceClient.createDefaultHeader(MediaType.APPLICATION_JSON_VALUE);
		GenericRestServiceClient.appendUriParameters(uriParameters, "test", test);
		path.append(UrlHelper.convertToQueryParameters(uriParameters.entrySet()));
		// Executes the operation and returns the response.
		return this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();

	}

	/**
	 * Test service.

 @param test Test argument.
  */
	public void test5Async(
			java.lang.Long test) throws BusinessException {
		jmsTemplate.convertAndSend("test5Async",
				test
			);
	}

	/**
	 * Test service.

 @param  test Test argument.
 @return      Test object.
  */
	public java.util.Map<java.lang.String,java.lang.Object> test6(
			java.lang.Long test) throws BusinessException {
		// Operation parameters.
		StringBuilder path = preparePath("a/{test}");
		final HttpMethod method = HttpMethod.GET;
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.util.Map<java.lang.String,java.lang.Object>> returnType =
				new ParameterizedTypeReference<java.util.Map<java.lang.String,java.lang.Object>>() {};
		// Adds the content type headers.
		final MultiValueMap<String, String> headers = GenericRestServiceClient.createDefaultHeader(MediaType.APPLICATION_JSON_VALUE);
		// Adds the path parameter to the map.
		path = new StringBuilder(path.toString().replace("{test}", Objects.toString(test)));
		path.append(UrlHelper.convertToQueryParameters(uriParameters.entrySet()));
		// Executes the operation and returns the response.
		return this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();

	}

	/**
	 * Test service.

 @param  test        Test argument.
 @return             Test object.
 @throws IOException Exception.
  */
	public java.lang.String test7(
			java.util.List<org.springframework.core.io.Resource> test) throws BusinessException {
		// Operation parameters.
		StringBuilder path = preparePath("parts");
		final HttpMethod method = HttpMethod.POST;
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.String> returnType =
				new ParameterizedTypeReference<java.lang.String>() {};
		// Adds the content type headers.
		final MultiValueMap<String, String> headers = GenericRestServiceClient.createDefaultHeader("MULTIPART/FORM-DATA");
		// Adds the part parameter to the map.
		partParameters.put("test",
				(test == null ? List.of() : ((java.util.Collection.class.isAssignableFrom(test.getClass()) ?
						new ArrayList((java.util.Collection)(java.lang.Object)test) :
						List.of(test)))));
		path.append(UrlHelper.convertToQueryParameters(uriParameters.entrySet()));
		// Executes the operation and returns the response.
		return this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();

	}


}