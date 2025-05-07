package org.coldis.library.test.service.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.ObjectHelper;
import org.coldis.library.helper.RandomHelper;
import org.coldis.library.helper.ReflectionHelper;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.client.generator.ServiceClientOperation;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.jms.JmsMessage;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringValueResolver;

/**
 * Test service.
  */
@Service
@SuppressWarnings({ "rawtypes", "unchecked" })
public class TestService2Client implements ApplicationContextAware, EmbeddedValueResolverAware {

	/** Application context. */
	private ApplicationContext applicationContext;

	/** Value resolver. */
	private StringValueResolver valueResolver;

	/**
	 * Fixed endpoint.
	 */
	private String fixedEndpoint;

	/**
	 * Endpoint bean.
	 */
	private Object endpointBean;

	/**
	 * Endpoint bean property.
	 */
	private String endpointBeanProperty = "endpoint";
	
	/** 
	 * Service path.
	 */
	@Value("test2")
	private String servicePath;

	/**
	 * Always-sync.
	 */
	@Value("${org.coldis.library.service-client.always-sync:false}")
	private Boolean alwaysSync;

	/**
	 * JMS template.
	 */
	@Autowired(required = false)
	private JmsTemplate jmsTemplate;

	/**
	 * JMS template helper.
	 */
	@Autowired(required = false)
	private JmsTemplateHelper jmsTemplateHelper;

	/**
	 * Service client.
	 */
	@Autowired
	@Qualifier(value = "restServiceClient")
	private GenericRestServiceClient serviceClient;

	/**
	 * No arguments constructor.
	 */
	public TestService2Client() {
		super();
	}

	/**
	 * @see ApplicationContextAware#
	 *     setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(final ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}


	/**
	 * @see org.springframework.context.EmbeddedValueResolverAware#
	 *      setEmbeddedValueResolver(org.springframework.util.StringValueResolver)
	 */
	@Override
	public void setEmbeddedValueResolver(final StringValueResolver resolver) {
		valueResolver = resolver;
	}

	/**
	 * Gets the fixed endpoint.
	 * @return The fixed endpoint.
	 */
	private String getFixedEndpoint() {
		this.fixedEndpoint = (this.fixedEndpoint == null ? this.valueResolver.resolveStringValue("") : this.fixedEndpoint);
		this.fixedEndpoint = (this.fixedEndpoint == null ? "" : this.fixedEndpoint);
		return this.fixedEndpoint;
	}

	/**
	 * Gets the endpoint bean.
	 * @return The endpoint bean.
	 */
	private Object getEndpointBean() {
		this.endpointBean = (this.endpointBean == null && StringUtils.isEmpty(this.getFixedEndpoint()) ? this.applicationContext.getBean("testService2Properties") : this.endpointBean);
		return this.endpointBean;
	}

	/**
	 * Gets the dynamic endpoint.
	 * @return The dynamic endpoint.
	 */
	private String getActualEndpoint() {
		String endpoint = this.getFixedEndpoint();
		final Object endpointBean = this.getEndpointBean();
		if (endpointBean != null && StringUtils.isNotBlank(this.endpointBeanProperty)) {
			endpoint = (String) ReflectionHelper.getAttribute(endpointBean, this.endpointBeanProperty);
			endpoint = (endpoint != null && endpoint.contains("${") ? valueResolver.resolveStringValue(endpoint) : endpoint);
		}
		return endpoint;
	}

	/**
	 * Gets all available endpoints.
	 * @return All available endpoints.
	 */
	private List<String> getEndpoints() {
		String endpoints = this.getActualEndpoint();
		return (endpoints == null ? null : List.of(endpoints.split(",")));
	}

	/**
	 * Gets one endpoint (balanced).
	 * @return One endpoint (balanced).
	 */
	private String getEndpoint() {
		List<String> endpoints = this.getEndpoints();
		return (CollectionUtils.isEmpty(endpoints) ? "" : endpoints.get(RandomHelper.getPositiveRandomLong((long) (endpoints.size())).intValue()));
	}
	
