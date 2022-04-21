package ${serviceClient.namespace};

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
#{if}(!${serviceClient.jmsListenerQualifier.isEmpty()})
	@Qualifier(value = "${serviceClient.jmsListenerQualifier}")
#{end}
	private JmsTemplate jmsTemplate;
	
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
	 * @see org.springframework.context.EmbeddedValueResolverAware#
	 *      setEmbeddedValueResolver(org.springframework.util.StringValueResolver)
	 */
	@Override
	public void setEmbeddedValueResolver(final StringValueResolver resolver) {
		valueResolver = resolver;
	}
	private StringBuilder preparePath(String uri) {
		return new StringBuilder(this.valueResolver.resolveStringValue("${serviceClient.endpoint}/" + uri + "?"));
	}
#{foreach}( ${operation} in ${serviceClient.operations} )
	/**
	 *${operation.docComment}  */
	public ${operation.returnType} ${operation.name}(
			#{set}($currentItemIdx = 0)#{foreach}( ${parameter} in ${operation.parameters} )#{if}(${currentItemIdx} > 0),
			#{end}#{set}($currentItemIdx = $currentItemIdx + 1)${parameter.type} ${parameter.originalName}#{end}) throws BusinessException {
#{if}(${operation.asynchronousDestination.isEmpty()})
		// Operation parameters.
		StringBuilder path = preparePath("${operation.path}");
		final HttpMethod method = HttpMethod.#{if}(${operation.method.isEmpty()})GET#{else}${operation.method.toUpperCase()}#{end};
		Object body = null;
		final Map<String, Object> uriParameters = new HashMap<>();
		final MultiValueMap<String, Object> partParameters = new LinkedMultiValueMap<>();
		final ParameterizedTypeReference<#{if}(${operation.returnType.equals("void")})?#{else}${operation.returnType}#{end}> returnType =
				new ParameterizedTypeReference<#{if}(${operation.returnType.equals("void")})Void#{else}${operation.returnType}#{end}>() {};
		// Adds the content type headers.
		final MultiValueMap<String, String> headers = GenericRestServiceClient.createDefaultHeader(#{if}(${operation.mediaType.isEmpty()})MediaType.APPLICATION_JSON_VALUE#{else}"${operation.mediaType.toUpperCase()}"#{end});
#{foreach}( ${parameter} in ${operation.parameters} )
#{if}(${parameter.kind.name().equals("REQUEST_BODY")})
		// Sets the operation body.
		body = ${parameter.originalName};
#{elseif}(${parameter.kind.name().equals("PATH_VARIABLE")})
		// Adds the path parameter to the map.
		path = new StringBuilder(path.toString().replace("{${parameter.originalName}}", Objects.toString(${parameter.originalName})));
#{elseif}(${parameter.kind.name().equals("REQUEST_PARAMETER")})
		GenericRestServiceClient.appendUriParameters(uriParameters, "${parameter.originalName}", ${parameter.originalName});
#{elseif}(${parameter.kind.name().equals("REQUEST_HEADER")})
		// Adds the header to the map.
		GenericRestServiceClient.addHeaders(headers, false, "${parameter.name}", #{if}(
				${parameter.type.endsWith("[]")})List.of(${parameter.originalName}).toArray(new String[] {})
						#{else}(${parameter.originalName} == null ? new String[] {} :
						List.of(${parameter.originalName}.toString()).toArray(new String[] {}))#{end});
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
		path.append(UrlHelper.convertToQueryParameters(uriParameters.entrySet()));
		// Executes the operation and returns the response.
		#{if}(!${operation.returnType.equals("void")})return #{end}this.serviceClient.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType)#{if}(!${operation.returnType.equals("void")}).getBody()#{end};

#{else}
		jmsTemplate.convertAndSend("${operation.asynchronousDestination}",
#{if}(${operation.parameters.size()} > 1)
				Map.of(
					#{foreach}( ${parameter} in ${operation.parameters} )
											#{set}($currentItemIdx = 0)"${parameter.originalName}", ${parameter.originalName}#{if}(${currentItemIdx} > 0),
											#{end}#{set}($currentItemIdx = $currentItemIdx + 1)
					#{end}
										)
#{else}
				${operation.parameters.get(0).originalName}
#{end}
			);
#{end}
	}

#{end}

}