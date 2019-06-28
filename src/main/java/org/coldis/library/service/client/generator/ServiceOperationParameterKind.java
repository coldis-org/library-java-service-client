package org.coldis.library.service.client.generator;

/**
 * Service operation parameter kind.
 */
public enum ServiceOperationParameterKind {

	/**
	 * Path variable.
	 */
	PATH_VARIABLE,

	/**
	 * Request parameter.
	 */
	REQUEST_PARAMETER,

	/**
	 * Request part.
	 */
	REQUEST_PART,

	/**
	 * Request header.
	 */
	REQUEST_HEADER,

	/**
	 * Request body.
	 */
	REQUEST_BODY,

	/**
	 * Inherited.
	 */
	INHERITED,

	/**
	 * Ignored.
	 */
	IGNORED;

}
