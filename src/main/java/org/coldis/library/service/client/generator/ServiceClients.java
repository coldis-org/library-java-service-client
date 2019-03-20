package org.coldis.library.service.client.generator;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Service clients metadata.
 */
@Documented
@Target(TYPE)
@Retention(RUNTIME)
public @interface ServiceClients {

	/**
	 * Service clients to be generated.
	 */
	public ServiceClient[] types();

}
