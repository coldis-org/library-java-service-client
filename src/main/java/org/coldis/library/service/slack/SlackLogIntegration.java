package org.coldis.library.service.slack;

import java.util.Map;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Slack integration service.
 */
@Component
public class SlackLogIntegration {

	/**
	 * Slack URL.
	 */
	@Value("${org.coldis.library.service-client.slack.endpoint}")
	private String slackUrl;

	/**
	 * Service client.
	 */
	@Autowired
	private GenericRestServiceClient restServiceClient;

	/**
	 * Sends a message to Slack.
	 * 
	 * @param  hookContext       Web-hook context.
	 * @param  message           Message.
	 *
	 * @return                   The message submission response.
	 * @throws BusinessException If the message cannot be send.
	 */
	public ResponseEntity<String> send(
			final String hookContext,
			final String message) throws BusinessException {
		return this.restServiceClient.executeOperation(this.slackUrl + hookContext, HttpMethod.POST, null, Map.of("text", message), null,
				new ParameterizedTypeReference<String>() {});
	}

}
