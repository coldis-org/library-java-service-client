package org.coldis.library.service.client;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;

/**
 * How the generated clients apply the deadline of the request the current thread
 * serves ({@link ServiceClientRequestDeadline}): if they do (a runtime switch,
 * on by default through the auto-configuration), the margin kept from it, and the read timeout that
 * bounds the time they send on when a call has none of its own. The
 * {@link ServiceClientFailoverExecutor} asks it what is left of the deadline
 * before each attempt, and for the attempt's request with the header.
 * <p>
 * The header is taken from any caller (an accepted decision): a caller can
 * shorten or skip its own request's downstream calls, which matters only where
 * a check lets a request through when its downstream call fails. Once the
 * original caller has given up, a chain of writes can stop partway (accepted).
 */
public class ServiceClientRequestDeadlinePolicy {

	/**
	 * Margin kept from the request's deadline (for the answer to travel back), when
	 * none is given.
	 */
	public static final Duration DEFAULT_MARGIN = Duration.ofMillis(100);

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientRequestDeadlinePolicy.class);

	/**
	 * If the request's deadline is applied to the calls (read once per attempt; no
	 * lock).
	 */
	private volatile boolean isEnabled;

	/**
	 * Margin kept from the request's deadline.
	 */
	private final Duration margin;

	/**
	 * Read timeout of a call that has none of its own (the default template's;
	 * {@code null} if not known).
	 */
	private final Duration defaultReadTimeout;

	/**
	 * Constructor.
	 *
	 * @param isEnabled          If the request's deadline is applied to the calls.
	 * @param margin             Margin kept from the request's deadline
	 *                               ({@code null} or negative for none).
	 * @param defaultReadTimeout Read timeout of a call that has none of its own
	 *                               (the default template's; {@code null}, zero
	 *                               or negative if not known).
	 */
	public ServiceClientRequestDeadlinePolicy(final boolean isEnabled, final Duration margin, final Duration defaultReadTimeout) {
		super();
		this.isEnabled = isEnabled;
		this.margin = ((margin == null) || margin.isNegative() ? Duration.ZERO : margin);
		this.defaultReadTimeout = (ServiceClientRequestFactory.hasOwnReadTimeout(defaultReadTimeout) ? defaultReadTimeout : null);
	}

	/**
	 * Creates a policy that is off (it can be turned on at runtime), with the
	 * default margin and no known default read timeout.
	 *
	 * @return The policy.
	 */
	public static ServiceClientRequestDeadlinePolicy createDisabled() {
		return new ServiceClientRequestDeadlinePolicy(false, ServiceClientRequestDeadlinePolicy.DEFAULT_MARGIN, null);
	}

	/**
	 * Gets if the request's deadline is applied to the calls.
	 *
	 * @return If the request's deadline is applied to the calls.
	 */
	public boolean isEnabled() {
		return this.isEnabled;
	}

	/**
	 * Turns the request's deadline on or off for the calls that start from now on
	 * (what {@code org.coldis.library.service-client.deadline.enabled} sets at
	 * start).
	 *
	 * @param isEnabled If the request's deadline is applied to the calls.
	 */
	public void setEnabled(
			final boolean isEnabled) {
		this.isEnabled = isEnabled;
	}

	/**
	 * Gets the margin kept from the request's deadline.
	 *
	 * @return The margin kept from the request's deadline.
	 */
	public Duration getMargin() {
		return this.margin;
	}

	/**
	 * Gets the read timeout of a call that has none of its own.
	 *
	 * @return The read timeout of a call that has none of its own ({@code null}
	 *         if not known).
	 */
	public Duration getDefaultReadTimeout() {
		return this.defaultReadTimeout;
	}

	/**
	 * Gets what is left of the deadline of the request the current thread serves,
	 * less the margin. Never fails: a deadline that cannot be read is logged and
	 * taken as none, so the call goes on as it would without one.
	 *
	 * @return What is left of the deadline ({@code null} with no deadline, or with
	 *         the policy off; zero or negative when it is spent).
	 */
	public Duration getRemainingTime() {
		Duration remainingTime = null;
		if (this.isEnabled) {
			try {
				final Long deadlineNanos = ServiceClientRequestDeadline.getDeadlineNanos();
				if (deadlineNanos != null) {
					remainingTime = Duration.ofNanos(ServiceClientRequestDeadline.getRemainingNanos(deadlineNanos)).minus(this.margin);
				}
			}
			catch (final RuntimeException exception) {
				ServiceClientRequestDeadlinePolicy.LOGGER.warn("Request deadline not read ({}): the call goes on without it.", exception.getClass().getSimpleName());
				ServiceClientRequestDeadlinePolicy.LOGGER.debug("Request deadline not read.", exception);
			}
		}
		return remainingTime;
	}

	/**
	 * Gets the time sent on with an attempt: what is left of the deadline, bounded
	 * by the attempt's read timeout (the call's own, or else the default one).
	 *
	 * @param  readTimeout   The call's own read timeout ({@code null}, zero or
	 *                           negative for none).
	 * @param  remainingTime What is left of the deadline.
	 * @return               The time sent on.
	 */
	public Duration getTimeSent(
			final Duration readTimeout,
			final Duration remainingTime) {
		final Duration attemptReadTimeout = (ServiceClientRequestFactory.hasOwnReadTimeout(readTimeout) ? readTimeout : this.defaultReadTimeout);
		return ((attemptReadTimeout != null) && (attemptReadTimeout.compareTo(remainingTime) < 0) ? attemptReadTimeout : remainingTime);
	}

	/**
	 * Gets the request of an attempt with the time sent on in the
	 * {@value ServiceClientRequestDeadline#HEADER} header (replacing any such
	 * header the request has; its other headers and body are kept). Never fails:
	 * on any error, the request is sent as is (logged).
	 *
	 * @param  requestEntity Request headers and body.
	 * @param  readTimeout   The call's own read timeout ({@code null}, zero or
	 *                           negative for none).
	 * @param  remainingTime What is left of the deadline.
	 * @return               The request of the attempt.
	 */
	public HttpEntity<?> withHeader(
			final HttpEntity<?> requestEntity,
			final Duration readTimeout,
			final Duration remainingTime) {
		HttpEntity<?> attemptEntity = requestEntity;
		try {
			final HttpHeaders headers = new HttpHeaders();
			headers.putAll(requestEntity.getHeaders());
			headers.set(ServiceClientRequestDeadline.HEADER, Long.toString(Math.max(1L, this.getTimeSent(readTimeout, remainingTime).toMillis())));
			attemptEntity = new HttpEntity<>(requestEntity.getBody(), headers);
		}
		catch (final RuntimeException exception) {
			ServiceClientRequestDeadlinePolicy.LOGGER.warn("Request deadline header not added ({}): the request goes without it.",
					exception.getClass().getSimpleName());
			ServiceClientRequestDeadlinePolicy.LOGGER.debug("Request deadline header not added.", exception);
		}
		return attemptEntity;
	}

}
