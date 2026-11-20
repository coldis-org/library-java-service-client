package org.coldis.library.service.client;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * Metrics of the service client failover and balancing, recorded in a meter
 * registry. Every meter is tagged with the target only (and the outcome or
 * reason), never with an endpoint, so their number stays bounded:
 * <ul>
 * <li>{@value #ATTEMPTS} (counter; tags {@code target}, {@code outcome}).</li>
 * <li>{@value #RETRIES} (counter; tags {@code target}, {@code reason}).</li>
 * <li>{@value #COOLDOWNS} (counter; tag {@code target}).</li>
 * <li>{@value #BUDGET_SPENT} (counter; tags {@code target}, {@code reason}).</li>
 * <li>{@value #LAST_ENDPOINTS_CALLS} (counter; tag {@code target}).</li>
 * <li>{@value #IN_FLIGHT} (gauge; tag {@code target}).</li>
 * </ul>
 */
public class MicrometerServiceClientMetrics implements ServiceClientMetrics {

	/**
	 * Attempts counter.
	 */
	public static final String ATTEMPTS = "service.client.attempts";

	/**
	 * Retries counter.
	 */
	public static final String RETRIES = "service.client.retries";

	/**
	 * Cooldowns counter.
	 */
	public static final String COOLDOWNS = "service.client.cooldowns";

	/**
	 * Counter of operations whose further attempts were stopped because their total
	 * time limit (budget) was spent.
	 */
	public static final String BUDGET_SPENT = "service.client.attempts.stopped.by.time.limit";

	/**
	 * Counter of calls that went to their target's previous (last non-empty)
	 * endpoint list, as its current one was empty.
	 */
	public static final String LAST_ENDPOINTS_CALLS = "service.client.calls.on.previous.endpoints";

	/**
	 * Requests in flight gauge.
	 */
	public static final String IN_FLIGHT = "service.client.in.flight";

	/**
	 * Meter registry.
	 */
	private final MeterRegistry meterRegistry;

	/**
	 * Requests in flight per target.
	 */
	private final Map<String, AtomicInteger> inFlightRequests = new ConcurrentHashMap<>();

	/**
	 * Constructor.
	 *
	 * @param meterRegistry Meter registry.
	 */
	public MicrometerServiceClientMetrics(final MeterRegistry meterRegistry) {
		super();
		this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
	}

	/**
	 * Gets the requests in flight of a target (its gauge is registered with it).
	 *
	 * @param  target Target.
	 * @return        The requests in flight of the target.
	 */
	private AtomicInteger getInFlightRequests(
			final String target) {
		return this.inFlightRequests.computeIfAbsent(target,
				newTarget -> this.meterRegistry.gauge(MicrometerServiceClientMetrics.IN_FLIGHT, Tags.of("target", newTarget), new AtomicInteger()));
	}

	/**
	 * @see ServiceClientMetrics#recordAttempt(String, String)
	 */
	@Override
	public void recordAttempt(
			final String target,
			final String outcome) {
		this.meterRegistry.counter(MicrometerServiceClientMetrics.ATTEMPTS, "target", target, "outcome", outcome).increment();
	}

	/**
	 * @see ServiceClientMetrics#recordRetry(String, String)
	 */
	@Override
	public void recordRetry(
			final String target,
			final String reason) {
		this.meterRegistry.counter(MicrometerServiceClientMetrics.RETRIES, "target", target, "reason", reason).increment();
	}

	/**
	 * @see ServiceClientMetrics#recordBudgetSpent(String, String)
	 */
	@Override
	public void recordBudgetSpent(
			final String target,
			final String reason) {
		this.meterRegistry.counter(MicrometerServiceClientMetrics.BUDGET_SPENT, "target", target, "reason", reason).increment();
	}

	/**
	 * @see ServiceClientMetrics#recordLastEndpointsCall(String)
	 */
	@Override
	public void recordLastEndpointsCall(
			final String target) {
		this.meterRegistry.counter(MicrometerServiceClientMetrics.LAST_ENDPOINTS_CALLS, "target", target).increment();
	}

	/**
	 * @see ServiceClientMetrics#recordCooldown(String)
	 */
	@Override
	public void recordCooldown(
			final String target) {
		this.meterRegistry.counter(MicrometerServiceClientMetrics.COOLDOWNS, "target", target).increment();
	}

	/**
	 * @see ServiceClientMetrics#recordRequestStarted(String)
	 */
	@Override
	public void recordRequestStarted(
			final String target) {
		this.getInFlightRequests(target).incrementAndGet();
	}

	/**
	 * @see ServiceClientMetrics#recordRequestEnded(String)
	 */
	@Override
	public void recordRequestEnded(
			final String target) {
		this.getInFlightRequests(target).decrementAndGet();
	}

}
