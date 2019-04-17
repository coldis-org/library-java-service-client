package org.coldis.library.test.service.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
  * Test service.
  */
@Service
public class TestServiceClient extends org.coldis.library.service.client.GenericRestServiceClient {

	/**
	 * No arguments constructor.
	 */
	public TestServiceClient() {
		super();
	}

	/**
	 * Test service.
  */
	public void test1(
			) throws BusinessException, IntegrationException {
		// Operation parameters.
		StringBuilder path = new StringBuilder("http://localhost:8080/test/?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<?> returnType =
				new ParameterizedTypeReference<Void>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
				MediaType.APPLICATION_JSON_UTF8_VALUE);
		// Executes the operation and returns the response.
		this.executeOperation(path.toString(), method, headers,
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

 @param  test  Test argument.
 @return       Test object.
  */
	public org.coldis.library.test.service.client.dto.DtoTestObjectDto test2(
			org.coldis.library.test.service.client.dto.DtoTestObjectDto test1,
			java.lang.String test2,
			java.lang.String test3,
			java.lang.Integer test4,
			int[] test5) throws BusinessException, IntegrationException {
		// Operation parameters.
		StringBuilder path = new StringBuilder("http://localhost:8080/test/?");
		final HttpMethod method = HttpMethod.PUT;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<org.coldis.library.test.service.client.dto.DtoTestObjectDto> returnType =
				new ParameterizedTypeReference<org.coldis.library.test.service.client.dto.DtoTestObjectDto>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
				MediaType.APPLICATION_JSON_UTF8_VALUE);
		// Sets the operation body.
		body = test1;
		// Adds the header to the map.
		GenericRestServiceClient.addHeaders(headers, false, "test2", test2 == null ? null : test2.toString());
		// Adds the URI parameter to the map.
		uriParameters.put("test3", test3);
		path.append("test3={test3}&");
		// Adds the header to the map.
		GenericRestServiceClient.addHeaders(headers, false, "test4", test4 == null ? null : test4.toString());
		// Adds the URI parameter to the map.
		uriParameters.put("test5", test5);
		path.append("test5={test5}&");
		// Executes the operation and returns the response.
		return this.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();
	}
	
	/**
	 * Test service.

 @param  test Test argument.
 @return      Test object.
  */
	public org.springframework.core.io.Resource test3(
			org.coldis.library.service.model.FileResource teste) throws BusinessException, IntegrationException {
		// Operation parameters.
		StringBuilder path = new StringBuilder("http://localhost:8080/test//test?");
		final HttpMethod method = HttpMethod.PUT;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<org.springframework.core.io.Resource> returnType =
				new ParameterizedTypeReference<org.springframework.core.io.Resource>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
				"MULTIPART/FORM-DATA");
		// Adds the part parameter to the map.
		partParameters.put("teste",
				List.of(teste));
		// Executes the operation and returns the response.
		return this.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();
	}
	
	/**
	 * Test service.

 @param  test Test argument.
 @return      Test object.
  */
	public java.lang.Integer test4(
			java.lang.Long test) throws BusinessException, IntegrationException {
		// Operation parameters.
		StringBuilder path = new StringBuilder("http://localhost:8080/test//test?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.Integer> returnType =
				new ParameterizedTypeReference<java.lang.Integer>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
				MediaType.APPLICATION_JSON_UTF8_VALUE);
		// Adds the URI parameter to the map.
		uriParameters.put("test", test);
		path.append("test={test}&");
		// Executes the operation and returns the response.
		return this.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();
	}
	
	/**
	 * Test service.

 @param  test Test argument.
 @return      Test object.
  */
	public java.lang.Integer test5(
			java.lang.Long test) throws BusinessException, IntegrationException {
		// Operation parameters.
		StringBuilder path = new StringBuilder("http://localhost:8080/test/a/{test}?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.Integer> returnType =
				new ParameterizedTypeReference<java.lang.Integer>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
				MediaType.APPLICATION_JSON_UTF8_VALUE);
		// Adds the path parameter to the map.
		path = new StringBuilder(path.toString().replace("{test}", Objects.toString(test)));
		// Executes the operation and returns the response.
		return this.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();
	}
	

}