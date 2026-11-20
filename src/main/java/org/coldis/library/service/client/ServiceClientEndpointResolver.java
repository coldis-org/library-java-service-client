package org.coldis.library.service.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the endpoints of a call of a target (a generated client) from its
 * endpoint list, which is read again on every call and may change while the
 * service runs (it is pushed by service discovery). An empty list does not
 * replace the last non-empty one seen for the target: the call goes to that
 * last good list, for at most a maximum age, then fails at once with no request
 * (as it does when the target never had a list), so emptying a list still stops
 * the calls after that age. The transitions are logged once, and the calls on a
 * last list are counted in the {@link ServiceClientMetrics}.
 */
public class ServiceClientEndpointResolver {

	/**
	 * Code of the failure of a call with no endpoint to go to.
	 */
	public static final String NO_ENDPOINTS_CODE = "service.client.endpoints.none";

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientEndpointResolver.class);

	/**
	 * Resolver that keeps no last list (a call with an empty list fails at once):
	 * for the executor used when none is configured, so it holds no state.
	 */
	public static final ServiceClientEndpointResolver NO_MEMORY = new ServiceClientEndpointResolver(Duration.ZERO, ServiceClientMetrics.NONE) {

		/**
		 * @see ServiceClientEndpointResolver#resolve(String, List)
		 */
		@Override
		public List<String> resolve(
				final String target,
				final List<String> endpoints) throws IntegrationException {
			final List<String> availableEndpoints = ServiceClientEndpointResolver.getAvailableEndpoints(endpoints);
			if (availableEndpoints.isEmpty()) {
				throw ServiceClientEndpointResolver.createNoEndpointsException(target, "");
			}
			return availableEndpoints;
		}

	};

	/**
	 * Last non-empty endpoint list of a target.
	 *
	 * @param endpoints Endpoints (not empty).
	 * @param seenNanos When the list was last seen non-empty
	 *                      ({@link System#nanoTime()}).
	 */
	private record LastEndpoints(
			List<String> endpoints,
			long seenNanos) {}

	/**
	 * How long a last list stands in for an empty one (zero or negative for no
	 * limit).
	 */
	private final Duration lastEndpointsMaxAge;

	/**
	 * Metrics.
	 */
	private final ServiceClientMetrics metrics;

	/**
	 * Last non-empty endpoint list of each target.
	 */
	private final Map<String, LastEndpoints> lastEndpoints = new ConcurrentHashMap<>();

	/**
	 * Targets whose current endpoint list is empty, so their calls go to their last
	 * non-empty one (each is warned about once, when it gets there).
	 */
	private final Set<String> targetsOnLastEndpoints = ConcurrentHashMap.newKeySet();

	/**
	 * Constructor.
	 *
	 * @param lastEndpointsMaxAge How long a last list stands in for an empty one
	 *                                ({@code null}, zero or negative for no
	 *                                limit).
	 * @param metrics             Metrics.
	 */
	public ServiceClientEndpointResolver(final Duration lastEndpointsMaxAge, final ServiceClientMetrics metrics) {
		super();
		this.lastEndpointsMaxAge = (lastEndpointsMaxAge == null ? Duration.ZERO : lastEndpointsMaxAge);
		this.metrics = Objects.requireNonNull(metrics, "metrics");
	}

	/**
	 * Gets how long a last list stands in for an empty one.
	 *
	 * @return How long a last list stands in for an empty one (zero or negative
	 *         for no limit).
	 */
	public Duration getLastEndpointsMaxAge() {
		return this.lastEndpointsMaxAge;
	}

	/**
	 * Gets the endpoints of a list: trimmed, without blank or repeated ones (empty
	 * when none is left).
	 *
	 * @param  endpoints Endpoints of the list ({@code null} for none).
	 * @return           The endpoints of the list.
	 */
	public static List<String> getAvailableEndpoints(
			final List<String> endpoints) {
		return (endpoints == null ? List.of()
				: endpoints.stream().filter(Objects::nonNull).map(String::trim).filter(StringUtils::isNotBlank).distinct().toList());
	}

	/**
	 * Creates the failure of a call with no endpoint to go to.
	 *
	 * @param  target Target of the call.
	 * @param  reason Reason appended to the message (empty for none).
	 * @return        The failure.
	 */
	private static IntegrationException createNoEndpointsException(
			final String target,
			final String reason) {
		final String targetName = (StringUtils.isBlank(target) || ServiceClientTarget.NONE.equals(target) ? "a call with no target client" : target);
		return new IntegrationException(new SimpleMessage(ServiceClientEndpointResolver.NO_ENDPOINTS_CODE, "No endpoints configured for " + targetName + reason + "."));
	}

	/**
	 * Forgets every last list (and the targets on one).
	 */
	public void clear() {
		this.lastEndpoints.clear();
		this.targetsOnLastEndpoints.clear();
	}

	/**
	 * Gets the endpoints of a call of a target: those of its list (see
	 * {@link #getAvailableEndpoints(List)}), kept as the target's last good list;
	 * or, when none is left, the target's last good list, if it is not older than
	 * the maximum age (warned once, when the list gets empty). A call with no
	 * target ({@link ServiceClientTarget#NONE}) has no last good list.
	 *
	 * @param  target               Target of the call.
	 * @param  endpoints            Endpoints of the list ({@code null} for none).
	 * @return                      The endpoints of the call (never empty).
	 * @throws IntegrationException If the list is empty and the target has no last
	 *                                  good list, or one older than the maximum
	 *                                  age.
	 */
	public List<String> resolve(
			final String target,
			final List<String> endpoints) throws IntegrationException {
		final boolean hasTarget = StringUtils.isNotBlank(target) && !ServiceClientTarget.NONE.equals(target);
		final List<String> availableEndpoints = ServiceClientEndpointResolver.getAvailableEndpoints(endpoints);
		final List<String> resolvedEndpoints;
		if (!availableEndpoints.isEmpty()) {
			resolvedEndpoints = availableEndpoints;
			if (hasTarget) {
				this.lastEndpoints.put(target, new LastEndpoints(availableEndpoints, System.nanoTime()));
				if (this.targetsOnLastEndpoints.remove(target)) {
					ServiceClientEndpointResolver.LOGGER.info("Endpoint list of {} is back ({} endpoints).", target, availableEndpoints.size());
				}
			}
		}
		else {
			final LastEndpoints last = (hasTarget ? this.lastEndpoints.get(target) : null);
			if (last == null) {
				throw ServiceClientEndpointResolver.createNoEndpointsException(target, "");
			}
			if (this.lastEndpointsMaxAge.isPositive() && ((System.nanoTime() - last.seenNanos()) > this.lastEndpointsMaxAge.toNanos())) {
				throw ServiceClientEndpointResolver.createNoEndpointsException(target,
						" (its last list is older than " + this.lastEndpointsMaxAge.toSeconds() + "s)");
			}
			resolvedEndpoints = last.endpoints();
			if (this.targetsOnLastEndpoints.add(target)) {
				ServiceClientEndpointResolver.LOGGER.warn(
						"Endpoint list of {} is empty: its calls go to its last non-empty list ({} endpoints) until it is back, for at most {}.", target,
						resolvedEndpoints.size(), this.lastEndpointsMaxAge.isPositive() ? this.lastEndpointsMaxAge.toSeconds() + "s" : "no limit");
			}
			try {
				this.metrics.recordLastEndpointsCall(target);
			}
			catch (final RuntimeException exception) {
				ServiceClientEndpointResolver.LOGGER.debug("Service client metrics not recorded.", exception);
			}
		}
		return resolvedEndpoints;
	}

}
