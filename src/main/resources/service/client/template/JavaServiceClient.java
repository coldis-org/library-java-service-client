package ${serviceClient.namespace};

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
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
public class ${serviceClient.name}#{if}(!${serviceClient.superclass.isEmpty()}) extends ${serviceClient.superclass}#{end} implements EmbeddedValueResolverAware {
	
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
	#{if}(!${serviceClient.serviceClientQualifier.isEmpty()})@Qualifier(value = "${serviceClient.serviceClientQualifier}")#{end}
	private GenericRestServiceClient serviceClient;

	/**
	 * No arguments constructor.
	 */
	public ${serviceClient.name}() {
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

#{foreach}( ${operation} in ${serviceClient.operations} )
	/**
	 *${operation.docComment}  */
	public ${operation.returnType} ${operation.name}(
			#{set}($currentItemIdx = 0)#{foreach}( ${parameter} in ${operation.parameters} )#{if}(${currentItemIdx} > 0),
			#{end}#{set}($currentItemIdx = $currentItemIdx + 1)${parameter.type} ${parameter.originalName}#{end}) throws BusinessException {
		// Operation parameters.
		StringBuilder path = new StringBuilder(this.valueResolver
				.resolveStringValue("${serviceClient.endpoint}/${operation.path}?"));
		final HttpMethod method = HttpMethod.#{if}(${operation.method.isEmpty()})GET#{else}${operation.method.toUpperCase()}#{end};
		final MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<#{if}(${operation.returnType.equals("void")})?#{else}${operation.returnType}#{end}> returnType =
				new ParameterizedTypeReference<#{if}(${operation.returnType.equals("void")})Void#{else}${operation.returnType}#{end}>() {};
		// Adds the content type headers.
		GenericRestServiceClient.addContentTypeHeaders(headers,
				#{if}(${operation.mediaType.isEmpty()})MediaType.APPLICATION_JSON_UTF8_VALUE#{else}"${operation.mediaType.toUpperCase()}"#{end});
#{foreach}( ${parameter} in ${operation.parameters} )
#{if}(${parameter.kind.name().equals("REQUEST_BODY")})
		// Sets the operation body.
		body = ${parameter.originalName};
#{elseif}(${parameter.kind.name().equals("PATH_VARIABLE")})
		// Adds the path parameter to the map.
		path = new StringBuilder(path.toString().replace("{${parameter.originalName}}", Objects.toString(${parameter.originalName})));
#{elseif}(${parameter.kind.name().equals("REQUEST_PARAMETER")})
		// Adds the URI parameter to the map.
		uriParameters.put("${parameter.originalName}", ${parameter.originalName});
		path.append("${parameter.name}={${parameter.originalName}}&");
#{elseif}(${parameter.kind.name().equals("REQUEST_HEADER")})
		// Adds the header to the map.
		GenericRestServiceClient.addHeaders(headers, false, "${parameter.name}", #{if}(
				${parameter.type.endsWith("[]")})List.of(${parameter.originalName}).toArray(new String[] {})
						#{else}(${parameter.originalName} == null ? 
						List.of(${parameter.originalName}).toArray(new String[] {}) : 
						(String[])(java.util.Collection.class.isAssignableFrom(${parameter.originalName}.getClass()) ?
						((java.util.Collection)(java.lang.Object)${parameter.originalName}).toArray(new String[] {}) :
						List.of(${parameter.originalName}.toString()).toArray(new String[] {})))#{end});
#{elseif}(${parameter.kind.name().equals("REQUEST_PART")})
		// Adds the part parameter to the map.
		partParameters.put("${parameter.name}",
				(${parameter.originalName} == null ? List.of() : (#{if}(
				${parameter.type.endsWith("[]")})List.of(${parameter.originalName})
						#{else}(java.util.Collection.class.isAssignableFrom(${parameter.originalName}.getClass()) ?
						new ArrayList((java.util.Collection)${parameter.originalName}) :
						List.of(${parameter.originalName}))#{end})));
#{end}
#{end}
		// Executes the operation and returns the response.
		#{if}(!${operation.returnType.equals("void")})return #{end}this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType)#{if}(!${operation.returnType.equals("void")}).getBody()#{end};
	}
	
#{if}(${operation.asynchronous})
	/**
	 * ${operation.name} queue.
	 */
	public static final String ${operation.name}Queue = "${operation.name}Queue.queue";
	
	/**
	 *${operation.docComment}  */
	@Transactional
	@JmsListener(destination = "${operation.name}Queue.queue")
	public void ${operation.name}(Map<String, ?> parameters) throws BusinessException {
		${operation.name}(
#{foreach}( ${parameter} in ${operation.parameters} )#{set}($currentItemIdx = 0)
				(${parameter.type}) parameters.get("${parameter.originalName}")#{if}(${currentItemIdx} > 0), #{end}#{set}($currentItemIdx = $currentItemIdx + 1)
#{end}
			);
	}

	
	/**
	 *${operation.docComment}  */
	@Transactional
	public void ${operation.name}Async(
			#{set}($currentItemIdx = 0)#{foreach}( ${parameter} in ${operation.parameters} )#{if}(${currentItemIdx} > 0),
			#{end}#{set}($currentItemIdx = $currentItemIdx + 1)${parameter.type} ${parameter.originalName}#{end}) throws BusinessException {
		jmsTemplate.convertAndSend(${operation.name}Queue, 
				Map.of(
#{foreach}( ${parameter} in ${operation.parameters} )
						#{set}($currentItemIdx = 0)"${parameter.originalName}", ${parameter.originalName}#{if}(${currentItemIdx} > 0),
						#{end}#{set}($currentItemIdx = $currentItemIdx + 1)
#{end}
					));
	}
#{end}
#{end}

}