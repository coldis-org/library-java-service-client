package org.coldis.library.service.client.generator;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Service client operation parameter metadata.
 */
@Documented
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface ServiceClientOperationParameter {

	/**
	 * Service client operation parameter type.
	 */
	public String type() default "";

	/**
	 * Service client operation parameter name.
	 */
	public String name() default "";

	/**
	 * Service client operation parameter kind.
	 */
	public String kind() default "";

}
