package org.coldis.library.service.client.generator;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Service client operations metadata.
 */
@Documented
@Target(METHOD)
@Retention(RUNTIME)
public @interface ServiceClientOperations {

	/**
	 * Service client operations to be generated.
	 */
	public ServiceClientOperation[] operations();

}
