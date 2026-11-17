package org.coldis.library.service.client;

import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

/**
 * Target of the service client calls the current thread makes: the name of the
 * generated client making them ({@code @ServiceClient(name)}, or the origin
 * class name with {@code Client} appended), or {@link #NONE} outside a generated
 * client's call (full-URL calls, third-party clients). Metrics are tagged with
 * it, so they can be grouped by downstream service with a bounded number of
 * values. Any other request the thread makes during the call (by an interceptor
 * or a message converter, for instance) gets the same target.
 */
public final class ServiceClientTarget {

	/**
	 * Target outside a generated client's call.
	 */
	public static final String NONE = "none";

	/**
	 * Target of the current thread ({@code null} for none).
	 */
	private static final ThreadLocal<String> CURRENT_TARGET = new ThreadLocal<>();

	/**
	 * No instances.
	 */
	private ServiceClientTarget() {
		super();
	}

	/**
	 * Gets the target of the current thread.
	 *
	 * @return The target of the current thread ({@link #NONE} outside a generated
	 *         client's call).
	 */
	public static String getCurrent() {
		final String currentTarget = ServiceClientTarget.CURRENT_TARGET.get();
		return (currentTarget == null ? ServiceClientTarget.NONE : currentTarget);
	}

	/**
	 * Runs an action with a target for the calls the current thread makes while it
	 * runs.
	 *
	 * @param  <ResultType> Action result type.
	 * @param  target       Target ({@code null} or blank for {@link #NONE}).
	 * @param  action       Action.
	 * @return              The action result.
	 */
	public static <ResultType> ResultType runFor(
			final String target,
			final Supplier<ResultType> action) {
		final String previousTarget = ServiceClientTarget.CURRENT_TARGET.get();
		ServiceClientTarget.CURRENT_TARGET.set(StringUtils.isBlank(target) ? null : target);
		try {
			return action.get();
		}
		finally {
			if (previousTarget == null) {
				ServiceClientTarget.CURRENT_TARGET.remove();
			}
			else {
				ServiceClientTarget.CURRENT_TARGET.set(previousTarget);
			}
		}
	}

}
