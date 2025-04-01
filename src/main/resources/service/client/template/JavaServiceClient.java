package ${serviceClient.namespace};

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
  *${serviceClient.docComment}  */
@Service
@SuppressWarnings({ "rawtypes", "unchecked" })
public class ${serviceClient.name}#{if}(!${serviceClient.superclass.isEmpty()}) extends ${serviceClient.superclass}#{end} implements ApplicationContextAware, EmbeddedValueResolverAware {
	
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
	private String endpointBeanProperty = "${serviceClient.endpointBeanProperty}";
	
	/**
	 * Always-sync.
	 */
	@Value("#[[$]]#{org.coldis.library.service-client.always-sync:false}")
	private Boolean alwaysSync;

	/**
	 * JMS template.
	 */
	@Autowired(required = false)
	#{if}(!${serviceClient.jmsListenerQualifier.isEmpty()})
	@Qualifier(value = "${serviceClient.jmsListenerQualifier}")
	#{end}
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
	#{if}(!${serviceClient.serviceClientQualifier.isEmpty()})
	@Qualifier(value = "${serviceClient.serviceClientQualifier}")
	#{end}
	private GenericRestServiceClient serviceClient;

	/**
	 * No arguments constructor.
	 */
	public ${serviceClient.name}() {
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
		this.fixedEndpoint = (this.fixedEndpoint == null ? this.valueResolver.resolveStringValue("${serviceClient.endpoint}") : this.fixedEndpoint);
		this.fixedEndpoint = (this.fixedEndpoint == null ? "" : this.fixedEndpoint);
		return this.fixedEndpoint;
	}
	
	/**
	 * Gets the endpoint bean.
	 * @return The endpoint bean.
	 */
	private Object getEndpointBean() {
		this.endpointBean = (this.endpointBean == null && StringUtils.isEmpty(this.getFixedEndpoint()) ? this.applicationContext.getBean("${serviceClient.endpointBean}") : this.endpointBean);
		return this.endpointBean;
	}
	
	/**
	 * Gets the dynamic endpoint.
	 * @return The dynamic endpoint.
	 */
	private String getDynamicEndpoint() {
		String endpoint = this.getFixedEndpoint();
		final Object endpointBean = this.getEndpointBean();
		if (endpointBean != null && StringUtils.isNotBlank(this.endpointBeanProperty)) {
			endpoint = (String) ReflectionHelper.getAttribute(endpointBean, this.endpointBeanProperty);
		}
		return endpoint;
	}
	
	/**
	 * Gets all available endpoints.
	 * @return All available endpoints.
	 */
	private List<String> getEndpoints() {
		String endpoints = this.getFixedEndpoint();
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
	
#{foreach}( ${operation} in ${serviceClient.operations} )

	/**
	 * Endpoint for the operation.
	 */
	@Value("${operation.path}")
	private String ${operation.name}Path;

	/**
	 *${operation.docComment} 
	 * @throws BusinessException Any expected errors.
	 */
	${operation.annotations}
	public ${operation.returnType} ${operation.name}(
		#{if}(${operation.asynchronousDestination.isEmpty()})
			#{set}($currentItemIdx = 0)#{foreach}( ${parameter} in ${operation.parameters} )#{if}(${currentItemIdx} > 0),
			#{end}#{set}($currentItemIdx = $currentItemIdx + 1)${parameter.type} ${parameter.originalName}#{end}
		#{else}
			JmsMessage<${operation.parameters[0].type}> message
		#{end}
			) throws BusinessException {
#{if}(${operation.asynchronousDestination.isEmpty()})
		// Operation parameters.
		StringBuilder path = new StringBuilder(this.getEndpoint() + (StringUtils.isBlank(${operation.name}Path) ? "" : "/" + ${operation.name}Path) + "?");
		final HttpMethod method = HttpMethod.#{if}(${operation.method.isEmpty()})GET#{else}${operation.method.toUpperCase()}#{end};
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<#{if}(${operation.returnType.equals("void")})?#{else}${operation.returnType}#{end}> returnType =
				new ParameterizedTypeReference<#{if}(${operation.returnType.equals("void")})Void#{else}${operation.returnType}#{end}>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
				#{if}(${operation.mediaType.isEmpty()})MediaType.APPLICATION_JSON_VALUE#{else}"${operation.mediaType.toUpperCase()}"#{end});
#{foreach}( ${parameter} in ${operation.parameters} )
#{if}(${parameter.kind.name().equals("REQUEST_BODY")})
		// Sets the operation body.
		body = ${parameter.originalName};
#{elseif}(${parameter.kind.name().equals("PATH_VARIABLE")})
		// Adds the path parameter to the map.
		path = new StringBuilder(path.toString().replace("{${parameter.originalName}}", Objects.toString(${parameter.originalName})));
#{elseif}(${parameter.kind.name().equals("REQUEST_PARAMETER")})
		// If the parameter is an array.
		if (${parameter.originalName} != null && ${parameter.originalName}.getClass().isArray()) {
			// For each item.
			java.util.List ${parameter.originalName}s = java.util.Arrays.asList(${parameter.originalName});
			for (Integer parameterItemIndex = 0; parameterItemIndex < ${parameter.originalName}s.size(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("${parameter.originalName}" + parameterItemIndex, ${parameter.originalName}s.get(parameterItemIndex));
				path.append("${parameter.name}={${parameter.originalName}" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is a collection.
		else if (${parameter.originalName} != null && java.lang.Iterable.class.isAssignableFrom(${parameter.originalName}.getClass())) {
			// For each item.
			java.util.Iterator ${parameter.originalName}s = ((java.lang.Iterable)(java.lang.Object) ${parameter.originalName}).iterator();
			for (Integer parameterItemIndex = 0; ${parameter.originalName}s.hasNext(); parameterItemIndex++) {
				// Adds the URI parameter to the map.
				uriParameters.put("${parameter.originalName}" + parameterItemIndex, ${parameter.originalName}s.next());
				path.append("${parameter.name}={${parameter.originalName}" + parameterItemIndex + "}&");
			}
		}
		// If the parameter is not a collection nor an array.
		else if (${parameter.originalName} != null) {
			// Adds the URI parameter to the map.
			uriParameters.put("${parameter.originalName}", ${parameter.originalName});
			path.append("${parameter.name}={${parameter.originalName}}&");
		}
#{elseif}(${parameter.kind.name().equals("REQUEST_HEADER")})
		if (${parameter.originalName} != null) {
			// Adds the header to the map.
			GenericRestServiceClient.addHeaders(headers, false, "${parameter.name}", #{if}(
					${parameter.type.endsWith("[]")})Arrays.toString(${parameter.originalName}).split("[\\[\\]]")[1].split(", ")
							#{else}((String[])(java.util.Collection.class.isAssignableFrom(${parameter.originalName}.getClass()) ?
							((java.util.Collection)(java.lang.Object)${parameter.originalName}).stream().map(Objects::toString).toArray() :
							List.of(${parameter.originalName}.toString()).toArray(new String[] {})))#{end});
		}
#{elseif}(${parameter.kind.name().equals("REQUEST_PART")})
		// Adds the part parameter to the map.
		partParameters.put("${parameter.name}",
				(${parameter.originalName} == null ? List.of() : (#{if}(
				${parameter.type.endsWith("[]")})List.of(${parameter.originalName})
						#{else}(java.util.Collection.class.isAssignableFrom(${parameter.originalName}.getClass()) ?
						new ArrayList((java.util.Collection)(java.lang.Object)${parameter.originalName}) :
						List.of(${parameter.originalName}))#{end})));
#{end}
#{end}
		// Executes the operation and returns the response.
		#{if}(!${operation.returnType.equals("void")})return #{end}this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType)#{if}(!${operation.returnType.equals("void")}).getBody()#{end};
				
#{else}
		String syncMethodName = "${operation.name}".replaceAll("Async", "");
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
			jmsTemplate.convertAndSend("${operation.asynchronousDestination}", message.getMessage());
		}
		else {
			jmsTemplateHelper.send(jmsTemplate, message.withDestination("${operation.asynchronousDestination}"));
		}
#{end}
	}
	
#{end}

}