package org.coldis.library.service.client.generator;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Service client metadata.
 */
@Documented
@Target(TYPE)
@Retention(RUNTIME)
public @interface ServiceClient {

	/**
	 * Context is used to identify clients and methods that should be bound
	 * together.
	 */
	public String context() default "";

	/**
	 * Resources path. Default is "src/main/resources".
	 */
	public String resourcesPath() default "src/main/resources/";

	/**
	 * Template relative path (from resources).
	 */
	public String templatePath() default "service/client/template/JavaServiceClient.java";

	/**
	 * The service client file extension.
	 */
	public String fileExtension() default "java";

	/**
	 * Service client namespace.
	 */
	public String namespace();

	/**
	 * Service client super class
	 */
	public String superclass() default "";

	/**
	 * Service client name. Default is the origin class name with the "Client"
	 * append.
	 */
	public String name() default "";

	/**
	 * Service client endpoint.
	 */
	public String endpoint() default "";

}
