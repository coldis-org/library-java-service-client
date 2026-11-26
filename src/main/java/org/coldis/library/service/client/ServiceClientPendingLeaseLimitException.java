package org.coldis.library.service.client;

import java.io.IOException;

/**
 * A request refused at once, with nothing sent, because too many requests were
 * already waiting for a pooled connection to its endpoint (see
 * {@link ServiceClientConnectionManager}). It reaches the caller as the cause
 * of the HTTP client's failure (within a {@code ResourceAccessException} from a
 * rest template). The failover executor retries it on the next endpoint (for
 * any method: nothing was sent), puts no endpoint in cooldown (the queue is
 * local) and counts it as an overload outcome of the concurrency limit.
 */
public class ServiceClientPendingLeaseLimitException extends IOException {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 8153061922435717930L;

	/**
	 * Constructor.
	 *
	 * @param message Message.
	 */
	public ServiceClientPendingLeaseLimitException(final String message) {
		super(message);
	}

}
