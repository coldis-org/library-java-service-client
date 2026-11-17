package org.coldis.library.service.client;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the endpoint (instance) a service client operation is sent to, among
 * the endpoints of its list.
 * <ul>
 * <li>{@link Strategy#LEAST_IN_FLIGHT} (power of two choices): two endpoints at
 * random, the one with fewer requests in flight from this instance wins (ties at
 * random). Endpoints that just failed to connect or answered a retry status are
 * skipped for a cooldown, unless every endpoint is.</li>
 * <li>{@link Strategy#RANDOM}: one endpoint at random, no cooldown.</li>
 * </ul>
 * State is kept per endpoint string and stays small: idle in-flight counters and
 * expired cooldowns are pruned when an endpoint fails (and when there are more
 * than {@link #MAX_COUNTERS_BEFORE_PRUNING} counters), a cooldown also ends when the
 * endpoint answers. Endpoints that leave a list therefore do not pile up. The hot
 * path takes no lock: a counter is looked up in a {@link ConcurrentHashMap} and
 * changed atomically.
 */
public class ServiceClientEndpointBalancer {

	/**
	 * Balancing strategy.
	 */
	public enum Strategy {

		/**
		 * Uniform random choice (no cooldown).
		 */
		RANDOM,

		/**
		 * Power of two choices on requests in flight, with a cooldown for failed
		 * endpoints.
		 */
		LEAST_IN_FLIGHT;

		/**
		 * Gets the strategy from its property value ({@code random},
		 * {@code least-in-flight}).
		 *
		 * @param  value Property value.
		 * @return       The strategy.
		 */
		public static Strategy fromPropertyValue(
				final String value) {
			return Strategy.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
		}

	}

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientEndpointBalancer.class);

	/**
	 * Number of counters above which idle ones are pruned on a request.
	 */
	private static final int MAX_COUNTERS_BEFORE_PRUNING = 256;

	/**
	 * Balancing strategy.
	 */
	private final Strategy strategy;

	/**
	 * How long an endpoint that failed is skipped (nanoseconds).
	 */
	private final long failureCooldownNanos;

	/**
	 * Requests in flight per endpoint.
	 */
	private final Map<String, AtomicInteger> inFlightRequests = new ConcurrentHashMap<>();

	/**
	 * End of the cooldown per endpoint ({@link System#nanoTime()}).
	 */
	private final Map<String, Long> cooldownEnds = new ConcurrentHashMap<>();

	/**
	 * Default constructor.
	 *
	 * @param strategy        Balancing strategy.
	 * @param failureCooldown How long an endpoint that failed is skipped.
	 */
	public ServiceClientEndpointBalancer(final Strategy strategy, final Duration failureCooldown) {
		super();
		this.strategy = strategy;
		this.failureCooldownNanos = failureCooldown.toNanos();
	}

	/**
	 * Gets the balancing strategy.
	 *
	 * @return The balancing strategy.
	 */
	public Strategy getStrategy() {
		return this.strategy;
	}

	/**
	 * Gets the requests in flight to an endpoint.
	 *
	 * @param  endpoint Endpoint.
	 * @return          The requests in flight to the endpoint.
	 */
	public int getInFlightRequests(
			final String endpoint) {
		final AtomicInteger inFlight = this.inFlightRequests.get(endpoint);
		return (inFlight == null ? 0 : inFlight.get());
	}

	/**
	 * Gets if an endpoint is in its failure cooldown.
	 *
	 * @param  endpoint Endpoint.
	 * @param  now      Current {@link System#nanoTime()}.
	 * @return          If the endpoint is in its failure cooldown.
	 */
	private boolean isCoolingDown(
			final String endpoint,
			final long now) {
		final Long cooldownEnd = this.cooldownEnds.get(endpoint);
		return (cooldownEnd != null) && ((cooldownEnd - now) > 0);
	}

	/**
	 * Chooses an endpoint among the candidates that were not tried yet.
	 *
	 * @param  endpoints      Endpoints of the list (not empty).
	 * @param  triedEndpoints Endpoints already tried for this operation.
	 * @return                The chosen endpoint (among all the endpoints if every
	 *                        one was tried).
	 */
	public String choose(
			final List<String> endpoints,
			final Collection<String> triedEndpoints) {
		final List<String> untriedEndpoints = endpoints.stream().filter(endpoint -> !triedEndpoints.contains(endpoint)).collect(Collectors.toList());
		final List<String> candidates = (untriedEndpoints.isEmpty() ? endpoints : untriedEndpoints);
		final ThreadLocalRandom random = ThreadLocalRandom.current();
		String chosenEndpoint = null;
		if (Strategy.RANDOM.equals(this.strategy) || (candidates.size() == 1)) {
			chosenEndpoint = candidates.get(random.nextInt(candidates.size()));
		}
		else {
			final long now = System.nanoTime();
			final List<String> availableEndpoints = candidates.stream().filter(endpoint -> !this.isCoolingDown(endpoint, now)).collect(Collectors.toList());
			final List<String> choices = (availableEndpoints.isEmpty() ? candidates : availableEndpoints);
			if (choices.size() == 1) {
				chosenEndpoint = choices.get(0);
			}
			else {
				final int firstIndex = random.nextInt(choices.size());
				final int secondIndex = (firstIndex + 1 + random.nextInt(choices.size() - 1)) % choices.size();
				final String firstEndpoint = choices.get(firstIndex);
				final String secondEndpoint = choices.get(secondIndex);
				final int firstInFlight = this.getInFlightRequests(firstEndpoint);
				final int secondInFlight = this.getInFlightRequests(secondEndpoint);
				chosenEndpoint = (firstInFlight < secondInFlight ? firstEndpoint
						: secondInFlight < firstInFlight ? secondEndpoint : (random.nextBoolean() ? firstEndpoint : secondEndpoint));
			}
		}
		return chosenEndpoint;
	}

	/**
	 * Removes the counters of endpoints with no request in flight. A request that
	 * gets its counter just before the counter is removed (between the look-up and
	 * the increment) is counted on the removed counter and ends on it (see
	 * {@link InFlightRequest#close()}): it is only missed by the choice while it is
	 * in flight. Pruning runs on every failure, so this gap is widest during
	 * failure bursts; it costs balancing accuracy only, never a lost count.
	 */
	private void pruneIdleEndpoints() {
		this.inFlightRequests.values().removeIf(endpointCounter -> endpointCounter.get() <= 0);
	}

	/**
	 * A request in flight to an endpoint: closing it (once) ends it, on the counter
	 * it started on.
	 */
	public static final class InFlightRequest implements AutoCloseable {

		/**
		 * Endpoint.
		 */
		private final String endpoint;

		/**
		 * When the request started ({@link System#nanoTime()}).
		 */
		private final long startNanos;

		/**
		 * Counter of the endpoint.
		 */
		private final AtomicInteger endpointCounter;

		/**
		 * If the request already ended.
		 */
		private final AtomicBoolean isClosed = new AtomicBoolean();

		/**
		 * Constructor.
		 *
		 * @param endpoint        Endpoint.
		 * @param endpointCounter Counter of the endpoint.
		 */
		InFlightRequest(final String endpoint, final AtomicInteger endpointCounter) {
			this.endpoint = endpoint;
			this.startNanos = System.nanoTime();
			this.endpointCounter = endpointCounter;
		}

		/**
		 * Gets the endpoint.
		 *
		 * @return The endpoint.
		 */
		public String getEndpoint() {
			return this.endpoint;
		}

		/**
		 * Ends the request.
		 *
		 * @see java.lang.AutoCloseable#close()
		 */
		@Override
		public void close() {
			if (this.isClosed.compareAndSet(false, true)) {
				this.endpointCounter.decrementAndGet();
			}
		}

	}

	/**
	 * Starts a request to an endpoint (close the returned request, e.g. with
	 * try-with-resources, when it ends).
	 *
	 * @param  endpoint Endpoint.
	 * @return          The request in flight.
	 */
	public InFlightRequest startRequest(
			final String endpoint) {
		if (this.inFlightRequests.size() > ServiceClientEndpointBalancer.MAX_COUNTERS_BEFORE_PRUNING) {
			this.pruneIdleEndpoints();
		}
		final AtomicInteger endpointCounter = this.inFlightRequests.computeIfAbsent(endpoint, newEndpoint -> new AtomicInteger());
		endpointCounter.incrementAndGet();
		return new InFlightRequest(endpoint, endpointCounter);
	}

	/**
	 * Records that an endpoint failed (could not connect, or answered a retry
	 * status): it is skipped for the cooldown. Idle counters and expired cooldowns
	 * are pruned.
	 *
	 * @param endpoint Endpoint.
	 */
	public void startCooldown(
			final String endpoint) {
		// A blank endpoint (no list: the path is the whole URL) is not one instance.
		if (Strategy.LEAST_IN_FLIGHT.equals(this.strategy) && (this.failureCooldownNanos > 0) && StringUtils.isNotBlank(endpoint)) {
			final long now = System.nanoTime();
			this.pruneIdleEndpoints();
			this.cooldownEnds.values().removeIf(cooldownEnd -> (cooldownEnd - now) <= 0);
			if (this.cooldownEnds.put(endpoint, now + this.failureCooldownNanos) == null) {
				ServiceClientEndpointBalancer.LOGGER.info("Endpoint {} skipped for {} ms after a failure.", ServiceClientFailoverExecutor.getLoggableEndpoint(endpoint),
						Duration.ofNanos(this.failureCooldownNanos).toMillis());
			}
		}
	}

	/**
	 * Records that an endpoint answered a request: its cooldown (if any) ends, but
	 * only if the request was sent after the cooldown started (an answer to an
	 * older request says nothing about the endpoint since it failed).
	 *
	 * @param answeredRequest The answered request.
	 */
	public void endCooldown(
			final InFlightRequest answeredRequest) {
		final String endpoint = answeredRequest.getEndpoint();
		final Long cooldownEnd = (this.cooldownEnds.isEmpty() ? null : this.cooldownEnds.get(endpoint));
		if ((cooldownEnd != null) && ((answeredRequest.startNanos - (cooldownEnd - this.failureCooldownNanos)) > 0)
				&& this.cooldownEnds.remove(endpoint, cooldownEnd)) {
			ServiceClientEndpointBalancer.LOGGER.info("Endpoint {} answered, no longer skipped.", ServiceClientFailoverExecutor.getLoggableEndpoint(endpoint));
		}
	}

	/**
	 * Gets if an endpoint is in its failure cooldown now.
	 *
	 * @param  endpoint Endpoint.
	 * @return          If the endpoint is in its failure cooldown now.
	 */
	public boolean isCoolingDown(
			final String endpoint) {
		return this.isCoolingDown(endpoint, System.nanoTime());
	}

	/**
	 * Gets how long an endpoint that failed is skipped.
	 *
	 * @return How long an endpoint that failed is skipped.
	 */
	public Duration getFailureCooldown() {
		return Duration.ofNanos(this.failureCooldownNanos);
	}


	/**
	 * Gets the number of endpoints with a request counter (kept small by the
	 * pruning).
	 *
	 * @return The number of endpoints with a request counter.
	 */
	public int getCountedEndpointsCount() {
		return this.inFlightRequests.size();
	}

	/**
	 * Gets the number of endpoints with a cooldown (expired ones included until
	 * pruned).
	 *
	 * @return The number of endpoints with a cooldown.
	 */
	public int getCooldownEndpointsCount() {
		return this.cooldownEnds.size();
	}

	/**
	 * Forgets every request counter and cooldown (for instance after the endpoint
	 * lists change). Requests in flight finish on the counters they started on.
	 */
	public void clear() {
		this.inFlightRequests.clear();
		this.cooldownEnds.clear();
	}

}
