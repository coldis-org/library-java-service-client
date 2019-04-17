package ${serviceClient.namespace};

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
  *${serviceClient.docComment}  */
@Service
public class ${serviceClient.name}#{if}(!${serviceClient.superclass.isEmpty()}) extends ${serviceClient.superclass}#{end} {

	/**
	 * No arguments constructor.
	 */
	public ${serviceClient.name}() {
		super();
	}

#{foreach}( ${operation} in ${serviceClient.operations} )
	/**
	 *${operation.docComment}  */
	public ${operation.returnType} ${operation.name}(
			#{set}($currentItemIdx = 0)#{foreach}( ${parameter} in ${operation.parameters} )#{if}(${currentItemIdx} > 0),
			#{end}#{set}($currentItemIdx = $currentItemIdx + 1)${parameter.type} ${parameter.name}#{end}) throws BusinessException, IntegrationException {
		// Operation parameters.
		StringBuilder path = new StringBuilder("${serviceClient.endpoint}/${operation.path}?");
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
#{if}(${parameter.kind.toLowerCase().equals("body")})
		// Sets the operation body.
		body = ${parameter.name};
#{elseif}(${parameter.kind.toLowerCase().equals("pathparameter")})
		// Adds the path parameter to the map.
		path = new StringBuilder(path.toString().replace("{${parameter.name}}", Objects.toString(${parameter.name})));
#{elseif}(${parameter.kind.toLowerCase().equals("uriparameter")})
		// Adds the URI parameter to the map.
		uriParameters.put("${parameter.name}", ${parameter.name});
		path.append("${parameter.name}={${parameter.name}}&");
#{elseif}(${parameter.kind.toLowerCase().equals("header")})
		// Adds the header to the map.
		GenericRestServiceClient.addHeaders(headers, false, "${parameter.name}", #{if}(${Collection.class.isAssignableFrom(
				${Class.forName(${parameter.type})})})new ArrayList<>(${parameter.name})#{elseif}(
				${parameter.type.endsWith("[]")})List.of(${parameter.name}).toArray(new String[] {})#{else}${parameter.name} == null ? null : ${parameter.name}.toString()#{end});
#{elseif}(${parameter.kind.toLowerCase().equals("partparameter")})
		// Adds the part parameter to the map.
		partParameters.put("${parameter.name}",
				#{if}(${Collection.class.isAssignableFrom(
				${Class.forName(${parameter.type})})})new ArrayList<>(${parameter.name})#{elseif}(
				${parameter.type.endsWith("[]")})List.of(${parameter.name})#{else}List.of(${parameter.name})#{end});
#{end}
#{end}
		// Executes the operation and returns the response.
		#{if}(!${operation.returnType.equals("void")})return #{end}this.executeOperation(path.toString(), method, headers,
				partParameters.isEmpty() ? body : partParameters,
				uriParameters, returnType)#{if}(!${operation.returnType.equals("void")}).getBody()#{end};
	}
	
#{end}

}