	/**
	 * Gets the service path.
	 * @return The service path.
	 */
	private String getPath() {
		String actualPath = (this.servicePath == null ? "" : this.servicePath);
		actualPath = (StringUtils.isBlank(actualPath) || actualPath.startsWith("/") ? actualPath : "/" + actualPath);
		return actualPath;
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("")
	private String endpointPath1;

	/**
	 * Test service.

	 * @throws BusinessException Any expected errors.
	 */
	
	public void test1(

			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath1;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder url = new StringBuilder(this.getEndpoint() + this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<?> returnType =
				new ParameterizedTypeReference<Void>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// Executes the operation and returns the response.
this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType);
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("{code}")
	private String endpointPath2;

	/**
	 * Test service.

	 * @throws BusinessException Any expected errors.
	 */
	
	public void test1(
java.lang.String code
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath2;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder url = new StringBuilder(this.getEndpoint() + this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<?> returnType =
				new ParameterizedTypeReference<Void>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// Adds the path parameter to the map.
		url = new StringBuilder(url.toString().replace("{code}", Objects.toString(code)));
		// Executes the operation and returns the response.
this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType);
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("")
	private String endpointPath3;

	/**
	 * Test service.

 @param  test1 Test parameter.
 @param  test2 Test parameter.
 @param  test3 Test parameter.
 @param  test4 Test parameter.
 @param  test5 Test parameter.
 @param  test6 Test parameter.
 @param  test7 Test parameter.
 @return       Test object.

	 * @throws BusinessException Any expected errors.
	 */
	
	public org.coldis.library.test.service.client.dto.DtoTestObjectDto test2(
org.coldis.library.test.service.client.dto.DtoTestObjectDto test1,
java.lang.String test2,
java.lang.String test3,
java.lang.Integer test4,
int[] test5,
java.util.List<java.lang.Integer> test7
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath3;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder url = new StringBuilder(this.getEndpoint() + this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.PUT;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<org.coldis.library.test.service.client.dto.DtoTestObjectDto> returnType =
				new ParameterizedTypeReference<org.coldis.library.test.service.client.dto.DtoTestObjectDto>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// Sets the operation body.
		body = test1;
		if (test2 != null) {
			// Adds the header to the map.
			GenericRestServiceClient.addHeaders(headers, false, "test2", ((String[])(java.util.Collection.class.isAssignableFrom(test2.getClass()) ?
			((java.util.Collection)(java.lang.Object)test2).stream().map(Objects::toString).toArray() :
			List.of(test2.toString()).toArray(new String[] {}))));
		}
		// If the parameter is an array.
		if (test3 != null && test3.getClass().isArray()) {
			// For each item.
			java.util.List test3s = java.util.Arrays.asList(test3);
			for (Integer parameterItemIndex = 0; parameterItemIndex < test3s.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test3" + parameterItemIndex, test3s.get(parameterItemIndex));
				url.append("test3={test3" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (test3 != null && java.lang.Iterable.class.isAssignableFrom(test3.getClass())) {
			// For each item.
			java.util.Iterator test3s = ((java.lang.Iterable)(java.lang.Object) test3).iterator();
			for (Integer parameterItemIndex = 0; test3s.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test3" + parameterItemIndex, test3s.next());
				url.append("test3={test3" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (test3 != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("test3", test3);
			url.append("test3={test3}&");
		}
		if (test4 != null) {
			// Adds the header to the map.
			GenericRestServiceClient.addHeaders(headers, false, "Test-Test", ((String[])(java.util.Collection.class.isAssignableFrom(test4.getClass()) ?
			((java.util.Collection)(java.lang.Object)test4).stream().map(Objects::toString).toArray() :
			List.of(test4.toString()).toArray(new String[] {}))));
		}
		// If the parameter is an array.
		if (test5 != null && test5.getClass().isArray()) {
			// For each item.
			java.util.List test5s = java.util.Arrays.asList(test5);
			for (Integer parameterItemIndex = 0; parameterItemIndex < test5s.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test5" + parameterItemIndex, test5s.get(parameterItemIndex));
				url.append("test5={test5" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (test5 != null && java.lang.Iterable.class.isAssignableFrom(test5.getClass())) {
			// For each item.
			java.util.Iterator test5s = ((java.lang.Iterable)(java.lang.Object) test5).iterator();
			for (Integer parameterItemIndex = 0; test5s.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test5" + parameterItemIndex, test5s.next());
				url.append("test5={test5" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (test5 != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("test5", test5);
			url.append("test5={test5}&");
		}
		// If the parameter is an array.
		if (test7 != null && test7.getClass().isArray()) {
			// For each item.
			java.util.List test7s = java.util.Arrays.asList(test7);
			for (Integer parameterItemIndex = 0; parameterItemIndex < test7s.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test7" + parameterItemIndex, test7s.get(parameterItemIndex));
				url.append("test7={test7" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (test7 != null && java.lang.Iterable.class.isAssignableFrom(test7.getClass())) {
			// For each item.
			java.util.Iterator test7s = ((java.lang.Iterable)(java.lang.Object) test7).iterator();
			for (Integer parameterItemIndex = 0; test7s.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test7" + parameterItemIndex, test7s.next());
				url.append("test7={test7" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (test7 != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("test7", test7);
			url.append("test7={test7}&");
		}
		// Executes the operation and returns the response.
return this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("22")
	private String endpointPath4;

	/**
	 * Test service.

 @param  test1 Test parameter.
 @param  test2 Test parameter.
 @param  test3 Test parameter.
 @param  test4 Test parameter.
 @param  test5 Test parameter.
 @param  test6 Test parameter.
 @param  test7 Test parameter.
 @return       Test object.

	 * @throws BusinessException Any expected errors.
	 */
	
	public org.coldis.library.test.service.client.dto.DtoTestObjectDto test22(
org.coldis.library.test.service.client.dto.DtoTestObjectDto test1,
java.lang.String test2,
java.lang.String test3,
java.lang.Integer test4,
int[] test5,
java.util.List<java.lang.Integer> test7
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath4;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder url = new StringBuilder(this.getEndpoint() + this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.PUT;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<org.coldis.library.test.service.client.dto.DtoTestObjectDto> returnType =
				new ParameterizedTypeReference<org.coldis.library.test.service.client.dto.DtoTestObjectDto>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// Sets the operation body.
		body = test1;
		if (test2 != null) {
			// Adds the header to the map.
			GenericRestServiceClient.addHeaders(headers, false, "test2", ((String[])(java.util.Collection.class.isAssignableFrom(test2.getClass()) ?
			((java.util.Collection)(java.lang.Object)test2).stream().map(Objects::toString).toArray() :
			List.of(test2.toString()).toArray(new String[] {}))));
		}
		// If the parameter is an array.
		if (test3 != null && test3.getClass().isArray()) {
			// For each item.
			java.util.List test3s = java.util.Arrays.asList(test3);
			for (Integer parameterItemIndex = 0; parameterItemIndex < test3s.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test3" + parameterItemIndex, test3s.get(parameterItemIndex));
				url.append("test3={test3" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (test3 != null && java.lang.Iterable.class.isAssignableFrom(test3.getClass())) {
			// For each item.
			java.util.Iterator test3s = ((java.lang.Iterable)(java.lang.Object) test3).iterator();
			for (Integer parameterItemIndex = 0; test3s.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test3" + parameterItemIndex, test3s.next());
				url.append("test3={test3" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (test3 != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("test3", test3);
			url.append("test3={test3}&");
		}
		if (test4 != null) {
			// Adds the header to the map.
			GenericRestServiceClient.addHeaders(headers, false, "Test-Test", ((String[])(java.util.Collection.class.isAssignableFrom(test4.getClass()) ?
			((java.util.Collection)(java.lang.Object)test4).stream().map(Objects::toString).toArray() :
			List.of(test4.toString()).toArray(new String[] {}))));
		}
		if (test5 != null) {
			// Adds the header to the map.
			GenericRestServiceClient.addHeaders(headers, false, "test5", Arrays.toString(test5).split("[\\[\\]]")[1].split(", ")
);
		}
		if (test7 != null) {
			// Adds the header to the map.
			GenericRestServiceClient.addHeaders(headers, false, "Test-Test2", ((String[])(java.util.Collection.class.isAssignableFrom(test7.getClass()) ?
			((java.util.Collection)(java.lang.Object)test7).stream().map(Objects::toString).toArray() :
			List.of(test7.toString()).toArray(new String[] {}))));
		}
		// Executes the operation and returns the response.
return this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("/test")
	private String endpointPath5;

	/**
	 * Test service.

 @param  test Test argument.
 @return      Test object.

	 * @throws BusinessException Any expected errors.
	 */
	
	public org.springframework.core.io.Resource test3(
org.coldis.library.service.model.FileResource test
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath5;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder url = new StringBuilder(this.getEndpoint() + this.getPath() + endpointPath + "?");
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
				(test == null ? List.of() : ((java.util.Collection.class.isAssignableFrom(test.getClass()) ?
		new ArrayList((java.util.Collection)(java.lang.Object)test) :
		List.of(test)))));
		// Executes the operation and returns the response.
return this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("/test")
	private String endpointPath6;

	/**
	 * Test service.

 @param  test Test argument.
 @return      Test object.

	 * @throws BusinessException Any expected errors.
	 */
	
	public java.lang.Integer test4(
java.lang.Long test
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath6;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder url = new StringBuilder(this.getEndpoint() + this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.Integer> returnType =
				new ParameterizedTypeReference<java.lang.Integer>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// If the parameter is an array.
		if (test != null && test.getClass().isArray()) {
			// For each item.
			java.util.List tests = java.util.Arrays.asList(test);
			for (Integer parameterItemIndex = 0; parameterItemIndex < tests.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test" + parameterItemIndex, tests.get(parameterItemIndex));
				url.append("test={test" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (test != null && java.lang.Iterable.class.isAssignableFrom(test.getClass())) {
			// For each item.
			java.util.Iterator tests = ((java.lang.Iterable)(java.lang.Object) test).iterator();
			for (Integer parameterItemIndex = 0; tests.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test" + parameterItemIndex, tests.next());
				url.append("test={test" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (test != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("test", test);
			url.append("test={test}&");
		}
		// Executes the operation and returns the response.
return this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("")
	private String endpointPath7;

	/**
	 * Test service.

 @param test Test argument.

	 * @throws BusinessException Any expected errors.
	 */
	
	public void test5Async(
	JmsMessage<java.lang.Long> message
			) throws BusinessException {
		String syncMethodName = "test5Async".replaceAll("Async", "");
		Method syncMethod = MethodUtils.getMatchingMethod(this.getClass(), syncMethodName, message.getMessage().getClass());
		if (this.alwaysSync && syncMethod != null && message.getMessage() != null) {
			try {
				MethodUtils.invokeMethod(this, syncMethodName, message.getMessage());
			}
			catch (Exception exception) {
				throw new BusinessException(new SimpleMessage("client.error"), exception);
			}
		}
		else if (jmsTemplateHelper == null) {
			jmsTemplate.convertAndSend("2test5Async", message.getMessage());
		}
		else {
			jmsTemplateHelper.send(jmsTemplate, message.withDestination("2test5Async"));
		}
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("a/{test}")
	private String endpointPath8;

	/**
	 * Test service.

 @param  test Test argument.
 @return      Test object.

	 * @throws BusinessException Any expected errors.
	 */
	
	public java.util.Map<java.lang.String,java.lang.Object> test6(
java.lang.Long test
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath8;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder url = new StringBuilder(this.getEndpoint() + this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.util.Map<java.lang.String,java.lang.Object>> returnType =
				new ParameterizedTypeReference<java.util.Map<java.lang.String,java.lang.Object>>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
MediaType.APPLICATION_JSON_VALUE);
		// Adds the path parameter to the map.
		url = new StringBuilder(url.toString().replace("{test}", Objects.toString(test)));
		// Executes the operation and returns the response.
return this.serviceClient.executeOperation(url.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType).getBody();
				
	}
	

}