package org.coldis.library.service.client.generator;

/**
 * If a service client operation is idempotent (may be sent again to another
 * endpoint after the request may have reached the first one).
 */
public enum ServiceClientOperationIdempotency {

	/**
	 * Decided by the HTTP method (property
	 * {@code org.coldis.library.service-client.failover.idempotent-methods}).
	 */
	BY_METHOD,

	/**
	 * Idempotent.
	 */
	IDEMPOTENT,

	/**
	 * Not idempotent.
	 */
	NOT_IDEMPOTENT

}
