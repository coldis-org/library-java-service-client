package org.coldis.library.service.client;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import org.apache.commons.lang3.StringUtils;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.PropertyResolver;

/**
 * Adaptive limit of the operations in flight to each target (a generated client,
 * see {@link ServiceClientTarget}), learnt from the target's own latency and
 * overload answers, in memory only (no external system).
 * <p>
 * It measures, per target, the operations in flight (one permit per operation,
 * held across its failover attempts) and, per window (1 s and at least 10
 * samples), the mean latency of the attempts and the share of overload outcomes
 * (read timeouts, no pooled connection in time, statuses 429 and 503). The
 * baseline latency falls fast and rises slowly; a window is overloaded when its
 * mean latency is over the tolerance (3x) times the baseline, or at least 10% of
 * its outcomes are overload outcomes. An overloaded window lowers the limit (at
 * most halving it, never under the minimum); a healthy window with the in-flight
 * peak at half the limit or more raises it by its square root (up to the
 * maximum); a healthy target over its limit raises the limit to its in-flight
 * count, so a healthy target is never limited except by the maximum. The first
 * 10 windows are a warm-up: nothing counts as over the limit.
 * <p>
 * In {@link Mode#OBSERVE} (the default) it never rejects: operations that would
 * have been rejected are counted. In {@link Mode#ENFORCE}, an operation is
 * rejected (fail fast, no queue, before any request) only when the target is
 * overloaded and its in-flight count is over the limit; the minimum always
 * passes. In {@link Mode#OFF} it does nothing for the target.
 * <p>
 * Cost per operation: one map lookup, one atomic increment and decrement, one
 * volatile read of the limit, two {@link LongAdder} additions per attempt and
 * one clock read; no lock, no allocation, no background thread. The limit is
 * recomputed at most once per window, by the one thread that wins a
 * compare-and-set on the window end. The numbers are kept as {@code double} (not
 * {@code BigDecimal}): they are timings on the hot path, not business values.
 * <p>
 * It also suggests a fixed limit per target, from its healthy windows over the
 * last 24 hours ({@link #getSnapshot(String)}, a gauge, and a periodic log line).
 */
