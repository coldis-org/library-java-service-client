package org.coldis.library.service.client;

import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.collections4.EnumerationUtils;
import org.apache.commons.lang3.StringUtils;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Generic RESTful service client.
 */
public class GenericRestServiceClient {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(GenericRestServiceClient.class);

	/**
	 * REST operations template.
	 */
	@Autowired
	private RestOperations restTemplate;

	/**
	 * Object mapper to be used on REST operations.
	 */
	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * Gets the REST operations template.
	 *
	 * @return The REST operations template.
	 */
	public RestOperations getRestTemplate() {
		return this.restTemplate;
	}

	/**
	 * Sets the REST operations template.
	 *
	 * @param restTemplate New REST operations template.
	 */
	public void setRestTemplate(final RestOperations restTemplate) {
		this.restTemplate = restTemplate;
	}

	/**
	 * Gets the objectMapper.
	 *
	 * @return The objectMapper.
	 */
	protected ObjectMapper getObjectMapper() {
		return this.objectMapper;
	}

	/**
	 * Sets the objectMapper.
	 *
	 * @param newObjectMapper New objectMapper.
	 */
	protected void setObjectMapper(final ObjectMapper newObjectMapper) {
		this.objectMapper = newObjectMapper;
	}

	/**
	 * Adds another headers entry to an existing one.
	 *
	 * @param  headers    Existent headers.
	 * @param  replaceKey If existent keys should be replaced in the given headers.
	 * @param  key        The headers key.
	 * @param  values     The headers values.
	 * @return            The updated headers.
	 */
	public static MultiValueMap<String, String> addHeaders(final MultiValueMap<String, String> headers,
			final Boolean replaceKey, final String key, final String... values) {
		// Headers are created if none are given.
		final MultiValueMap<String, String> actualHeaders = headers == null ? new LinkedMultiValueMap<>() : headers;
		// If the key should be actually replaced.
		if (replaceKey) {
			// Removes any existing keys from the headers.
			actualHeaders.remove(key);
		}
		// For each headers value.
		if (values != null) {
			for (final String value : values) {
				// Adds the headers entry.
				actualHeaders.add(key, value);
			}
		}
		// Returns the updated.
		return actualHeaders;
	}

	/**
	 * Adds any type of plain authorization headers.
	 *
	 * @param  headers   Existent headers.
	 * @param  authType  Authentication type (Basic, Bearer...).
	 * @param  authValue Authentication value (token...).
	 * @return           The updated headers.
	 */
	public static MultiValueMap<String, String> addPlainAuthorizationBasicHeaders(
			final MultiValueMap<String, String> headers, final String authType, final String authValue) {
		return GenericRestServiceClient.addHeaders(headers, true, HttpHeaders.AUTHORIZATION,
				authType + " " + authValue);
	}

	/**
	 * Adds basic authorization headers.
	 *
	 * @param  headers  Existent headers.
	 * @param  username User name.
	 * @param  password user password.
	 * @return          The updated headers.
	 */
	public static MultiValueMap<String, String> addBasicAuthorizationHeaders(
			final MultiValueMap<String, String> headers, final String username, final String password) {
		return GenericRestServiceClient.addPlainAuthorizationBasicHeaders(headers, "Basic",
				Base64.getEncoder().encodeToString(new String(username + ":" + password).getBytes()));
	}

	/**
	 * Adds content type headers.
	 *
	 * @param  headers      Existent headers.
	 * @param  contentTypes Headers content types to be added.
	 * @return              The updated headers.
	 */
	public static MultiValueMap<String, String> addContentTypeHeaders(final MultiValueMap<String, String> headers,
			final String... contentTypes) {
		return GenericRestServiceClient.addHeaders(headers, false, HttpHeaders.CONTENT_TYPE, contentTypes);
	}

	/**
	 * Adds the original HTTP request headers for a given entry (if any) to the
	 * existent headers.
	 *
	 * @param  headers Existent headers.
	 * @param  key     Headers entry keys to be added.
	 * @return         The updated headers.
	 */
	public static MultiValueMap<String, String> addOriginalRequestHeaders(final MultiValueMap<String, String> headers,
			final String key) {
		// Tries to get the original request attributes.
		final ServletRequestAttributes requestAttrs = (ServletRequestAttributes) RequestContextHolder
				.getRequestAttributes();
		// If request attributes are available.
		if ((requestAttrs != null) && (requestAttrs.getRequest() != null)) {
			// Gets the headers from the original request.
			final Enumeration<String> values = requestAttrs.getRequest().getHeaders(key);
			// Copies the original headers to the existent ones.
			GenericRestServiceClient.addHeaders(headers, false, key,
					EnumerationUtils.toList(values).toArray(new String[0]));
		}
		// Returns the updated (or not) headers.
		return headers;
	}

