package org.coldis.library.service.client;

import java.time.Duration;
import java.util.Objects;

import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

/**
 * Keep-alive strategy of the service client: how long a pooled connection may
 * stay idle before it is no longer reused. The server's {@code Keep-Alive}
 * header, when it says less, wins; otherwise (no header, or a longer one) the
 * maximum applies, instead of the HTTP client's default of three minutes. A
 * maximum below the server's own idle timeout keeps the client from reusing a
 * connection the server is closing.
 */
public class ServiceClientKeepAliveStrategy implements ConnectionKeepAliveStrategy {

	/**
	 * Maximum keep-alive.
	 */
	private final TimeValue maxKeepAlive;

	/**
	 * Constructor.
	 *
	 * @param maxKeepAlive Maximum keep-alive (not negative).
	 */
	public ServiceClientKeepAliveStrategy(final Duration maxKeepAlive) {
		super();
		if (Objects.requireNonNull(maxKeepAlive, "maxKeepAlive").isNegative()) {
			throw new IllegalArgumentException("The maximum keep-alive must not be negative.");
		}
		this.maxKeepAlive = TimeValue.ofMilliseconds(maxKeepAlive.toMillis());
	}

	/**
	 * Gets the maximum keep-alive.
	 *
	 * @return The maximum keep-alive.
	 */
	public TimeValue getMaxKeepAlive() {
		return this.maxKeepAlive;
	}

	/**
	 * Gets the keep-alive of a connection: the server's, if not longer than the
	 * maximum, or the maximum.
	 *
	 * @see ConnectionKeepAliveStrategy#getKeepAliveDuration(HttpResponse,
	 *      HttpContext)
	 */
	@Override
	public TimeValue getKeepAliveDuration(
			final HttpResponse response,
			final HttpContext context) {
		final TimeValue serverKeepAlive = DefaultConnectionKeepAliveStrategy.INSTANCE.getKeepAliveDuration(response, context);
		return (TimeValue.isNonNegative(serverKeepAlive) && (serverKeepAlive.compareTo(this.maxKeepAlive) < 0) ? serverKeepAlive : this.maxKeepAlive);
	}

}
