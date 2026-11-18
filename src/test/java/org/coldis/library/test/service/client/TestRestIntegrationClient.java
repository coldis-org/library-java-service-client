package org.coldis.library.test.service.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.ObjectHelper;
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
 * Test integration: a service client source that extends
 {@link GenericRestServiceClient} (as the integrations with third-party
 services do) and declares two operations calling
 {@link TestService#test4(Long)}, one of them an overload (not an override) of
 a generic client method name. Its generated client must expose those
 operations only, none of the methods inherited from
 {@link GenericRestServiceClient} nor the ones overriding them
 ({@link #getRestTemplate()}, not marked as ignored on purpose).
  */
@Service
@SuppressWarnings({ "rawtypes", "unchecked" })
public class TestRestIntegrationClient implements ApplicationContextAware, EmbeddedValueResolverAware {

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
	@Value("")
	private String servicePath;

	/**
	 * Always-sync.
	 */
	@Value("${org.coldis.library.service-client.always-sync:false}")
	private Boolean alwaysSync;

	/**
	 * Read timeout of this client's calls, in seconds:
	 * org.coldis.library.service-client.timeout.TestRestIntegrationClient (none, zero or negative: the template's).
	 * Applies only to templates whose request factory is the library's (ServiceClientRequestFactory).
	 * The key sits under the global timeout's, so in YAML it is written flat (quoted), not nested.
	 */
	@Value("${org.coldis.library.service-client.timeout.TestRestIntegrationClient:#{null}}")
	private Long readTimeoutSeconds;

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
	 * Sets the service client.
	 * @param serviceClient Service client.
	 */
	public void setServiceClient(final GenericRestServiceClient serviceClient) {
		this.serviceClient = serviceClient;
	}

	/**
	 * No arguments constructor.
	 */
	public TestRestIntegrationClient() {
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
		this.fixedEndpoint = (this.fixedEndpoint == null ? this.valueResolver.resolveStringValue("http://localhost:${local.server.port}/test") : this.fixedEndpoint);
		this.fixedEndpoint = (this.fixedEndpoint == null ? "" : this.fixedEndpoint);
		return this.fixedEndpoint;
	}

	/**
	 * Gets the endpoint bean.
	 * @return The endpoint bean.
	 */
	private Object getEndpointBean() {
		this.endpointBean = (this.endpointBean == null && StringUtils.isEmpty(this.getFixedEndpoint()) ? this.applicationContext.getBean("") : this.endpointBean);
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
	 * Gets all available endpoints (the operation fails over them).
	 * @return All available endpoints.
	 */
	private List<String> getEndpoints() {
		String endpoints = this.getActualEndpoint();
		return (endpoints == null ? null : List.of(endpoints.split(",")));
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
	@Value("test")
	private String endpointPath1;

	/**
	 * Test operation: echoes the argument.

 @param  test Test argument.
 @return      The test argument.

	 * @throws BusinessException Any expected errors.
	 */
	
	public java.lang.Long test4(
java.lang.Long test
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath1;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder pathAndQuery = new StringBuilder(this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.Long> returnType =
				new ParameterizedTypeReference<java.lang.Long>() {};
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
				pathAndQuery.append("test={test" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (test != null && java.lang.Iterable.class.isAssignableFrom(test.getClass())) {
			// For each item.
			java.util.Iterator tests = ((java.lang.Iterable)(java.lang.Object) test).iterator();
			for (Integer parameterItemIndex = 0; tests.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test" + parameterItemIndex, tests.next());
				pathAndQuery.append("test={test" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (test != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("test", test);
			pathAndQuery.append("test={test}&");
		}
		// Executes the operation (failing over the endpoints) and returns the response.
return this.serviceClient.executeOperation(this.getEndpoints(), pathAndQuery.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType, null,
				(this.readTimeoutSeconds == null ? null : java.time.Duration.ofSeconds(this.readTimeoutSeconds)), "TestRestIntegrationClient").getBody();
				
	}
	

	/**
	 * Endpoint for the operation.
	 */
	@Value("test")
	private String endpointPath2;

	/**
	 * Test operation named as a generic client method, but overloading it (a
 different signature), so still an operation: echoes the argument.

 @param  test Test argument.
 @return      The test argument.

	 * @throws BusinessException Any expected errors.
	 */
	
	public java.lang.Long executeOperation(
java.lang.Long test
			) throws BusinessException {
		// Operation parameters.
		String endpointPath = endpointPath2;
		endpointPath = (StringUtils.isBlank(endpointPath) || endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath);
		StringBuilder pathAndQuery = new StringBuilder(this.getPath() + endpointPath + "?");
		final HttpMethod method = HttpMethod.GET;
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<java.lang.Long> returnType =
				new ParameterizedTypeReference<java.lang.Long>() {};
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
				pathAndQuery.append("test={test" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (test != null && java.lang.Iterable.class.isAssignableFrom(test.getClass())) {
			// For each item.
			java.util.Iterator tests = ((java.lang.Iterable)(java.lang.Object) test).iterator();
			for (Integer parameterItemIndex = 0; tests.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("test" + parameterItemIndex, tests.next());
				pathAndQuery.append("test={test" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (test != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("test", test);
			pathAndQuery.append("test={test}&");
		}
		// Executes the operation (failing over the endpoints) and returns the response.
return this.serviceClient.executeOperation(this.getEndpoints(), pathAndQuery.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType, null,
				(this.readTimeoutSeconds == null ? null : java.time.Duration.ofSeconds(this.readTimeoutSeconds)), "TestRestIntegrationClient").getBody();
				
	}
	

}