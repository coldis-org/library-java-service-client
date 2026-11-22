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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
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
 * random). Endpoints that just failed to connect or answered a cooldown status
 * are skipped for a cooldown, unless every endpoint is.</li>
 * <li>{@link Strategy#RANDOM}: one endpoint at random, no cooldown.</li>
 * </ul>
 * The cooldown of an endpoint:
 * <ul>
 * <li>Starts at the base cooldown. An endpoint that fails again on a request
 * sent after its cooldown ended (it failed as soon as it came back) gets twice
 * its last cooldown, up to the maximum cooldown; an answer (any status but a
 * cooldown one) ends it, so its next cooldown starts at the base again. A
 * maximum equal to the base keeps every cooldown at the base.</li>
 * <li>Once it ends, a single request at a time (the probe) goes to the
 * endpoint, until it answers: other requests skip it as if it were still
 * cooling down. The probe's answer ends the cooldown; its cooldown failure
 * starts the next (longer) one; any other end (a read timeout, for instance)
 * frees the probe for the next request. A probe out for longer than the
 * cooldown that preceded it no longer holds the endpoint (another request may
 * probe it).</li>
 * <li>Is forgotten once it ended more than the maximum cooldown ago with no
 * outcome (no request reached the endpoint): it then neither holds a probe nor
 * grows the next cooldown.</li>
 * </ul>
 * When every endpoint of a list is skipped (cooling down or probed), all of them
 * are candidates again, so a call always has an endpoint. The balancer also
 * remembers why each cooldown started ({@link CooldownReason}), so the failover
 * executor can make a single attempt when every endpoint of a list is skipped
 * after a connect timeout.
 * <p>
 * State is kept per endpoint string and stays small: idle in-flight counters and
 * forgotten cooldowns are pruned when an endpoint fails (and idle counters when
 * there are more than {@link #MAX_COUNTERS_BEFORE_PRUNING} of them), a cooldown
 * also ends when the endpoint answers. Endpoints that leave a list therefore do
 * not pile up. The hot path takes no lock and allocates nothing of its own: a
 * counter or cooldown is looked up in a {@link ConcurrentHashMap} and changed
 * atomically (a cooldown is allocated when an endpoint fails only).
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
	 * Why an endpoint's cooldown started (its metrics tag value).
	 */
	public enum CooldownReason {

		/**
		 * The connection timed out (each attempt on such an endpoint waits the
		 * whole connect timeout).
		 */
		CONNECT_TIMEOUT("connect-timeout"),

		/**
		 * The connection was refused, or the host had no route or no address (an
		 * attempt on such an endpoint fails fast).
		 */
		NOT_CONNECTED("not-connected"),

		/**
		 * The endpoint answered a cooldown status.
		 */
		STATUS("status");

		/**
		 * Metrics tag value.
		 */
		private final String tagValue;

		/**
		 * Constructor.
		 *
		 * @param tagValue Metrics tag value.
		 */
		CooldownReason(final String tagValue) {
			this.tagValue = tagValue;
		}

		/**
		 * Gets the metrics tag value.
		 *
		 * @return The metrics tag value.
		 */
		public String getTagValue() {
			return this.tagValue;
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
	 * Probe slot value of an endpoint with no probe out.
	 */
	private static final long NO_PROBE = Long.MIN_VALUE;

	/**
	 * The cooldown of an endpoint: when it started and ends, why, and the probe
	 * out once it ended. Immutable but for the probe slot; a new cooldown replaces
	 * it in the map.
	 */
	private static final class Cooldown {

		/**
		 * When the cooldown started ({@link System#nanoTime()}).
		 */
		private final long startNanos;

		/**
		 * When the cooldown ends ({@link System#nanoTime()}).
		 */
		private final long endNanos;

		/**
		 * How long the cooldown lasts (nanoseconds).
		 */
		private final long durationNanos;

		/**
		 * Why the cooldown started.
		 */
		private final CooldownReason reason;

		/**
		 * When the probe out started ({@link System#nanoTime()}), or
		 * {@link ServiceClientEndpointBalancer#NO_PROBE}.
		 */
		private final AtomicLong probeStartNanos = new AtomicLong(ServiceClientEndpointBalancer.NO_PROBE);

		/**
		 * Constructor.
		 *
		 * @param startNanos    When the cooldown starts.
		 * @param durationNanos How long the cooldown lasts (nanoseconds).
		 * @param reason        Why the cooldown started.
		 */
		Cooldown(final long startNanos, final long durationNanos, final CooldownReason reason) {
			this.startNanos = startNanos;
			this.durationNanos = durationNanos;
			this.endNanos = startNanos + durationNanos;
			this.reason = reason;
		}

		/**
		 * Gets if the cooldown has not ended yet.
		 *
		 * @param  now Current {@link System#nanoTime()}.
		 * @return     If the cooldown has not ended yet.
		 */
		boolean isActive(
				final long now) {
			return (this.endNanos - now) > 0;
		}

		/**
		 * Gets if the cooldown is forgotten: it ended at least the forget horizon
		 * ago.
		 *
		 * @param  now           Current {@link System#nanoTime()}.
		 * @param  horizonNanos  Forget horizon (nanoseconds).
		 * @return               If the cooldown is forgotten.
		 */
		boolean isForgotten(
				final long now,
				final long horizonNanos) {
			return (now - this.endNanos) >= horizonNanos;
		}

		/**
		 * Gets if the cooldown ended and its endpoint awaits a probe (an answer).
		 *
		 * @param  now          Current {@link System#nanoTime()}.
		 * @param  horizonNanos Forget horizon (nanoseconds).
		 * @return              If the endpoint awaits a probe.
		 */
		boolean awaitsProbe(
				final long now,
				final long horizonNanos) {
			return !this.isActive(now) && !this.isForgotten(now, horizonNanos);
		}

		/**
		 * Gets if a probe started at a given time still holds the endpoint (it is
		 * out for less than the cooldown).
		 *
		 * @param  probeStart When the probe started (or
		 *                        {@link ServiceClientEndpointBalancer#NO_PROBE}).
		 * @param  now        Current {@link System#nanoTime()}.
		 * @return            If the probe holds the endpoint.
		 */
		boolean isHeldBy(
				final long probeStart,
				final long now) {
			return (probeStart != ServiceClientEndpointBalancer.NO_PROBE) && ((now - probeStart) < this.durationNanos);
		}

		/**
		 * Gets if the endpoint is skipped: cooling down, or awaiting the answer of a
		 * probe out.
		 *
		 * @param  now          Current {@link System#nanoTime()}.
		 * @param  horizonNanos Forget horizon (nanoseconds).
		 * @return              If the endpoint is skipped.
		 */
		boolean isSkipped(
				final long now,
				final long horizonNanos) {
			return this.isActive(now) || (this.awaitsProbe(now, horizonNanos) && this.isHeldBy(this.probeStartNanos.get(), now));
		}

		/**
		 * Takes the probe slot, if free (or held by a probe out for longer than the
		 * cooldown).
		 *
		 * @param  now Current {@link System#nanoTime()} (the probe's start).
		 * @return     If the probe slot was taken.
		 */
		boolean claimProbe(
				final long now) {
			final long probeStart = this.probeStartNanos.get();
			return !this.isHeldBy(probeStart, now) && this.probeStartNanos.compareAndSet(probeStart, now);
		}

		/**
		 * Frees the probe slot, if the given probe still holds it.
		 *
		 * @param probeStart When the probe started.
		 */
		void releaseProbe(
				final long probeStart) {
			this.probeStartNanos.compareAndSet(probeStart, ServiceClientEndpointBalancer.NO_PROBE);
		}

	}

	/**
	 * Balancing strategy.
	 */
	private final Strategy strategy;

	/**
	 * Base cooldown of an endpoint that failed (nanoseconds).
	 */
	private final long failureCooldownNanos;

	/**
	 * Maximum cooldown of an endpoint that keeps failing as soon as it comes back
	 * (nanoseconds; never under the base). Also how long after it ended a
	 * cooldown is remembered.
	 */
	private final long failureCooldownMaxNanos;

	/**
	 * Clock ({@link System#nanoTime()}, or a test's).
	 */
	private final LongSupplier nanoClock;

	/**
	 * Requests in flight per endpoint.
	 */
	private final Map<String, AtomicInteger> inFlightRequests = new ConcurrentHashMap<>();

	/**
	 * Cooldown per endpoint.
	 */
	private final Map<String, Cooldown> cooldowns = new ConcurrentHashMap<>();

	/**
	 * Constructor.
	 *
	 * @param strategy           Balancing strategy.
	 * @param failureCooldown    Base cooldown of an endpoint that failed.
	 * @param failureCooldownMax Maximum cooldown of an endpoint that keeps failing
	 *                               as soon as it comes back (one under the base
	 *                               is taken as the base: no growth).
	 * @param nanoClock          Clock ({@link System#nanoTime()}, or a test's).
	 */
	public ServiceClientEndpointBalancer(
			final Strategy strategy,
			final Duration failureCooldown,
			final Duration failureCooldownMax,
			final LongSupplier nanoClock) {
		super();
		this.strategy = strategy;
		this.failureCooldownNanos = failureCooldown.toNanos();
		this.failureCooldownMaxNanos = Math.max(this.failureCooldownNanos, failureCooldownMax.toNanos());
		this.nanoClock = nanoClock;
	}

	/**
	 * Constructor on {@link System#nanoTime()}.
	 *
	 * @param strategy           Balancing strategy.
	 * @param failureCooldown    Base cooldown of an endpoint that failed.
	 * @param failureCooldownMax Maximum cooldown of an endpoint that keeps failing
	 *                               as soon as it comes back (one under the base
	 *                               is taken as the base: no growth).
	 */
	public ServiceClientEndpointBalancer(final Strategy strategy, final Duration failureCooldown, final Duration failureCooldownMax) {
		this(strategy, failureCooldown, failureCooldownMax, System::nanoTime);
	}

	/**
	 * Constructor with no growth (every cooldown lasts the base cooldown).
	 *
	 * @param strategy        Balancing strategy.
	 * @param failureCooldown How long an endpoint that failed is skipped.
	 */
	public ServiceClientEndpointBalancer(final Strategy strategy, final Duration failureCooldown) {
		this(strategy, failureCooldown, failureCooldown);
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
	 * Gets the cooldown of an endpoint.
	 *
	 * @param  endpoint Endpoint.
	 * @return          The cooldown of the endpoint ({@code null} for none).
	 */
	private Cooldown getCooldown(
			final String endpoint) {
		return (this.cooldowns.isEmpty() ? null : this.cooldowns.get(endpoint));
	}

	/**
	 * Gets if an endpoint is skipped by the choice: cooling down, or awaiting the
	 * answer of a probe out.
	 *
	 * @param  endpoint Endpoint.
	 * @param  now      Current {@link System#nanoTime()}.
	 * @return          If the endpoint is skipped.
	 */
	private boolean isSkipped(
			final String endpoint,
			final long now) {
		final Cooldown cooldown = this.getCooldown(endpoint);
		return (cooldown != null) && cooldown.isSkipped(now, this.failureCooldownMaxNanos);
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
			final long now = this.nanoClock.getAsLong();
			final List<String> availableEndpoints = candidates.stream().filter(endpoint -> !this.isSkipped(endpoint, now)).collect(Collectors.toList());
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
	 * it started on, and frees the endpoint's probe slot if it was its probe.
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
		 * Cooldown whose probe the request is ({@code null} if it is no probe).
		 */
		private final Cooldown probedCooldown;

		/**
		 * If the request already ended.
		 */
		private final AtomicBoolean isClosed = new AtomicBoolean();

		/**
		 * Constructor.
		 *
		 * @param endpoint        Endpoint.
		 * @param endpointCounter Counter of the endpoint.
		 * @param startNanos      When the request started.
		 * @param probedCooldown  Cooldown whose probe the request is ({@code null}
		 *                            if it is no probe).
		 */
		InFlightRequest(final String endpoint, final AtomicInteger endpointCounter, final long startNanos, final Cooldown probedCooldown) {
			this.endpoint = endpoint;
			this.startNanos = startNanos;
			this.endpointCounter = endpointCounter;
			this.probedCooldown = probedCooldown;
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
		 * Gets if the request is the probe of an endpoint whose cooldown ended.
		 *
		 * @return If the request is a probe.
		 */
		public boolean isProbe() {
			return (this.probedCooldown != null);
		}

		/**
		 * Ends the request (and frees the probe slot it holds, if any).
		 *
		 * @see java.lang.AutoCloseable#close()
		 */
		@Override
		public void close() {
			if (this.isClosed.compareAndSet(false, true)) {
				this.endpointCounter.decrementAndGet();
				if (this.probedCooldown != null) {
					this.probedCooldown.releaseProbe(this.startNanos);
				}
			}
		}

	}

	/**
	 * Tries to start a request to an endpoint, as its probe when its cooldown ended
	 * and no probe holds it; when another probe holds it, starts nothing (returns
	 * {@code null}, nothing counted) unless the request may join it.
	 *
	 * @param  endpoint         Endpoint.
	 * @param  mayJoinOtherProbe If the request starts even when another probe
	 *                               holds the endpoint (as an ordinary request).
	 * @return                  The request in flight ({@code null} if another probe
	 *                          holds the endpoint and the request may not join it).
	 */
	private InFlightRequest tryStartRequest(
			final String endpoint,
			final boolean mayJoinOtherProbe) {
		final long now = this.nanoClock.getAsLong();
		final Cooldown cooldown = this.getCooldown(endpoint);
		final boolean awaitsProbe = (cooldown != null) && cooldown.awaitsProbe(now, this.failureCooldownMaxNanos);
		final boolean isProbe = awaitsProbe && cooldown.claimProbe(now);
		InFlightRequest request = null;
		if (!awaitsProbe || isProbe || mayJoinOtherProbe) {
			if (this.inFlightRequests.size() > ServiceClientEndpointBalancer.MAX_COUNTERS_BEFORE_PRUNING) {
				this.pruneIdleEndpoints();
			}
			final AtomicInteger endpointCounter = this.inFlightRequests.computeIfAbsent(endpoint, newEndpoint -> new AtomicInteger());
			endpointCounter.incrementAndGet();
			request = new InFlightRequest(endpoint, endpointCounter, now, (isProbe ? cooldown : null));
			if (isProbe) {
				ServiceClientEndpointBalancer.LOGGER.debug("Endpoint {} probed after its cooldown.", ServiceClientFailoverExecutor.getLoggableEndpoint(endpoint));
			}
		}
		return request;
	}

	/**
	 * Starts a request to an endpoint already chosen (close the returned request,
	 * e.g. with try-with-resources, when it ends). When the endpoint's cooldown
	 * ended and no probe holds it, the request is its probe; when one does, the
	 * request still starts (as an ordinary one), so this method alone does not
	 * keep an endpoint to a single probe: to choose and start a request with that
	 * rule, use {@link #startRequest(List, Collection)}.
	 *
	 * @param  endpoint Endpoint.
	 * @return          The request in flight.
	 */
	public InFlightRequest startRequest(
			final String endpoint) {
		return this.tryStartRequest(endpoint, true);
	}

	/**
	 * Chooses an endpoint (see {@link #choose(List, Collection)}) and starts a
	 * request to it (close the returned request when it ends). When another
	 * request took the chosen endpoint's probe slot between the choice and the
	 * start, the choice is made once more (the endpoint is then skipped, unless
	 * every endpoint is), and that second choice starts in any case.
	 *
	 * @param  endpoints      Endpoints of the list (not empty).
	 * @param  triedEndpoints Endpoints already tried for this operation.
	 * @return                The request in flight.
	 */
	public InFlightRequest startRequest(
			final List<String> endpoints,
			final Collection<String> triedEndpoints) {
		InFlightRequest request = this.tryStartRequest(this.choose(endpoints, triedEndpoints), false);
		if (request == null) {
			request = this.tryStartRequest(this.choose(endpoints, triedEndpoints), true);
		}
		return request;
	}

	/**
	 * Gets the length of the cooldown an endpoint that failed starts.
	 *
	 * @param  previousCooldown  The endpoint's last cooldown ({@code null} for
	 *                               none).
	 * @param  requestStartNanos When the failed request started.
	 * @param  now               Current {@link System#nanoTime()}.
	 * @return                   The base cooldown with no (remembered) last
	 *                           cooldown; twice the last one (up to the maximum)
	 *                           when the request was sent after it ended; the
	 *                           last one's length otherwise (a failure during the
	 *                           cooldown, or of a request sent before it ended).
	 */
	private long getNextCooldownNanos(
			final Cooldown previousCooldown,
			final long requestStartNanos,
			final long now) {
		final long cooldownNanos;
		if ((previousCooldown == null) || previousCooldown.isForgotten(now, this.failureCooldownMaxNanos)) {
			cooldownNanos = this.failureCooldownNanos;
		}
		else if (!previousCooldown.isActive(now) && ((requestStartNanos - previousCooldown.endNanos) >= 0)) {
			cooldownNanos = (previousCooldown.durationNanos > (this.failureCooldownMaxNanos / 2) ? this.failureCooldownMaxNanos : previousCooldown.durationNanos * 2);
		}
		else {
			cooldownNanos = previousCooldown.durationNanos;
		}
		return cooldownNanos;
	}

	/**
	 * Starts (or restarts) an endpoint's cooldown.
	 *
	 * @param endpoint          Endpoint.
	 * @param requestStartNanos When the failed request started.
	 * @param reason            Why the cooldown starts.
	 */
	private void startCooldown(
			final String endpoint,
			final long requestStartNanos,
			final CooldownReason reason) {
		// A blank endpoint is not one instance (the failover executor never passes one).
		if (Strategy.LEAST_IN_FLIGHT.equals(this.strategy) && (this.failureCooldownNanos > 0) && StringUtils.isNotBlank(endpoint)) {
			final long now = this.nanoClock.getAsLong();
			this.pruneIdleEndpoints();
			this.cooldowns.values().removeIf(cooldown -> cooldown.isForgotten(now, this.failureCooldownMaxNanos));
			// Lock-free replacement: retried only if another failure replaced it first.
			Cooldown previousCooldown = null;
			Cooldown nextCooldown = null;
			boolean isStored = false;
			while (!isStored) {
				previousCooldown = this.cooldowns.get(endpoint);
				nextCooldown = new Cooldown(now, this.getNextCooldownNanos(previousCooldown, requestStartNanos, now), reason);
				isStored = (previousCooldown == null ? this.cooldowns.putIfAbsent(endpoint, nextCooldown) == null : this.cooldowns.replace(endpoint, previousCooldown, nextCooldown));
			}
			if ((previousCooldown == null) || !previousCooldown.isActive(now)) {
				ServiceClientEndpointBalancer.LOGGER.info("Endpoint {} skipped for {} ms after a failure ({}).", ServiceClientFailoverExecutor.getLoggableEndpoint(endpoint),
						Duration.ofNanos(nextCooldown.durationNanos).toMillis(), reason.getTagValue());
			}
		}
	}

	/**
	 * Records that a request failed in a way that puts its endpoint in cooldown
	 * (could not connect, or answered a cooldown status): the endpoint is skipped
	 * for the cooldown, longer if the request was sent after its last cooldown
	 * ended (see the class). Idle counters and forgotten cooldowns are pruned.
	 *
	 * @param failedRequest The failed request.
	 * @param reason        Why the cooldown starts.
	 */
	public void startCooldown(
			final InFlightRequest failedRequest,
			final CooldownReason reason) {
		this.startCooldown(failedRequest.getEndpoint(), failedRequest.startNanos, reason);
	}

	/**
	 * Records that an endpoint failed to connect, as a refused connection of a
	 * request sent now: it is skipped for the cooldown. Unlike before the growing
	 * cooldown, a call after the endpoint's cooldown ended (and before it is
	 * forgotten) doubles it, as any failure of a request sent after it ended does;
	 * and as the reason is not a connect timeout, it never makes an operation a
	 * single attempt (use {@link #startCooldown(InFlightRequest, CooldownReason)}
	 * for that). Idle counters and forgotten cooldowns are pruned.
	 *
	 * @param endpoint Endpoint.
	 */
	public void startCooldown(
			final String endpoint) {
		this.startCooldown(endpoint, this.nanoClock.getAsLong(), CooldownReason.NOT_CONNECTED);
	}

	/**
	 * Records that an endpoint answered a request: its cooldown (if any) ends,
	 * and its next one starts at the base, but only if the request was sent after
	 * the cooldown started (an answer to an older request says nothing about the
	 * endpoint since it failed).
	 *
	 * @param answeredRequest The answered request.
	 */
	public void endCooldown(
			final InFlightRequest answeredRequest) {
		final String endpoint = answeredRequest.getEndpoint();
		final Cooldown cooldown = this.getCooldown(endpoint);
		if ((cooldown != null) && ((answeredRequest.startNanos - cooldown.startNanos) > 0) && this.cooldowns.remove(endpoint, cooldown)) {
			ServiceClientEndpointBalancer.LOGGER.info("Endpoint {} answered, no longer skipped.", ServiceClientFailoverExecutor.getLoggableEndpoint(endpoint));
		}
	}

	/**
	 * Gets if every endpoint of a list is skipped (cooling down, or awaiting the
	 * answer of a probe out) after a connect timeout. Looks up each endpoint
	 * once, allocates nothing, and returns at once while no endpoint has a
	 * cooldown.
	 *
	 * @param  endpoints Endpoints of the list.
	 * @return           If the list is not empty and every endpoint of it is
	 *                   skipped after a connect timeout.
	 */
	public boolean isEverySkippedAfterConnectTimeout(
			final List<String> endpoints) {
		boolean isEverySkipped = !this.cooldowns.isEmpty() && !endpoints.isEmpty();
		final long now = (isEverySkipped ? this.nanoClock.getAsLong() : 0);
		for (int endpointIndex = 0; isEverySkipped && (endpointIndex < endpoints.size()); endpointIndex++) {
			final Cooldown cooldown = this.cooldowns.get(endpoints.get(endpointIndex));
			isEverySkipped = (cooldown != null) && (cooldown.reason == CooldownReason.CONNECT_TIMEOUT) && cooldown.isSkipped(now, this.failureCooldownMaxNanos);
		}
		return isEverySkipped;
	}

	/**
	 * Gets if an endpoint is in its failure cooldown now (not counting the wait
	 * for a probe's answer once it ended).
	 *
	 * @param  endpoint Endpoint.
	 * @return          If the endpoint is in its failure cooldown now.
	 */
	public boolean isCoolingDown(
			final String endpoint) {
		final Cooldown cooldown = this.getCooldown(endpoint);
		return (cooldown != null) && cooldown.isActive(this.nanoClock.getAsLong());
	}

	/**
	 * Gets if an endpoint is skipped by the choice now: in its failure cooldown,
	 * or awaiting the answer of the probe out after it.
	 *
	 * @param  endpoint Endpoint.
	 * @return          If the endpoint is skipped now.
	 */
	public boolean isSkipped(
			final String endpoint) {
		return this.isSkipped(endpoint, this.nanoClock.getAsLong());
	}

	/**
	 * Gets the length of an endpoint's current (or last remembered) cooldown.
	 *
	 * @param  endpoint Endpoint.
	 * @return          The length of the cooldown ({@code null} for none).
	 */
	public Duration getCooldownDuration(
			final String endpoint) {
		final Cooldown cooldown = this.getCooldown(endpoint);
		return (cooldown == null ? null : Duration.ofNanos(cooldown.durationNanos));
	}

	/**
	 * Gets the base cooldown of an endpoint that failed.
	 *
	 * @return The base cooldown of an endpoint that failed.
	 */
	public Duration getFailureCooldown() {
		return Duration.ofNanos(this.failureCooldownNanos);
	}

	/**
	 * Gets the maximum cooldown of an endpoint that keeps failing as soon as it
	 * comes back.
	 *
	 * @return The maximum cooldown.
	 */
	public Duration getFailureCooldownMax() {
		return Duration.ofNanos(this.failureCooldownMaxNanos);
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
	 * Gets the number of endpoints with a cooldown (ended ones included until
	 * forgotten and pruned).
	 *
	 * @return The number of endpoints with a cooldown.
	 */
	public int getCooldownEndpointsCount() {
		return this.cooldowns.size();
	}

	/**
	 * Forgets every request counter and cooldown (for instance after the endpoint
	 * lists change). Requests in flight finish on the counters they started on.
	 */
	public void clear() {
		this.inFlightRequests.clear();
		this.cooldowns.clear();
	}

}