public class ServiceClientConcurrencyLimiter {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientConcurrencyLimiter.class);

	/**
	 * Prefix of the properties.
	 */
	public static final String PROPERTY_PREFIX = "org.coldis.library.service-client.concurrency-limit.";

	/**
	 * Code of the failure of a rejected operation.
	 */
	public static final String REJECTED_CODE = "service.client.concurrency.limit";

	/**
	 * Status of the failure of a rejected operation.
	 */
	public static final Integer REJECTED_STATUS = 503;

	/**
	 * When a rejected operation may be retried.
	 */
	public static final Duration REJECTED_RETRY_IN = Duration.ofSeconds(1);

	/**
	 * Maximum number of targets with a limit (others are not limited).
	 */
	public static final int MAX_TARGETS = 256;

	/**
	 * Windows of the warm-up of a target.
	 */
	public static final int WARM_UP_WINDOWS = 10;

	/**
	 * Weight of a window that lowers the baseline latency.
	 */
	private static final double BASELINE_FALL_WEIGHT = 0.2;

	/**
	 * Share of overload outcomes from which a window is overloaded.
	 */
	private static final double OVERLOAD_OUTCOMES_SHARE = 0.1;

	/**
	 * Smallest factor of an overloaded window on the limit (at most halved).
	 */
	private static final double MIN_DECREASE_FACTOR = 0.5;

	/**
	 * Weight of a healthy window in the slow averages of rate and latency.
	 */
	private static final double SLOW_AVERAGE_WEIGHT = 0.05;

	/**
	 * Slots of the in-flight peak (one per minute).
	 */
	private static final int PEAK_SLOTS = 5;

	/**
	 * Duration of an in-flight peak slot.
	 */
	private static final long PEAK_SLOT_NANOS = TimeUnit.MINUTES.toNanos(1);

	/**
	 * Slots of the suggestion (one per hour).
	 */
	private static final int SUGGESTION_SLOTS = 24;

	/**
	 * Duration of a suggestion slot.
	 */
	private static final long SUGGESTION_SLOT_NANOS = TimeUnit.HOURS.toNanos(1);

	/**
	 * Highest status an overload status may be.
	 */
	private static final int MAX_STATUS = 999;

	/**
	 * Mode of the limit of a target.
	 */
	public enum Mode {

		/**
		 * Nothing is measured or limited.
		 */
		OFF,

		/**
		 * Measured, never rejects (operations over the limit while overloaded are
		 * counted).
		 */
		OBSERVE,

		/**
		 * Measured, and rejects operations over the limit while overloaded.
		 */
		ENFORCE;

		/**
		 * Gets a mode from its property value ({@code off}, {@code observe} or
		 * {@code enforce}, in any case).
		 *
		 * @param  value Property value.
		 * @return       The mode.
		 */
		public static Mode fromPropertyValue(
				final String value) {
			return Mode.valueOf(StringUtils.trimToEmpty(value).toUpperCase(Locale.ROOT));
		}

	}

	/**
	 * Settings of the limiter (the defaults of every target).
	 *
	 * @param mode                  Mode.
	 * @param min                   Minimum limit (always passes).
	 * @param max                   Maximum limit.
	 * @param initial               Initial limit.
	 * @param window                Duration of a window.
	 * @param minSamples            Samples a window needs to close.
	 * @param latencyTolerance      Times the baseline latency a window's latency
	 *                                  may reach and still be healthy.
	 * @param baselineRiseTime      Time constant of the rise of the baseline
	 *                                  latency.
	 * @param overloadStatuses      Response statuses that are overload outcomes.
	 * @param suggestionMargin      Margin of the suggested fixed limit over the
	 *                                  peak demand.
	 * @param suggestionLogInterval Interval of the suggestion log line (zero for
	 *                                  none).
	 */
	public record Settings(
			Mode mode,
			int min,
			int max,
			int initial,
			Duration window,
			int minSamples,
			double latencyTolerance,
			Duration baselineRiseTime,
			Collection<Integer> overloadStatuses,
			double suggestionMargin,
			Duration suggestionLogInterval) {

		/**
		 * Shipped defaults: observe, minimum 10, maximum 256, initial 20, windows of
		 * 1 s with at least 10 samples, tolerance 3x, baseline rise time 10 min,
		 * statuses 429 and 503, suggestion margin 2x logged every 15 min.
		 */
		public static final Settings DEFAULTS = new Settings(Mode.OBSERVE, 10, 256, 20, Duration.ofSeconds(1), 10, 3.0, Duration.ofMinutes(10),
				List.of(429, 503), 2.0, Duration.ofMinutes(15));

		/**
		 * Gets the same settings with another mode.
		 *
		 * @param  newMode Mode.
		 * @return         The settings with the mode.
		 */
		public Settings withMode(
				final Mode newMode) {
			return new Settings(newMode, this.min, this.max, this.initial, this.window, this.minSamples, this.latencyTolerance, this.baselineRiseTime,
					this.overloadStatuses, this.suggestionMargin, this.suggestionLogInterval);
		}

	}

	/**
	 * Read-only view of the limit of a target.
	 *
	 * @param target                 Target.
	 * @param mode                   Mode.
	 * @param limit                  Current limit.
	 * @param inFlight               Operations in flight.
	 * @param inFlightPeak           Peak of operations in flight over the last 5
	 *                                   minutes.
	 * @param isOverloaded           If the last window was overloaded.
	 * @param baselineLatency        Baseline latency (zero before the first
	 *                                   window).
	 * @param currentLatency         Mean latency of the last window (zero
	 *                                   before the first window).
	 * @param suggestedLimit         Suggested fixed limit (zero before a healthy
	 *                                   window).
	 * @param averageRate            Slow average of the attempts per second of
	 *                                   the healthy windows.
	 * @param averageLatency         Slow average of the latency of the healthy
	 *                                   windows.
	 * @param overLimitCalls         Operations over the limit while overloaded
	 *                                   (not rejected).
	 * @param rejectedCalls          Operations rejected.
	 * @param overloadedTime         Time overloaded.
	 * @param windows                Windows closed.
	 * @param errors                 Failures inside the limiter (the operations
	 *                                   proceeded).
	 */
	public record Snapshot(
			String target,
			Mode mode,
			int limit,
			int inFlight,
			int inFlightPeak,
			boolean isOverloaded,
			Duration baselineLatency,
			Duration currentLatency,
			int suggestedLimit,
			double averageRate,
			Duration averageLatency,
			long overLimitCalls,
			long rejectedCalls,
			Duration overloadedTime,
			long windows,
			long errors) {}

	/**
	 * Permit of an operation, from {@link #acquire(String)} to
	 * {@link #release(Permit)}: an opaque handle.
	 */
	public interface Permit {}

	/**
	 * Limit of a target: its settings and state, written by the limiter. Read by
	 * the metrics through its {@link ServiceClientConcurrencyLimitView} (at scrape
	 * time). The slot arrays are written by the thread closing a window and read
	 * as best effort (a reading may be one window late).
	 */
	private static final class TargetLimit implements Permit, ServiceClientConcurrencyLimitView {

		/**
		 * Limiter.
		 */
		private final ServiceClientConcurrencyLimiter limiter;

		/**
		 * Target.
		 */
		private final String target;

		/**
		 * Mode.
		 */
		private final Mode mode;

		/**
		 * Minimum limit.
		 */
		private final int min;

		/**
		 * Maximum limit.
		 */
		private final int max;

		/**
		 * Initial limit.
		 */
		private final int initial;

		/**
		 * Distance between the values written by every operation and the others
		 * (16 longs, 128 bytes: two cache lines, so a write to one never invalidates
		 * the line of another, with adjacent-line prefetch too).
		 */
		private static final int VALUE_SPACING = 16;

		/**
		 * Index of the operations in flight.
		 */
		private static final int IN_FLIGHT = TargetLimit.VALUE_SPACING;

		/**
		 * Index of the peak of operations in flight in the current window.
		 */
		private static final int WINDOW_PEAK_IN_FLIGHT = 2 * TargetLimit.VALUE_SPACING;

		/**
		 * Index of the current limit.
		 */
		private static final int LIMIT = 3 * TargetLimit.VALUE_SPACING;

		/**
		 * Index of the end of the current window ({@link System#nanoTime()}).
		 */
		private static final int WINDOW_END_NANOS = 4 * TargetLimit.VALUE_SPACING;

		/**
		 * Values read or written by every operation, each on cache lines of its own
		 * (operations in flight, window peak, limit, window end).
		 */
		private final AtomicLongArray paddedCounters = new AtomicLongArray(5 * TargetLimit.VALUE_SPACING);

		/**
		 * If a thread is closing the window.
		 */
		private final AtomicBoolean isClosingWindow = new AtomicBoolean();

		/**
		 * Sum of the latencies of the current window (nanoseconds).
		 */
		private final LongAdder windowLatencySum = new LongAdder();

		/**
		 * Latency samples of the current window.
		 */
		private final LongAdder windowLatencySamples = new LongAdder();

		/**
		 * Overload outcomes of the current window.
		 */
		private final LongAdder windowOverloads = new LongAdder();

		/**
		 * Overload outcomes with no latency sample of the current window.
		 */
		private final LongAdder windowOverloadsWithoutSample = new LongAdder();

		/**
		 * Operations over the limit while overloaded, not rejected.
		 */
		private final LongAdder overLimitCalls = new LongAdder();

		/**
		 * Operations rejected.
		 */
		private final LongAdder rejectedCalls = new LongAdder();

		/**
		 * Failures inside the limiter.
		 */
		private final LongAdder errors = new LongAdder();

		/**
		 * Start of the current window (written by the thread closing the window).
		 */
		private long windowStartNanos;

		/**
		 * Windows closed.
		 */
		private volatile long windows;

		/**
		 * If the last window was overloaded.
		 */
		private volatile boolean isOverloaded;

		/**
		 * Baseline latency (nanoseconds; zero before the first window).
		 */
		private volatile double baselineLatencyNanos;

		/**
		 * Mean latency of the last window (nanoseconds; zero before the first).
		 */
		private volatile double currentLatencyNanos;

		/**
		 * Time overloaded (nanoseconds).
		 */
		private volatile long overloadedNanos;

		/**
		 * Slow average of the rate of samples (per second) of healthy windows.
		 */
		private volatile double averageRate;

		/**
		 * Slow average of the latency of healthy windows (nanoseconds).
		 */
		private volatile double averageHealthyLatencyNanos;

		/**
		 * Peak of operations in flight per minute slot.
		 */
		private final int[] peakSlots = new int[ServiceClientConcurrencyLimiter.PEAK_SLOTS];

		/**
		 * Minute of each in-flight peak slot.
		 */
		private final long[] peakSlotMinutes = new long[ServiceClientConcurrencyLimiter.PEAK_SLOTS];

		/**
		 * Peak demand (operations in flight) of healthy windows per hour slot.
		 */
		private final double[] demandSlots = new double[ServiceClientConcurrencyLimiter.SUGGESTION_SLOTS];

		/**
		 * Hour of each demand slot.
		 */
		private final long[] demandSlotHours = new long[ServiceClientConcurrencyLimiter.SUGGESTION_SLOTS];

		/**
		 * Last time the suggestion was logged ({@link System#nanoTime()}).
		 */
		private long lastLogNanos;

		/**
		 * Time overloaded at the last log line (nanoseconds).
		 */
		private long lastLogOverloadedNanos;

		/**
		 * Constructor.
		 *
		 * @param limiter    Limiter.
		 * @param target     Target.
		 * @param mode       Mode.
		 * @param min        Minimum limit.
		 * @param max        Maximum limit.
		 * @param initial    Initial limit.
		 * @param nowNanos   Now ({@link System#nanoTime()}).
		 * @param windowNanos Duration of a window (nanoseconds).
		 */
		private TargetLimit(
				final ServiceClientConcurrencyLimiter limiter,
				final String target,
				final Mode mode,
				final int min,
				final int max,
				final int initial,
				final long nowNanos,
				final long windowNanos) {
			super();
			this.limiter = limiter;
			this.target = target;
			this.mode = mode;
			this.min = min;
			this.max = max;
			this.initial = initial;
			this.paddedCounters.set(TargetLimit.LIMIT, initial);
			this.windowStartNanos = nowNanos;
			this.paddedCounters.set(TargetLimit.WINDOW_END_NANOS, nowNanos + windowNanos);
			this.lastLogNanos = nowNanos;
			Arrays.fill(this.peakSlotMinutes, Long.MIN_VALUE);
			Arrays.fill(this.demandSlotHours, Long.MIN_VALUE);
		}

		/**
		 * Gets the target.
		 *
		 * @return The target.
		 */
		@Override
		public String getTarget() {
			return this.target;
		}

		/**
		 * Gets the current limit.
		 *
		 * @return The current limit.
		 */
		@Override
		public int getLimit() {
			return (int) this.paddedCounters.get(TargetLimit.LIMIT);
		}

		/**
		 * Gets the operations in flight.
		 *
		 * @return The operations in flight.
		 */
		@Override
		public int getInFlight() {
			return (int) this.paddedCounters.get(TargetLimit.IN_FLIGHT);
		}

		/**
		 * Gets the operations over the limit while overloaded, not rejected.
		 *
		 * @return The operations over the limit while overloaded.
		 */
		@Override
		public long getOverLimitCalls() {
			return this.overLimitCalls.sum();
		}

		/**
		 * Gets the operations rejected.
		 *
		 * @return The operations rejected.
		 */
		@Override
		public long getRejectedCalls() {
			return this.rejectedCalls.sum();
		}

		/**
		 * Gets the failures inside the limiter.
		 *
		 * @return The failures inside the limiter.
		 */
		@Override
		public long getErrors() {
			return this.errors.sum();
		}

		/**
		 * Gets the time overloaded.
		 *
		 * @return The time overloaded (seconds).
		 */
		@Override
		public double getOverloadedSeconds() {
			return this.overloadedNanos / (double) TimeUnit.SECONDS.toNanos(1);
		}

		/**
		 * Gets the baseline latency.
		 *
		 * @return The baseline latency (seconds; zero before the first window).
		 */
		@Override
		public double getBaselineLatencySeconds() {
			return this.baselineLatencyNanos / TimeUnit.SECONDS.toNanos(1);
		}

		/**
		 * Gets the mean latency of the last window.
		 *
		 * @return The mean latency of the last window (seconds; zero before the
		 *         first window).
		 */
		@Override
		public double getCurrentLatencySeconds() {
			return this.currentLatencyNanos / TimeUnit.SECONDS.toNanos(1);
		}

		/**
		 * Forgets what the limit learnt (limit, windows, baseline, overload and
		 * suggestion), starting a new window; the operations in flight and the
		 * counters are kept.
		 *
		 * @param nowNanos    Now ({@link System#nanoTime()}).
		 * @param windowNanos Duration of a window (nanoseconds).
		 */
		private void reset(
				final long nowNanos,
				final long windowNanos) {
			this.paddedCounters.set(TargetLimit.LIMIT, this.initial);
			this.paddedCounters.set(TargetLimit.WINDOW_PEAK_IN_FLIGHT, this.paddedCounters.get(TargetLimit.IN_FLIGHT));
			this.paddedCounters.set(TargetLimit.WINDOW_END_NANOS, nowNanos + windowNanos);
			this.windowLatencySum.reset();
			this.windowLatencySamples.reset();
			this.windowOverloads.reset();
			this.windowOverloadsWithoutSample.reset();
			this.windowStartNanos = nowNanos;
			this.isOverloaded = false;
			this.baselineLatencyNanos = 0;
			this.currentLatencyNanos = 0;
			this.averageRate = 0;
			this.averageHealthyLatencyNanos = 0;
			Arrays.fill(this.peakSlots, 0);
			Arrays.fill(this.peakSlotMinutes, Long.MIN_VALUE);
			Arrays.fill(this.demandSlots, 0);
			Arrays.fill(this.demandSlotHours, Long.MIN_VALUE);
			this.windows = 0;
		}

		/**
		 * @see ServiceClientConcurrencyLimitView#isEnforced()
		 */
		@Override
		public boolean isEnforced() {
			return (this.mode == Mode.ENFORCE);
		}

		/**
		 * @see ServiceClientConcurrencyLimitView#getInFlightPeak()
		 */
		@Override
		public int getInFlightPeak() {
			return this.limiter.computeInFlightPeak(this, this.limiter.nanoClock.getAsLong());
		}

		/**
		 * @see ServiceClientConcurrencyLimitView#getSuggestedLimit()
		 */
		@Override
		public double getSuggestedLimit() {
			final int suggestedLimit = this.limiter.computeSuggestedLimit(this, this.limiter.nanoClock.getAsLong());
			return (suggestedLimit == 0 ? Double.NaN : suggestedLimit);
		}

	}

	/**
	 * Limiter that limits nothing.
	 */
	public static final ServiceClientConcurrencyLimiter NONE = new ServiceClientConcurrencyLimiter(Settings.DEFAULTS.withMode(Mode.OFF), null,
			ServiceClientMetrics.NONE);

	/**
	 * Settings (the defaults of every target).
	 */
	private final Settings settings;

	/**
	 * Resolver of the per-target settings ({@code null} for none).
	 */
	private final PropertyResolver propertyResolver;

	/**
	 * Metrics.
	 */
	private final ServiceClientMetrics metrics;

	/**
	 * Clock ({@link System#nanoTime()} outside tests).
	 */
	private final LongSupplier nanoClock;

	/**
	 * Duration of a window (nanoseconds).
	 */
	private final long windowNanos;

	/**
	 * Overload statuses (indexed by status).
	 */
	private final boolean[] isOverloadStatus = new boolean[ServiceClientConcurrencyLimiter.MAX_STATUS + 1];

	/**
	 * If nothing is limited for any target (mode off and no per-target settings).
	 */
	private final boolean isOffEverywhere;

	/**
	 * Limits per target.
	 */
	private final Map<String, TargetLimit> targetLimits = new ConcurrentHashMap<>();

	/**
	 * Failures inside the limiter with no target limit.
	 */
	private final LongAdder errorsWithoutTarget = new LongAdder();

	/**
	 * If a failure inside the limiter was warned about.
	 */
	private final AtomicBoolean isFailureWarned = new AtomicBoolean();

	/**
	 * If the targets beyond the maximum were warned about.
	 */
	private final AtomicBoolean isMaxTargetsWarned = new AtomicBoolean();

	/**
	 * Constructor.
	 *
	 * @param settings         Settings (the defaults of every target).
	 * @param propertyResolver Resolver of the per-target settings
	 *                             ({@code PROPERTY_PREFIX + "mode." + target},
	 *                             {@code min.<target>}, {@code max.<target>});
	 *                             {@code null} for none.
	 * @param metrics          Metrics.
	 * @param nanoClock        Clock ({@link System#nanoTime()} outside tests).
	 */
	public ServiceClientConcurrencyLimiter(
			final Settings settings,
			final PropertyResolver propertyResolver,
			final ServiceClientMetrics metrics,
			final LongSupplier nanoClock) {
		super();
		this.settings = Objects.requireNonNull(settings, "settings");
		this.propertyResolver = propertyResolver;
		this.metrics = Objects.requireNonNull(metrics, "metrics");
		this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
		this.windowNanos = Math.max(1, settings.window().toNanos());
		(settings.overloadStatuses() == null ? List.<Integer>of() : settings.overloadStatuses()).stream()
				.filter(status -> (status != null) && (status >= 0) && (status <= ServiceClientConcurrencyLimiter.MAX_STATUS))
				.forEach(status -> this.isOverloadStatus[status] = true);
		this.isOffEverywhere = (settings.mode() == Mode.OFF) && (propertyResolver == null);
	}

	/**
	 * Constructor with the system clock.
	 *
	 * @param settings         Settings (the defaults of every target).
	 * @param propertyResolver Resolver of the per-target settings ({@code null}
	 *                             for none).
	 * @param metrics          Metrics.
	 */
	public ServiceClientConcurrencyLimiter(final Settings settings, final PropertyResolver propertyResolver, final ServiceClientMetrics metrics) {
		this(settings, propertyResolver, metrics, System::nanoTime);
	}

	/**
	 * Gets the settings (the defaults of every target).
	 *
	 * @return The settings.
	 */
	public Settings getSettings() {
		return this.settings;
	}

	/**
	 * Gets if a response status is an overload outcome.
	 *
	 * @param  status Response status.
	 * @return        If the status is an overload outcome.
	 */
	public boolean isOverloadStatus(
			final int status) {
		return (status >= 0) && (status <= ServiceClientConcurrencyLimiter.MAX_STATUS) && this.isOverloadStatus[status];
	}

	/**
	 * Gets a per-target property, or the default.
	 *
	 * @param  name         Property name (after the prefix).
	 * @param  target       Target.
	 * @param  defaultValue Default value.
	 * @return              The property of the target, or the default.
	 */
	private String getTargetProperty(
			final String name,
			final String target,
			final String defaultValue) {
		final String value = (this.propertyResolver == null ? null
				: this.propertyResolver.getProperty(ServiceClientConcurrencyLimiter.PROPERTY_PREFIX + name + "." + target));
		return (StringUtils.isBlank(value) ? defaultValue : value.trim());
	}

	/**
	 * Creates the limit of a target, with its own mode, minimum and maximum when
	 * set (an invalid one is warned about and the default kept).
	 *
	 * @param  target Target.
	 * @return        The limit of the target.
	 */
	private TargetLimit createTargetLimit(
			final String target) {
		Mode mode = this.settings.mode();
		int min = this.settings.min();
		int max = this.settings.max();
		try {
			mode = Mode.fromPropertyValue(this.getTargetProperty("mode", target, mode.name()));
			min = Integer.parseInt(this.getTargetProperty("min", target, Integer.toString(min)));
			max = Integer.parseInt(this.getTargetProperty("max", target, Integer.toString(max)));
		}
		catch (final IllegalArgumentException exception) {
			ServiceClientConcurrencyLimiter.LOGGER.warn("Concurrency limit settings of {} are invalid ({}): the defaults are used.", target,
					exception.getMessage());
			mode = this.settings.mode();
			min = this.settings.min();
			max = this.settings.max();
		}
		final int actualMin = Math.max(1, min);
		final int actualMax = Math.max(actualMin, max);
		final int initial = Math.min(actualMax, Math.max(actualMin, this.settings.initial()));
		return new TargetLimit(this, target, mode, actualMin, actualMax, initial, this.nanoClock.getAsLong(), this.windowNanos);
	}

	/**
	 * Gets the limit of a target, created on its first operation (none for
	 * {@link ServiceClientTarget#NONE} or beyond {@link #MAX_TARGETS}).
	 *
	 * @param  target Target.
	 * @return        The limit of the target ({@code null} for none).
	 */
	private TargetLimit getTargetLimit(
			final String target) {
		TargetLimit targetLimit = (target == null ? null : this.targetLimits.get(target));
		if ((targetLimit == null) && (target != null) && !ServiceClientTarget.NONE.equals(target)) {
			if (this.targetLimits.size() < ServiceClientConcurrencyLimiter.MAX_TARGETS) {
				final TargetLimit newTargetLimit = this.createTargetLimit(target);
				final TargetLimit existingTargetLimit = this.targetLimits.putIfAbsent(target, newTargetLimit);
				targetLimit = (existingTargetLimit == null ? newTargetLimit : existingTargetLimit);
				if ((existingTargetLimit == null) && (newTargetLimit.mode != Mode.OFF)) {
					try {
						this.metrics.registerConcurrencyLimit(newTargetLimit);
					}
					catch (final RuntimeException exception) {
						ServiceClientConcurrencyLimiter.LOGGER.warn("Concurrency limit metrics of {} not registered ({}).", target,
								exception.getClass().getSimpleName());
					}
					ServiceClientConcurrencyLimiter.LOGGER.info("Concurrency limit of {}: mode {}, limit {} (min {}, max {}).", target,
							newTargetLimit.mode.name().toLowerCase(Locale.ROOT), newTargetLimit.getLimit(), newTargetLimit.min, newTargetLimit.max);
				}
			}
			else if (this.isMaxTargetsWarned.compareAndSet(false, true)) {
				ServiceClientConcurrencyLimiter.LOGGER.warn("Concurrency limit: more than {} targets, the others are not limited.",
						ServiceClientConcurrencyLimiter.MAX_TARGETS);
			}
		}
		return targetLimit;
	}

	/**
	 * Creates the failure of a rejected operation.
	 *
	 * @param  targetLimit Limit of the target.
	 * @param  inFlight    Operations in flight with the rejected one.
	 * @param  limit       Limit.
	 * @return             The failure.
	 */
	private static IntegrationException createRejection(
			final TargetLimit targetLimit,
			final int inFlight,
			final int limit) {
		return new IntegrationException(
				new SimpleMessage(ServiceClientConcurrencyLimiter.REJECTED_CODE,
						"Concurrency limit of " + targetLimit.target + " reached (" + inFlight + " in flight, limit " + limit + ", overloaded): call rejected."),
				ServiceClientConcurrencyLimiter.REJECTED_STATUS, ServiceClientConcurrencyLimiter.REJECTED_RETRY_IN, null);
	}

	/**
	 * Gets if a failure is the rejection of an operation by a limiter.
	 *
	 * @param  failure Failure.
	 * @return         If the failure is a rejection.
	 */
	public static boolean isRejection(
			final Throwable failure) {
		return (failure instanceof final IntegrationException integrationFailure) && (integrationFailure.getInternalMessage() != null)
				&& ServiceClientConcurrencyLimiter.REJECTED_CODE.equals(integrationFailure.getInternalMessage().getCode());
	}

	/**
	 * Starts an operation of a target: takes its permit, held until
	 * {@link #release(Permit)} (across all its attempts). A healthy target over
	 * its limit raises the limit; an overloaded one over its limit is counted, and
	 * in {@link Mode#ENFORCE} rejected (the permit is not kept). Nothing counts as
	 * over the limit during the warm-up.
	 *
	 * @param  target               Target.
	 * @return                      The permit of the operation, to give back to
	 *                              {@link #recordAttempt} and {@link #release}
	 *                              ({@code null} when the target is not limited).
	 * @throws IntegrationException If the operation is rejected
	 *                                  ({@value #REJECTED_CODE}, status 503,
	 *                                  retry in 1 s).
	 */
	public Permit acquire(
			final String target) throws IntegrationException {
		final TargetLimit targetLimit = (this.isOffEverywhere ? null : this.getTargetLimit(target));
		Permit acquiredLimit = null;
		if ((targetLimit != null) && (targetLimit.mode != Mode.OFF)) {
			final long inFlight = targetLimit.paddedCounters.incrementAndGet(TargetLimit.IN_FLIGHT);
			long windowPeak = targetLimit.paddedCounters.get(TargetLimit.WINDOW_PEAK_IN_FLIGHT);
			while ((inFlight > windowPeak) && !targetLimit.paddedCounters.compareAndSet(TargetLimit.WINDOW_PEAK_IN_FLIGHT, windowPeak, inFlight)) {
				windowPeak = targetLimit.paddedCounters.get(TargetLimit.WINDOW_PEAK_IN_FLIGHT);
			}
			final long limit = targetLimit.paddedCounters.get(TargetLimit.LIMIT);
			if ((inFlight > limit) && (targetLimit.windows >= ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS)) {
				if (!targetLimit.isOverloaded) {
					targetLimit.paddedCounters.accumulateAndGet(TargetLimit.LIMIT, Math.min(targetLimit.max, inFlight), Math::max);
				}
				else if (targetLimit.mode == Mode.ENFORCE) {
					targetLimit.paddedCounters.decrementAndGet(TargetLimit.IN_FLIGHT);
					targetLimit.rejectedCalls.increment();
					throw ServiceClientConcurrencyLimiter.createRejection(targetLimit, (int) inFlight, (int) limit);
				}
				else {
					targetLimit.overLimitCalls.increment();
				}
			}
			acquiredLimit = targetLimit;
		}
		return acquiredLimit;
	}

	/**
	 * Records an attempt of an operation.
	 *
	 * @param permit          Permit of the operation ({@code null} does nothing).
	 * @param latencyNanos    Latency of the attempt (nanoseconds).
	 * @param isLatencySample If the latency is a sample (the target answered, or
	 *                            did not in the read timeout).
	 * @param isOverload      If the outcome is an overload outcome.
	 */
	public void recordAttempt(
			final Permit permit,
			final long latencyNanos,
			final boolean isLatencySample,
			final boolean isOverload) {
		if (permit instanceof final TargetLimit targetLimit) {
			if (isLatencySample) {
				targetLimit.windowLatencySum.add(Math.max(0, latencyNanos));
				targetLimit.windowLatencySamples.increment();
			}
			if (isOverload) {
				targetLimit.windowOverloads.increment();
				if (!isLatencySample) {
					targetLimit.windowOverloadsWithoutSample.increment();
				}
			}
		}
	}

	/**
	 * Gets the peak of a set of time slots over its recent slots.
	 *
	 * @param  slots      Peaks per slot.
	 * @param  slotTimes  Time (slot number) of each slot.
	 * @param  latestTime Latest slot number.
	 * @return            The peak over the slots not older than the slot count.
	 */
	private static double getRecentPeak(
			final double[] slots,
			final long[] slotTimes,
			final long latestTime) {
		double peak = 0;
		for (int slotIndex = 0; slotIndex < slots.length; slotIndex++) {
			if ((slotTimes[slotIndex] != Long.MIN_VALUE) && ((latestTime - slotTimes[slotIndex]) < slots.length)) {
				peak = Math.max(peak, slots[slotIndex]);
			}
		}
		return peak;
	}

	/**
	 * Gets the peak of operations in flight over the last 5 minutes.
	 *
	 * @param  targetLimit Limit of the target.
	 * @param  nowNanos    Now ({@link System#nanoTime()}).
	 * @return             The peak of operations in flight over the last 5
	 *                     minutes.
	 */
	private int computeInFlightPeak(
			final TargetLimit targetLimit,
			final long nowNanos) {
		final long minute = Math.floorDiv(nowNanos, ServiceClientConcurrencyLimiter.PEAK_SLOT_NANOS);
		int peak = (int) Math.max(targetLimit.paddedCounters.get(TargetLimit.WINDOW_PEAK_IN_FLIGHT), targetLimit.paddedCounters.get(TargetLimit.IN_FLIGHT));
		for (int slotIndex = 0; slotIndex < ServiceClientConcurrencyLimiter.PEAK_SLOTS; slotIndex++) {
			if ((targetLimit.peakSlotMinutes[slotIndex] != Long.MIN_VALUE) && ((minute - targetLimit.peakSlotMinutes[slotIndex]) < ServiceClientConcurrencyLimiter.PEAK_SLOTS)) {
				peak = Math.max(peak, targetLimit.peakSlots[slotIndex]);
			}
		}
		return peak;
	}

	/**
	 * Gets the suggested fixed limit of a target: the margin times its peak demand
	 * (the larger of rate times latency and the in-flight peak) over its healthy
	 * windows of the last 24 hours, never under its minimum.
	 *
	 * @param  targetLimit Limit of the target.
	 * @param  nowNanos    Now ({@link System#nanoTime()}).
	 * @return             The suggested fixed limit (zero before a healthy
	 *                     window).
	 */
	private int computeSuggestedLimit(
			final TargetLimit targetLimit,
			final long nowNanos) {
		final double peakDemand = ServiceClientConcurrencyLimiter.getRecentPeak(targetLimit.demandSlots, targetLimit.demandSlotHours,
				Math.floorDiv(nowNanos, ServiceClientConcurrencyLimiter.SUGGESTION_SLOT_NANOS));
		return (peakDemand <= 0 ? 0 : Math.max(targetLimit.min, (int) Math.ceil(this.settings.suggestionMargin() * peakDemand)));
	}

	/**
	 * Updates the in-flight peak and the suggestion slots with a closed window.
	 *
	 * @param targetLimit  Limit of the target.
	 * @param nowNanos     Now ({@link System#nanoTime()}).
	 * @param peakInFlight Peak of operations in flight of the window.
	 * @param demand       Demand of the window (operations in flight; negative
	 *                         when the window is not healthy).
	 */
	private static void updateSlots(
			final TargetLimit targetLimit,
			final long nowNanos,
			final int peakInFlight,
			final double demand) {
		final long minute = Math.floorDiv(nowNanos, ServiceClientConcurrencyLimiter.PEAK_SLOT_NANOS);
		final int peakSlot = (int) Math.floorMod(minute, (long) ServiceClientConcurrencyLimiter.PEAK_SLOTS);
		if (targetLimit.peakSlotMinutes[peakSlot] != minute) {
			targetLimit.peakSlots[peakSlot] = 0;
			targetLimit.peakSlotMinutes[peakSlot] = minute;
		}
		targetLimit.peakSlots[peakSlot] = Math.max(targetLimit.peakSlots[peakSlot], peakInFlight);
		if (demand >= 0) {
			final long hour = Math.floorDiv(nowNanos, ServiceClientConcurrencyLimiter.SUGGESTION_SLOT_NANOS);
			final int demandSlot = (int) Math.floorMod(hour, (long) ServiceClientConcurrencyLimiter.SUGGESTION_SLOTS);
			if (targetLimit.demandSlotHours[demandSlot] != hour) {
				targetLimit.demandSlots[demandSlot] = 0;
				targetLimit.demandSlotHours[demandSlot] = hour;
			}
			targetLimit.demandSlots[demandSlot] = Math.max(targetLimit.demandSlots[demandSlot], demand);
		}
	}

	/**
	 * Logs the limit and the suggested fixed limit of a target, at most once per
	 * log interval.
	 *
	 * @param targetLimit Limit of the target.
	 * @param nowNanos    Now ({@link System#nanoTime()}).
	 */
	private void logSuggestion(
			final TargetLimit targetLimit,
			final long nowNanos) {
		final long logIntervalNanos = (this.settings.suggestionLogInterval() == null ? 0 : this.settings.suggestionLogInterval().toNanos());
		if ((logIntervalNanos > 0) && ((nowNanos - targetLimit.lastLogNanos) >= logIntervalNanos)) {
			final int suggestedLimit = this.computeSuggestedLimit(targetLimit, nowNanos);
			ServiceClientConcurrencyLimiter.LOGGER.info(
					"Target {}: limit {}, in flight peak {}, latency {} ms (baseline {} ms), suggested fixed limit {}, overloaded {} s.", targetLimit.target,
					targetLimit.getLimit(), this.computeInFlightPeak(targetLimit, nowNanos),
					Math.round(targetLimit.averageHealthyLatencyNanos / TimeUnit.MILLISECONDS.toNanos(1)),
					Math.round(targetLimit.baselineLatencyNanos / TimeUnit.MILLISECONDS.toNanos(1)),
					(suggestedLimit == 0 ? "(no healthy window yet)" : suggestedLimit),
					TimeUnit.NANOSECONDS.toSeconds(targetLimit.overloadedNanos - targetLimit.lastLogOverloadedNanos));
			targetLimit.lastLogNanos = nowNanos;
			targetLimit.lastLogOverloadedNanos = targetLimit.overloadedNanos;
		}
	}

	/**
	 * Gets the next limit of a target after a window.
	 *
	 * @param  targetLimit       Limit of the target.
	 * @param  limit             Limit before the window closes.
	 * @param  currentLatency    Mean latency of the window (nanoseconds; zero
	 *                               with no sample).
	 * @param  isLatencyOverload If the window's latency is over the tolerance.
	 * @param  overloadShare     Share of overload outcomes of the window.
	 * @param  isOverloaded      If the window is overloaded.
	 * @param  peakInFlight      Peak of operations in flight of the window.
	 * @return                   The next limit.
	 */
	private int getNextLimit(
			final TargetLimit targetLimit,
			final int limit,
			final double currentLatency,
			final boolean isLatencyOverload,
			final double overloadShare,
			final boolean isOverloaded,
			final int peakInFlight) {
		int nextLimit = limit;
		if (isOverloaded) {
			double factor = 1;
			if (isLatencyOverload) {
				factor = (this.settings.latencyTolerance() * targetLimit.baselineLatencyNanos) / currentLatency;
			}
			if (overloadShare >= ServiceClientConcurrencyLimiter.OVERLOAD_OUTCOMES_SHARE) {
				factor = Math.min(factor, 1 - overloadShare);
			}
			factor = Math.max(ServiceClientConcurrencyLimiter.MIN_DECREASE_FACTOR, factor);
			nextLimit = Math.max(targetLimit.min, (int) Math.floor(limit * factor));
		}
		else if ((peakInFlight * 2) >= limit) {
			nextLimit = Math.min(targetLimit.max, limit + (int) Math.ceil(Math.sqrt(limit)));
		}
		return nextLimit;
	}

	/**
	 * Gets the next baseline latency after a window: it falls fast and rises
	 * slowly (time constant: the baseline rise time).
	 *
	 * @param  targetLimit    Limit of the target.
	 * @param  currentLatency Mean latency of the window (nanoseconds; zero with no
	 *                            sample).
	 * @param  elapsedNanos   Duration of the window charged (at most one window).
	 * @return                The next baseline latency.
	 */
	private double getNextBaseline(
			final TargetLimit targetLimit,
			final double currentLatency,
			final long elapsedNanos) {
		final double baseline = targetLimit.baselineLatencyNanos;
		final double nextBaseline;
		if (currentLatency <= 0) {
			nextBaseline = baseline;
		}
		else if (baseline <= 0) {
			nextBaseline = currentLatency;
		}
		else if (currentLatency < baseline) {
			nextBaseline = baseline + (ServiceClientConcurrencyLimiter.BASELINE_FALL_WEIGHT * (currentLatency - baseline));
		}
		else {
			final double riseNanos = Math.max(1, this.settings.baselineRiseTime().toNanos());
			nextBaseline = baseline + ((currentLatency - baseline) * (1 - Math.exp(-elapsedNanos / riseNanos)));
		}
		return nextBaseline;
	}

	/**
	 * Closes the window of a target: recomputes its limit, baseline latency and
	 * suggestion, and starts the next window. Called by one thread at a time.
	 *
	 * @param targetLimit Limit of the target.
	 * @param nowNanos    Now ({@link System#nanoTime()}).
	 */
	private void closeWindow(
			final TargetLimit targetLimit,
			final long nowNanos) {
		final long elapsedNanos = Math.max(1, nowNanos - targetLimit.windowStartNanos);
		// A window kept open by low traffic charges at most one window of time to
		// the overloaded time and the baseline rise (its idle time is not counted).
		final long chargedNanos = Math.min(elapsedNanos, this.windowNanos);
		final long latencySum = targetLimit.windowLatencySum.sumThenReset();
		final long latencySamples = targetLimit.windowLatencySamples.sumThenReset();
		final long overloads = targetLimit.windowOverloads.sumThenReset();
		final long overloadsWithoutSample = targetLimit.windowOverloadsWithoutSample.sumThenReset();
		final int peakInFlight = (int) targetLimit.paddedCounters.getAndSet(TargetLimit.WINDOW_PEAK_IN_FLIGHT, targetLimit.paddedCounters.get(TargetLimit.IN_FLIGHT));
		final long outcomes = latencySamples + overloadsWithoutSample;
		final double currentLatency = (latencySamples > 0 ? latencySum / (double) latencySamples : 0);
		// The two adders are reset one after the other, so an outcome may land
		// between them: the share is capped at 1.
		final double overloadShare = (outcomes > 0 ? Math.min(1, overloads / (double) outcomes) : 0);
		final boolean isWarmUp = (targetLimit.windows < ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS);
		final boolean isLatencyOverload = (currentLatency > 0) && (targetLimit.baselineLatencyNanos > 0)
				&& (currentLatency > (this.settings.latencyTolerance() * targetLimit.baselineLatencyNanos));
		final boolean isOverloaded = !isWarmUp && (isLatencyOverload || (overloadShare >= ServiceClientConcurrencyLimiter.OVERLOAD_OUTCOMES_SHARE));
		if (!isWarmUp) {
			// A raise made by an operation meanwhile (healthy, over the limit) wins.
			final int limit = targetLimit.getLimit();
			targetLimit.paddedCounters.compareAndSet(TargetLimit.LIMIT, limit,
					this.getNextLimit(targetLimit, limit, currentLatency, isLatencyOverload, overloadShare, isOverloaded, peakInFlight));
		}
		final boolean isHealthy = !isWarmUp && !isOverloaded;
		if (isHealthy) {
			final double rate = latencySamples / (elapsedNanos / (double) TimeUnit.SECONDS.toNanos(1));
			targetLimit.averageRate = (targetLimit.averageRate <= 0 ? rate
					: targetLimit.averageRate + (ServiceClientConcurrencyLimiter.SLOW_AVERAGE_WEIGHT * (rate - targetLimit.averageRate)));
			targetLimit.averageHealthyLatencyNanos = (targetLimit.averageHealthyLatencyNanos <= 0 ? currentLatency
					: targetLimit.averageHealthyLatencyNanos
							+ (ServiceClientConcurrencyLimiter.SLOW_AVERAGE_WEIGHT * (currentLatency - targetLimit.averageHealthyLatencyNanos)));
		}
		// Little's law: rate times latency is the sum of the latencies over the window.
		ServiceClientConcurrencyLimiter.updateSlots(targetLimit, nowNanos, peakInFlight, isHealthy ? Math.max(latencySum / (double) elapsedNanos, peakInFlight) : -1);
		targetLimit.baselineLatencyNanos = this.getNextBaseline(targetLimit, currentLatency, chargedNanos);
		targetLimit.currentLatencyNanos = currentLatency;
		targetLimit.overloadedNanos = targetLimit.overloadedNanos + (isOverloaded ? chargedNanos : 0);
		targetLimit.isOverloaded = isOverloaded;
		targetLimit.windowStartNanos = nowNanos;
		targetLimit.windows = targetLimit.windows + 1;
		this.logSuggestion(targetLimit, nowNanos);
	}

	/**
	 * Closes the window of a target if it is due (its end passed, with enough
	 * samples), in the one thread that wins the window end.
	 *
	 * @param targetLimit Limit of the target.
	 */
	private void closeWindowIfDue(
			final TargetLimit targetLimit) {
		final long nowNanos = this.nanoClock.getAsLong();
		final long windowEndNanos = targetLimit.paddedCounters.get(TargetLimit.WINDOW_END_NANOS);
		if (((nowNanos - windowEndNanos) >= 0)
				&& ((targetLimit.windowLatencySamples.sum() + targetLimit.windowOverloadsWithoutSample.sum()) >= this.settings.minSamples())
				&& targetLimit.isClosingWindow.compareAndSet(false, true)) {
			try {
				if (targetLimit.paddedCounters.compareAndSet(TargetLimit.WINDOW_END_NANOS, windowEndNanos, nowNanos + this.windowNanos)) {
					this.closeWindow(targetLimit, nowNanos);
				}
			}
			finally {
				targetLimit.isClosingWindow.set(false);
			}
		}
	}

	/**
	 * Ends an operation: gives its permit back, and closes the target's window if
	 * it is due.
	 *
	 * @param permit Permit of the operation ({@code null} does nothing).
	 */
	public void release(
			final Permit permit) {
		if (permit instanceof final TargetLimit targetLimit) {
			targetLimit.paddedCounters.decrementAndGet(TargetLimit.IN_FLIGHT);
			this.closeWindowIfDue(targetLimit);
		}
	}

	/**
	 * Forgets what every target's limit learnt (see the shared test context);
	 * the targets, their meters, operations in flight and counters are kept.
	 */
	public void clear() {
		final long nowNanos = this.nanoClock.getAsLong();
		this.targetLimits.values().forEach(targetLimit -> targetLimit.reset(nowNanos, this.windowNanos));
	}

	/**
	 * Records a failure inside the limiter (the operation proceeds): counted, and
	 * warned about once.
	 *
	 * @param target  Target of the operation.
	 * @param failure Failure.
	 */
	public final void recordFailure(
			final String target,
			final RuntimeException failure) {
		final TargetLimit targetLimit = (target == null ? null : this.targetLimits.get(target));
		if (targetLimit == null) {
			this.errorsWithoutTarget.increment();
		}
		else {
			targetLimit.errors.increment();
		}
		if (this.isFailureWarned.compareAndSet(false, true)) {
			ServiceClientConcurrencyLimiter.LOGGER.warn("Concurrency limit failed ({}): the call proceeded (warned once).",
					(failure == null ? "unknown" : failure.getClass().getSimpleName()));
		}
		ServiceClientConcurrencyLimiter.LOGGER.debug("Concurrency limit failed: the call proceeded.", failure);
	}

	/**
	 * Gets the failures inside the limiter with no target limit.
	 *
	 * @return The failures inside the limiter with no target limit.
	 */
	public long getErrorsWithoutTarget() {
		return this.errorsWithoutTarget.sum();
	}

	/**
	 * Gets a read-only view of the limit of a target.
	 *
	 * @param  target Target.
	 * @return        The view of the limit of the target (empty if it has none
	 *                yet).
	 */
	public Optional<Snapshot> getSnapshot(
			final String target) {
		final long nowNanos = this.nanoClock.getAsLong();
		return Optional.ofNullable(target == null ? null : this.targetLimits.get(target))
				.map(targetLimit -> new Snapshot(targetLimit.target, targetLimit.mode, targetLimit.getLimit(), targetLimit.getInFlight(),
						this.computeInFlightPeak(targetLimit, nowNanos), targetLimit.isOverloaded, Duration.ofNanos(Math.round(targetLimit.baselineLatencyNanos)),
						Duration.ofNanos(Math.round(targetLimit.currentLatencyNanos)), this.computeSuggestedLimit(targetLimit, nowNanos),
						targetLimit.averageRate, Duration.ofNanos(Math.round(targetLimit.averageHealthyLatencyNanos)), targetLimit.getOverLimitCalls(), targetLimit.getRejectedCalls(), Duration.ofNanos(targetLimit.overloadedNanos), targetLimit.windows,
						targetLimit.getErrors()));
	}

}
