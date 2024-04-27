package org.coldis.library.service.client.generator;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.springframework.cache.annotation.Cacheable;

/**
 * Service client operation metadata.
 */
@Documented
@Target(METHOD)
@Retention(RUNTIME)
public @interface ServiceClientOperation {

	/**
	 * Context is used to identify clients and methods that should be bound
	 * together.
	 */
	public String context() default "";

	/**
	 * If operation should be ignored in the service client.
	 */
	public boolean ignore() default false;

	/**
	 * Service client operation name.
	 */
	public String name() default "";

	/**
	 * Service client operation path.
	 */
	public String path() default "";

	/**
	 * Service client operation method.
	 */
	public String method() default "";

	/**
	 * Service client operation media type.
	 */
	public String mediaType() default "";

	/**
	 * Service client operation return type.
	 */
	public Class<?> returnType() default void.class;

	/**
	 * Service client operation return type.
	 */
	public String returnTypeName() default "";

	/**
	 * Asynchronous destination that should be used to call the service.
	 */
	public String asynchronousDestination() default "";

	/**
	 * Annotations to be copied.
	 */
	public Class<?>[] copiedAnnotations() default {

			// Deprecation.
			Deprecated.class,
			// Cache.
			Cacheable.class

	};

}
