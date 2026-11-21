package org.coldis.library.service.client;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
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
 * <li>{@value #ATTEMPTS_STOPPED_BY_TIME_LIMIT} (counter; tags {@code target}, {@code reason}).</li>
 * <li>{@value #CALLS_ON_LAST_ENDPOINTS} (counter; tag {@code target}).</li>
 * <li>{@value #DEADLINE_EXPIRED} (counter; tags {@code target},
 * {@code stage}).</li>
 * <li>{@value #IN_FLIGHT} (gauge; tag {@code target}).</li>
 * </ul>
 * The concurrency limit of each target (see
 * {@link ServiceClientConcurrencyLimiter}), read at scrape time, tag
 * {@code target} (and {@code result}):
 * <ul>
 * <li>{@value #LIMIT} (gauge), {@value #LIMIT_IN_FLIGHT} (gauge),
 * {@value #LIMIT_IN_FLIGHT_PEAK} (gauge, last 5 minutes).</li>
 * <li>{@value #LIMIT_CALLS} (function counter; tag {@code result}:
 * {@code over-limit} or {@code rejected}).</li>
 * <li>{@value #LIMIT_OVERLOADED_SECONDS} (function counter).</li>
 * <li>{@value #LIMIT_LATENCY_BASELINE}, {@value #LIMIT_LATENCY_CURRENT} (gauges,
 * seconds).</li>
 * <li>{@value #LIMIT_SUGGESTED} (gauge), {@value #LIMIT_ENFORCED} (gauge, 0 or
 * 1), {@value #LIMIT_ERRORS} (function counter).</li>
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
	public static final String ATTEMPTS_STOPPED_BY_TIME_LIMIT = "service.client.attempts.stopped.by.time.limit";

	/**
	 * Counter of calls stopped because the request's deadline was spent (also the
	 * message code of their failure,
	 * {@link ServiceClientFailoverExecutor#DEADLINE_EXPIRED_CODE}).
	 */
	public static final String DEADLINE_EXPIRED = "service.client.deadline.expired";

	/**
	 * Counter of calls that went to their target's last non-empty
	 * endpoint list, as its current one was empty.
	 */
	public static final String CALLS_ON_LAST_ENDPOINTS = "service.client.calls.on.last.endpoints";

	/**
	 * Requests in flight gauge.
	 */
	public static final String IN_FLIGHT = "service.client.in.flight";

	/**
	 * Concurrency limit gauge.
	 */
	public static final String LIMIT = "service.client.limit";

	/**
	 * Operations in flight under the concurrency limit gauge.
	 */
	public static final String LIMIT_IN_FLIGHT = "service.client.limit.in.flight";

	/**
	 * Peak of operations in flight over the last 5 minutes gauge.
	 */
	public static final String LIMIT_IN_FLIGHT_PEAK = "service.client.limit.in.flight.peak";

	/**
	 * Operations over the concurrency limit while overloaded counter.
	 */
	public static final String LIMIT_CALLS = "service.client.limit.calls";

	/**
	 * Time overloaded counter (seconds).
	 */
	public static final String LIMIT_OVERLOADED_SECONDS = "service.client.limit.overloaded.seconds";

	/**
	 * Baseline latency gauge (seconds).
	 */
	public static final String LIMIT_LATENCY_BASELINE = "service.client.limit.latency.baseline";

	/**
	 * Latency of the last window gauge (seconds).
	 */
	public static final String LIMIT_LATENCY_CURRENT = "service.client.limit.latency.current";

	/**
	 * Suggested fixed concurrency limit gauge.
	 */
	public static final String LIMIT_SUGGESTED = "service.client.limit.suggested";

	/**
	 * If the concurrency limit is enforced gauge (0 or 1).
	 */
	public static final String LIMIT_ENFORCED = "service.client.limit.enforced";

	/**
	 * Failures inside the concurrency limiter counter.
	 */
	public static final String LIMIT_ERRORS = "service.client.limit.errors";

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
	 * @see ServiceClientMetrics#recordAttemptsStoppedByTimeLimit(String, String)
	 */
	@Override
	public void recordAttemptsStoppedByTimeLimit(
			final String target,
			final String reason) {
		this.meterRegistry.counter(MicrometerServiceClientMetrics.ATTEMPTS_STOPPED_BY_TIME_LIMIT, "target", target, "reason", reason).increment();
	}

	/**
	 * @see ServiceClientMetrics#recordDeadlineExpired(String, String)
	 */
	@Override
	public void recordDeadlineExpired(
			final String target,
			final String stage) {
		this.meterRegistry.counter(MicrometerServiceClientMetrics.DEADLINE_EXPIRED, "target", target, "stage", stage).increment();
	}

	/**
	 * @see ServiceClientMetrics#recordCallOnLastEndpoints(String)
	 */
	@Override
	public void recordCallOnLastEndpoints(
			final String target) {
		this.meterRegistry.counter(MicrometerServiceClientMetrics.CALLS_ON_LAST_ENDPOINTS, "target", target).increment();
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

	/**
	 * @see ServiceClientMetrics#registerConcurrencyLimit(ServiceClientConcurrencyLimitView)
	 */
	@Override
	public void registerConcurrencyLimit(
			final ServiceClientConcurrencyLimitView limit) {
		final Tags tags = Tags.of("target", limit.getTarget());
		Gauge.builder(MicrometerServiceClientMetrics.LIMIT, limit, ServiceClientConcurrencyLimitView::getLimit).tags(tags)
				.register(this.meterRegistry);
		Gauge.builder(MicrometerServiceClientMetrics.LIMIT_IN_FLIGHT, limit, ServiceClientConcurrencyLimitView::getInFlight).tags(tags)
				.register(this.meterRegistry);
		Gauge.builder(MicrometerServiceClientMetrics.LIMIT_IN_FLIGHT_PEAK, limit, ServiceClientConcurrencyLimitView::getInFlightPeak).tags(tags).register(this.meterRegistry);
		FunctionCounter.builder(MicrometerServiceClientMetrics.LIMIT_CALLS, limit, ServiceClientConcurrencyLimitView::getOverLimitCalls)
				.tags(tags.and("result", "over-limit")).register(this.meterRegistry);
		FunctionCounter.builder(MicrometerServiceClientMetrics.LIMIT_CALLS, limit, ServiceClientConcurrencyLimitView::getRejectedCalls)
				.tags(tags.and("result", "rejected")).register(this.meterRegistry);
		FunctionCounter
				.builder(MicrometerServiceClientMetrics.LIMIT_OVERLOADED_SECONDS, limit, ServiceClientConcurrencyLimitView::getOverloadedSeconds)
				.tags(tags).register(this.meterRegistry);
		Gauge.builder(MicrometerServiceClientMetrics.LIMIT_LATENCY_BASELINE, limit, ServiceClientConcurrencyLimitView::getBaselineLatencySeconds)
				.tags(tags).baseUnit("seconds").register(this.meterRegistry);
		Gauge.builder(MicrometerServiceClientMetrics.LIMIT_LATENCY_CURRENT, limit, ServiceClientConcurrencyLimitView::getCurrentLatencySeconds)
				.tags(tags).baseUnit("seconds").register(this.meterRegistry);
		Gauge.builder(MicrometerServiceClientMetrics.LIMIT_SUGGESTED, limit, ServiceClientConcurrencyLimitView::getSuggestedLimit).tags(tags).register(this.meterRegistry);
		Gauge.builder(MicrometerServiceClientMetrics.LIMIT_ENFORCED, limit, enforcedLimit -> (enforcedLimit.isEnforced() ? 1 : 0)).tags(tags)
				.register(this.meterRegistry);
		FunctionCounter.builder(MicrometerServiceClientMetrics.LIMIT_ERRORS, limit, ServiceClientConcurrencyLimitView::getErrors).tags(tags)
				.register(this.meterRegistry);
	}

}
