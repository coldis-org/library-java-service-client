package org.coldis.library.service.client;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.web.client.RestOperations;

/**
 * Scope in which a call's requests use a read timeout of its own (a generated
 * client's), instead of the template's.
 */
@FunctionalInterface
public interface ServiceClientReadTimeoutScope {

	/**
	 * Runs an action whose requests, made through a template, use a read timeout
	 * of their own.
	 *
	 * @param  <ResultType> Action result type.
	 * @param  template     Template the action's requests go through.
	 * @param  readTimeout  Read timeout ({@code null}, zero or negative keeps the
	 *                          template's).
	 * @param  action       Action.
	 * @return              The action result.
	 */
	<ResultType> ResultType runWithReadTimeout(
			RestOperations template,
			Duration readTimeout,
			Supplier<ResultType> action);

}
