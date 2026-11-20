package org.coldis.library.service.client;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.web.client.RestOperations;

/**
 * Scope in which a call's requests use a read timeout of its own (a generated
 * client's), instead of the template's, and, optionally, a shorter wait for a
 * pooled connection and for the connection, before the request is sent (the
 * rest of the operation's time budget; see
 * {@link #runWithTimeouts(RestOperations, Duration, Duration, Supplier)}).
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

	/**
	 * Runs an action whose requests, made through a template, use a read timeout
	 * of their own and wait for a pooled connection and for their connection no
	 * longer than a limit (what is left of the operation's time budget). The
	 * default ignores the limit: a scope that applies it needs a request factory
	 * that reads it (as {@link ServiceClientRequestFactory#READ_TIMEOUT_SCOPE}
	 * does).
	 *
	 * @param  <ResultType>        Action result type.
	 * @param  template            Template the action's requests go through.
	 * @param  readTimeout         Read timeout ({@code null}, zero or negative
	 *                                 keeps the template's).
	 * @param  connectionWaitLimit Longest wait for a pooled connection and for the
	 *                                 connection ({@code null} keeps the
	 *                                 template's; a shorter one of the template
	 *                                 wins).
	 * @param  action              Action.
	 * @return                     The action result.
	 */
	default <ResultType> ResultType runWithTimeouts(
			final RestOperations template,
			final Duration readTimeout,
			final Duration connectionWaitLimit,
			final Supplier<ResultType> action) {
		return this.runWithReadTimeout(template, readTimeout, action);
	}

	/**
	 * Gets if the connection wait limit cut the connect timeout of the last
	 * request the current thread made in
	 * {@link #runWithTimeouts(RestOperations, Duration, Duration, Supplier)} (the
	 * default, which applies no limit, never does). A connect timeout of such a
	 * request says nothing about the endpoint.
	 *
	 * @return If the limit cut the last request's connect timeout.
	 */
	default boolean isLastConnectTimeoutCut() {
		return false;
	}

	/**
	 * Gets if the scope applies the connection wait limit of
	 * {@link #runWithTimeouts(RestOperations, Duration, Duration, Supplier)} (the
	 * default does not). A scope that overrides that method to apply the limit
	 * overrides this one too, so the time budget is known to cap each attempt.
	 *
	 * @return If the scope applies the connection wait limit.
	 */
	default boolean appliesConnectionWaitLimit() {
		return false;
	}

}