	/**
	 * Extension point to modify headers before a REST operation call. No
	 * modification is made by default.
	 *
	 * @param  headers Current headers.
	 * @return         Returns the updated headers.
	 */
	protected MultiValueMap<String, String> autoModifyHeaders(final MultiValueMap<String, String> headers) {
		return headers;
	}

	/**
	 * Extension point to handle REST operation execution exceptions. No handling is
	 * done by default.
	 *
	 * @param  <ResponseType>     REST operation response type.
	 * @param  path               REST operation path.
	 * @param  method             REST operation method.
	 * @param  headers            REST operation headers.
	 * @param  body               REST operation body.
	 * @param  responseType       REST operation response type.
	 * @param  operationException REST operation exception.
	 * @return                    The REST operation response (if nicely handled).
	 * @throws Exception          If the operation exception could not be handled.
	 *
	 */
	protected <ResponseType> ResponseEntity<ResponseType> autoHandleExecutionExceptions(final String path,
			final HttpMethod method, final MultiValueMap<String, String> headers, final Object body,
			final ParameterizedTypeReference<ResponseType> responseType, final Exception operationException)
					throws Exception {
		throw operationException;
	}

	/**
	 * Executes a REST operation.
	 *
	 * @param  <ResponseType>       REST operation response type.
	 * @param  path                 REST operation path.
	 * @param  method               REST operation method.
	 * @param  headers              REST operation headers.
	 * @param  body                 REST operation body.
	 * @param  uriParameters        REST operation URI parameters.
	 * @param  responseType         REST operation response type.
	 * @return                      The REST operation response.
	 *
	 * @throws IntegrationException If an unexpected exception is raised.
	 * @throws BusinessException    If a business exception is raised.
	 */
	public <ResponseType> ResponseEntity<ResponseType> executeOperation(final String path, final HttpMethod method,
			final MultiValueMap<String, String> headers, final Object body, final Map<String, Object> uriParameters,
			final ParameterizedTypeReference<ResponseType> responseType)
					throws IntegrationException, BusinessException {
		// Modifies the headers as for service client implementation.
		final MultiValueMap<String, String> actualHeaders = this.autoModifyHeaders(headers);
		// Makes sure parameters are not null.
		final Map<String, Object> actualUriParameters = (uriParameters == null ? new HashMap<>() : uriParameters);
		// Tries to execute the REST operation.
		try {
			// Creates a new HTTP entity for the current headers and body.
			final HttpEntity<Object> httpEntity = new HttpEntity<>(body, actualHeaders);
			// Executes the REST operation and returns the response.
			return this.getRestTemplate().exchange(path, method, httpEntity, responseType, actualUriParameters);
		}
		// If the REST operation raises an exception.
		catch (final Exception originalException) {
			// Actual REST operation exception is the current one.
			Exception actualException = originalException;
			// Tries to handle the operation exception.
			try {
				return this.autoHandleExecutionExceptions(path, method, actualHeaders, body, responseType,
						actualException);
			}
			// If the REST operation exception cannot be handled.
			catch (final Exception newException) {
				// Actual REST operation exception is the new one.
				actualException = newException;
			}
			// Logs the exception.
			GenericRestServiceClient.LOGGER.error("REST operation execution failed.", actualException);
			// If the exception is a HTTP exception.
			if (actualException instanceof HttpStatusCodeException) {
				// Gets the HTTP exception and its response.
				final HttpStatusCodeException httpException = ((HttpStatusCodeException) actualException);
				final String exceptionResponse = httpException.getResponseBodyAsString();
				// Exception messages.
				SimpleMessage[] exceptionMessages = null;
				// If there is an exception response.
				if (!StringUtils.isEmpty(exceptionResponse)) {
					// Tries to get the exception messages.
					exceptionMessages = ObjectMapperHelper.deserialize(this.objectMapper, exceptionResponse,
							SimpleMessage[].class, true);
				}
				// If no messages are available.
				if ((exceptionMessages == null) || (exceptionMessages.length == 0)) {
					// Creates a default message.
					exceptionMessages = new SimpleMessage[] { new SimpleMessage("rest.operation.execution.error") };
				}
				// If the exception status code is for bad request.
				if (HttpStatus.BAD_REQUEST.equals(httpException.getStatusCode())) {
					// Throws a business exception with the exception messages.
					throw new BusinessException(Arrays.asList(exceptionMessages), httpException);
				}
				// If the exception status code is not for bad request.
				else {
					// Throws an integration exception with the exception messages.
					throw new IntegrationException(
							new SimpleMessage(exceptionMessages[0].getCode(), exceptionMessages[0].getContent()),
							httpException.getStatusCode().value(), httpException);
				}
			}
			// For every other exception.
			else {
				// Throws an integration exception with a generic exception message.
				throw new IntegrationException(new SimpleMessage("rest.operation.execution.error"), actualException);
			}
		}
	}

}
