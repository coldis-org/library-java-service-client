package org.coldis.library.service.client;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.web.client.RestOperations;

/**
 * Scope in which a call's requests use a read timeout of its own (a generated
 * client's), instead of the template's, and, optionally, a shorter wait for a
 * pooled connection and for the connection, before the request is sent (the
 * rest of the operation's time budget; see
 * {@link #runWithTimeouts(RestOperations, Duration, Duration, Supplier)}), and a
 * read timeout no longer than a limit (what is left of the request's deadline;
 * see
 * {@link #runWithTimeouts(RestOperations, Duration, Duration, Duration, Supplier)}).
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

	/**
	 * Runs an action whose requests, made through a template, use a read timeout
	 * of their own, no longer than a limit (what is left of the request's
	 * deadline, see {@link ServiceClientRequestDeadline}), and wait for a pooled
	 * connection and for their connection no longer than another one. The default
	 * caps the call's own read timeout to the limit, and ignores the limit for a
	 * call that has none (the template's read timeout is not known here): a scope
	 * that caps the template's too needs a request factory that reads the limit
	 * (as {@link ServiceClientRequestFactory#READ_TIMEOUT_SCOPE} does).
	 *
	 * @param  <ResultType>        Action result type.
	 * @param  template            Template the action's requests go through.
	 * @param  readTimeout         Read timeout ({@code null}, zero or negative
	 *                                 keeps the template's).
	 * @param  readTimeoutLimit    Longest read timeout ({@code null} for none;
	 *                                 zero or negative waits the least possible,
	 *                                 one millisecond).
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
			final Duration readTimeoutLimit,
			final Duration connectionWaitLimit,
			final Supplier<ResultType> action) {
		final boolean shouldCap = (readTimeoutLimit != null) && (readTimeout != null) && readTimeout.isPositive() && (readTimeout.compareTo(readTimeoutLimit) > 0);
		final Duration actualReadTimeout = (shouldCap ? (readTimeoutLimit.toMillis() < 1L ? Duration.ofMillis(1) : readTimeoutLimit) : readTimeout);
		return this.runWithTimeouts(template, actualReadTimeout, connectionWaitLimit, action);
	}

}
