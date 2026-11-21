package org.coldis.library.test.service.client;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.ServiceClientConcurrencyLimiter;
import org.coldis.library.service.client.ServiceClientEndpointBalancer;
import org.coldis.library.service.client.ServiceClientEndpointResolver;
import org.coldis.library.service.client.ServiceClientFailoverExecutor;
import org.coldis.library.service.client.ServiceClientMetrics;
import org.coldis.library.service.client.ServiceClientRequestFactory;
import org.coldis.library.service.client.ServiceClientTarget;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Failover executor with a concurrency limiter (plain objects): one permit per
 * operation, what each attempt outcome feeds the limiter, enforce and observe,
 * and a failing limiter that never changes a call.
 */
public class ServiceClientFailoverExecutorConcurrencyLimitTest {

	/**
	 * Target of the tests.
	 */
	private static final String TARGET = "ExecutorLimitTestClient";

	/**
	 * Response type.
	 */
	private static final ParameterizedTypeReference<String> STRING_RESPONSE = new ParameterizedTypeReference<>() {};

	/**
	 * Clock of the tests (nanoseconds).
	 */
	private final AtomicLong clock = new AtomicLong(TimeUnit.HOURS.toNanos(1000));

	/**
	 * Limiter that records what the executor asks of it.
	 */
	private static class RecordingLimiter extends ServiceClientConcurrencyLimiter {

		/**
		 * Recorded events.
		 */
		private final List<String> events = new CopyOnWriteArrayList<>();

		/**
		 * Constructor.
		 *
		 * @param mode Mode.
		 */
		RecordingLimiter(final Mode mode) {
			super(ServiceClientConcurrencyLimiter.Settings.DEFAULTS.withMode(mode), null, ServiceClientMetrics.NONE);
		}

		/**
		 * @see ServiceClientConcurrencyLimiter#acquire(String)
		 */
		@Override
		public TargetLimit acquire(
				final String target) throws IntegrationException {
			this.events.add("acquire");
			return super.acquire(target);
		}

		/**
		 * @see ServiceClientConcurrencyLimiter#recordAttempt(TargetLimit, long,
		 *      boolean, boolean)
		 */
		@Override
		public void recordAttempt(
				final TargetLimit targetLimit,
				final long latencyNanos,
				final boolean isLatencySample,
				final boolean isOverload) {
			this.events.add("attempt " + (isLatencySample ? "sample" : "no-sample") + " " + (isOverload ? "overload" : "normal"));
			super.recordAttempt(targetLimit, latencyNanos, isLatencySample, isOverload);
		}

		/**
		 * @see ServiceClientConcurrencyLimiter#release(TargetLimit)
		 */
		@Override
		public void release(
				final TargetLimit targetLimit) {
			this.events.add("release");
			super.release(targetLimit);
		}

	}

	/**
	 * Event an attempt outcome should record in the limiter.
	 *
	 * @param failure Attempt failure ({@code null} for a success).
	 * @param event   Expected event.
	 */
	private record ExpectedEvent(
			RuntimeException failure,
			String event) {}

	/**
	 * Limiter whose every method fails.
	 */
	private static class FailingLimiter extends ServiceClientConcurrencyLimiter {

		/**
		 * Constructor.
		 *
		 * @param mode Mode.
		 */
		FailingLimiter(final Mode mode) {
			super(ServiceClientConcurrencyLimiter.Settings.DEFAULTS.withMode(mode), null, ServiceClientMetrics.NONE);
		}

		/**
		 * @see ServiceClientConcurrencyLimiter#acquire(String)
		 */
		@Override
		public TargetLimit acquire(
				final String target) throws IntegrationException {
			throw new IllegalStateException("Failing limiter.");
		}

		/**
		 * @see ServiceClientConcurrencyLimiter#recordAttempt(TargetLimit, long,
		 *      boolean, boolean)
		 */
		@Override
		public void recordAttempt(
				final TargetLimit targetLimit,
				final long latencyNanos,
				final boolean isLatencySample,
				final boolean isOverload) {
			throw new IllegalStateException("Failing limiter.");
		}

		/**
		 * @see ServiceClientConcurrencyLimiter#release(TargetLimit)
		 */
		@Override
		public void release(
				final TargetLimit targetLimit) {
			throw new IllegalStateException("Failing limiter.");
		}

	}

	/**
	 * Limiter that gives a permit, then fails when an attempt is recorded and when
	 * the permit is given back.
	 */
	private static class FailingAfterAcquireLimiter extends ServiceClientConcurrencyLimiter {

		/**
		 * Constructor.
		 *
		 * @param mode Mode.
		 */
		FailingAfterAcquireLimiter(final Mode mode) {
			super(ServiceClientConcurrencyLimiter.Settings.DEFAULTS.withMode(mode), null, ServiceClientMetrics.NONE);
		}

		/**
		 * @see ServiceClientConcurrencyLimiter#recordAttempt(TargetLimit, long,
		 *      boolean, boolean)
		 */
		@Override
		public void recordAttempt(
				final TargetLimit targetLimit,
				final long latencyNanos,
				final boolean isLatencySample,
				final boolean isOverload) {
			throw new IllegalStateException("Failing limiter.");
		}

		/**
		 * @see ServiceClientConcurrencyLimiter#release(TargetLimit)
		 */
		@Override
		public void release(
				final TargetLimit targetLimit) {
			throw new IllegalStateException("Failing limiter.");
		}

	}

	/**
	 * Creates an executor (failover on, 3 attempts, GET idempotent, 503 retried,
	 * no budget) with a limiter.
	 *
	 * @param  limiter Limiter.
	 * @return         The executor.
	 */
	private static ServiceClientFailoverExecutor createExecutor(
			final ServiceClientConcurrencyLimiter limiter) {
		return new ServiceClientFailoverExecutor(true, 3, new String[] { "GET" }, new String[] { "503" }, null, false, Duration.ZERO,
				new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, Duration.ZERO),
				new ServiceClientEndpointResolver(Duration.ZERO, ServiceClientMetrics.NONE), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE,
				ServiceClientMetrics.NONE, limiter);
	}

	/**
	 * Creates a template whose every call fails with a failure (or succeeds with
	 * none), recording the URL of each call and the in-flight count of the target
	 * then.
	 *
	 * @param  failure       Failure ({@code null} to succeed).
	 * @param  attemptedUrls Where the URLs are recorded.
	 * @param  limiter       Limiter whose in-flight count is recorded.
	 * @param  inFlights     Where the in-flight counts are recorded.
	 * @return               The template.
	 */
	private static RestTemplate createTemplate(
			final RuntimeException failure,
			final List<String> attemptedUrls,
			final ServiceClientConcurrencyLimiter limiter,
			final List<Integer> inFlights) {
		return new RestTemplate(new SimpleClientHttpRequestFactory()) {

			@Override
			@SuppressWarnings("unchecked")
			public <ResponseType> ResponseEntity<ResponseType> exchange(
					final String url,
					final HttpMethod method,
					final HttpEntity<?> requestEntity,
					final ParameterizedTypeReference<ResponseType> responseType,
					final Map<String, ?> uriVariables) {
				attemptedUrls.add(url);
				limiter.getSnapshot(ServiceClientFailoverExecutorConcurrencyLimitTest.TARGET).ifPresent(snapshot -> inFlights.add(snapshot.inFlight()));
				if (failure != null) {
					throw failure;
				}
				return (ResponseEntity<ResponseType>) ResponseEntity.ok(url);
			}
		};
	}

	/**
	 * Executes a GET of the test target.
	 *
	 * @param  executor  Executor.
	 * @param  endpoints Endpoints.
	 * @param  template  Template.
	 * @return           The outcome.
	 */
	private static ServiceClientFailoverExecutor.Outcome<String> execute(
			final ServiceClientFailoverExecutor executor,
			final List<String> endpoints,
			final RestTemplate template) {
		return ServiceClientTarget.runFor(ServiceClientFailoverExecutorConcurrencyLimitTest.TARGET,
				() -> executor.executeOverEndpoints(endpoints, "/path", HttpMethod.GET, new HttpEntity<>(null),
						ServiceClientFailoverExecutorConcurrencyLimitTest.STRING_RESPONSE, Map.of(), null, null, template));
	}

	/**
	 * An operation holds one permit across its 3 attempts (in flight is 1 at each
	 * of them), released once at the end.
	 */
	@Test
	public void testOnePermitPerOperation() {
		final RecordingLimiter limiter = new RecordingLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE);
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		final List<Integer> inFlights = new CopyOnWriteArrayList<>();
		final ServiceClientFailoverExecutor.Outcome<String> outcome = ServiceClientFailoverExecutorConcurrencyLimitTest.execute(
				ServiceClientFailoverExecutorConcurrencyLimitTest.createExecutor(limiter), List.of("http://first", "http://second", "http://third"),
				ServiceClientFailoverExecutorConcurrencyLimitTest.createTemplate(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE), attemptedUrls,
						limiter, inFlights));
		Assertions.assertNotNull(outcome.failure());
		Assertions.assertEquals(3, attemptedUrls.size());
		Assertions.assertEquals(List.of(1, 1, 1), inFlights);
		Assertions.assertEquals(List.of("acquire", "attempt sample overload", "attempt sample overload", "attempt sample overload", "release"),
				limiter.events);
		Assertions.assertEquals(0, limiter.getSnapshot(ServiceClientFailoverExecutorConcurrencyLimitTest.TARGET).orElseThrow().inFlight());
	}

	/**
	 * What each attempt outcome feeds the limiter: a latency sample when the
	 * target answered (any status) or did not in the read timeout; an overload
	 * outcome for a read timeout, no pooled connection in time, 429 and 503;
	 * nothing for connect failures, a dropped connection or other failures.
	 */
	@Test
	public void testOutcomes() {
		final List<ExpectedEvent> expectedEvents = List.of(new ExpectedEvent(null, "attempt sample normal"),
				new ExpectedEvent(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR), "attempt sample normal"),
				new ExpectedEvent(new HttpClientErrorException(HttpStatus.NOT_FOUND), "attempt sample normal"),
				new ExpectedEvent(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS), "attempt sample overload"),
				new ExpectedEvent(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE), "attempt sample overload"),
				new ExpectedEvent(new ResourceAccessException("Read timeout.", new SocketTimeoutException("Read timed out")), "attempt sample overload"),
				new ExpectedEvent(new ResourceAccessException("Pool.", new ConnectionRequestTimeoutException("Timeout.")), "attempt no-sample overload"),
				new ExpectedEvent(new ResourceAccessException("Refused.", new ConnectException("Refused.")), "attempt no-sample normal"),
				new ExpectedEvent(new ResourceAccessException("Connect timeout.", new ConnectTimeoutException("Connect timed out")), "attempt no-sample normal"),
				new ExpectedEvent(new ResourceAccessException("Unknown host.", new UnknownHostException("host")), "attempt no-sample normal"),
				new ExpectedEvent(new ResourceAccessException("Dropped.", new NoHttpResponseException("Dropped.")), "attempt no-sample normal"),
				new ExpectedEvent(new IllegalStateException("Other."), "attempt no-sample normal"));
		for (final ExpectedEvent expectedEvent : expectedEvents) {
			final RecordingLimiter limiter = new RecordingLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE);
			ServiceClientFailoverExecutorConcurrencyLimitTest.execute(ServiceClientFailoverExecutorConcurrencyLimitTest.createExecutor(limiter),
					List.of("http://only"),
					ServiceClientFailoverExecutorConcurrencyLimitTest.createTemplate(expectedEvent.failure(), new ArrayList<>(), limiter, new ArrayList<>()));
			Assertions.assertEquals(List.of("acquire", expectedEvent.event(), "release"), limiter.events, String.valueOf(expectedEvent.failure()));
		}
	}

	/**
	 * A limiter failing when the permit is taken changes nothing in the call, in
	 * observe and enforce mode: the same response, or the same failure, and the
	 * failures are counted (the operation then holds no permit, so nothing else
	 * reaches the limiter).
	 */
	@Test
	public void testFailingLimiterNeverChangesTheCall() {
		for (final ServiceClientConcurrencyLimiter.Mode mode : List.of(ServiceClientConcurrencyLimiter.Mode.OBSERVE,
				ServiceClientConcurrencyLimiter.Mode.ENFORCE)) {
			final FailingLimiter limiter = new FailingLimiter(mode);
			final ServiceClientFailoverExecutor executor = ServiceClientFailoverExecutorConcurrencyLimitTest.createExecutor(limiter);
			final ServiceClientFailoverExecutor.Outcome<String> success = ServiceClientFailoverExecutorConcurrencyLimitTest.execute(executor,
					List.of("http://only"), ServiceClientFailoverExecutorConcurrencyLimitTest.createTemplate(null, new ArrayList<>(), limiter, new ArrayList<>()));
			Assertions.assertEquals("http://only/path", success.response().getBody());
			final HttpServerErrorException failure = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
			final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
			final ServiceClientFailoverExecutor.Outcome<String> failed = ServiceClientFailoverExecutorConcurrencyLimitTest.execute(executor,
					List.of("http://first", "http://second"),
					ServiceClientFailoverExecutorConcurrencyLimitTest.createTemplate(failure, attemptedUrls, limiter, new ArrayList<>()));
			Assertions.assertSame(failure, failed.failure());
			Assertions.assertEquals(2, attemptedUrls.size());
			Assertions.assertEquals(2, limiter.getErrorsWithoutTarget());
		}
	}

	/**
	 * A limiter that gives a permit, then fails when each attempt is recorded and
	 * when the permit is given back, changes nothing in the call, in observe and
	 * enforce mode: the same response, or the same failure over the same
	 * endpoints; each failure is counted on the target.
	 */
	@Test
	public void testLimiterFailingAfterAcquireNeverChangesTheCall() {
		for (final ServiceClientConcurrencyLimiter.Mode mode : List.of(ServiceClientConcurrencyLimiter.Mode.OBSERVE,
				ServiceClientConcurrencyLimiter.Mode.ENFORCE)) {
			final FailingAfterAcquireLimiter limiter = new FailingAfterAcquireLimiter(mode);
			final ServiceClientFailoverExecutor executor = ServiceClientFailoverExecutorConcurrencyLimitTest.createExecutor(limiter);
			final ServiceClientFailoverExecutor.Outcome<String> success = ServiceClientFailoverExecutorConcurrencyLimitTest.execute(executor,
					List.of("http://only"), ServiceClientFailoverExecutorConcurrencyLimitTest.createTemplate(null, new ArrayList<>(), limiter, new ArrayList<>()));
			Assertions.assertEquals("http://only/path", success.response().getBody());
			Assertions.assertEquals(2, limiter.getSnapshot(ServiceClientFailoverExecutorConcurrencyLimitTest.TARGET).orElseThrow().errors());
			final HttpServerErrorException failure = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
			final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
			final ServiceClientFailoverExecutor.Outcome<String> failed = ServiceClientFailoverExecutorConcurrencyLimitTest.execute(executor,
					List.of("http://first", "http://second"),
					ServiceClientFailoverExecutorConcurrencyLimitTest.createTemplate(failure, attemptedUrls, limiter, new ArrayList<>()));
			Assertions.assertSame(failure, failed.failure());
			Assertions.assertEquals(2, Set.copyOf(attemptedUrls).size());
			// Two attempts recorded and one release, all failing.
			Assertions.assertEquals(5, limiter.getSnapshot(ServiceClientFailoverExecutorConcurrencyLimitTest.TARGET).orElseThrow().errors());
		}
	}

	/**
	 * Creates a limiter on the test clock whose target is overloaded with its
	 * limit (10) in flight: it ran the warm-up at 70 ms, then a window at 4 s.
	 *
	 * @param  mode    Mode.
	 * @param  permits Where the permits held are put.
	 * @return         The limiter.
	 */
	private ServiceClientConcurrencyLimiter createOverloadedLimiter(
			final ServiceClientConcurrencyLimiter.Mode mode,
			final List<ServiceClientConcurrencyLimiter.TargetLimit> permits) {
		final ServiceClientConcurrencyLimiter limiter = new ServiceClientConcurrencyLimiter(ServiceClientConcurrencyLimiter.Settings.DEFAULTS.withMode(mode),
				null, ServiceClientMetrics.NONE, this.clock::get);
		for (int windowIndex = 0; windowIndex <= ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS; windowIndex++) {
			for (int operationIndex = 0; operationIndex < 10; operationIndex++) {
				final ServiceClientConcurrencyLimiter.TargetLimit permit = limiter.acquire(ServiceClientFailoverExecutorConcurrencyLimitTest.TARGET);
				limiter.recordAttempt(permit, TimeUnit.MILLISECONDS.toNanos(windowIndex < ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS ? 70 : 4000), true,
						false);
				if (operationIndex == 9) {
					this.clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
				}
				limiter.release(permit);
			}
		}
		for (int operationIndex = 0; operationIndex < 10; operationIndex++) {
			permits.add(limiter.acquire(ServiceClientFailoverExecutorConcurrencyLimitTest.TARGET));
		}
		final ServiceClientConcurrencyLimiter.Snapshot snapshot = limiter.getSnapshot(ServiceClientFailoverExecutorConcurrencyLimitTest.TARGET).orElseThrow();
		Assertions.assertTrue(snapshot.isOverloaded());
		Assertions.assertEquals(10, snapshot.limit());
		return limiter;
	}

	/**
	 * Enforce, overloaded and at the limit: the operation fails with the 503
	 * rejection before any request (no attempt); observe lets it through.
	 */
	@Test
	public void testEnforceRejectsObserveNever() {
		final List<ServiceClientConcurrencyLimiter.TargetLimit> enforcedPermits = new ArrayList<>();
		final ServiceClientConcurrencyLimiter enforcingLimiter = this.createOverloadedLimiter(ServiceClientConcurrencyLimiter.Mode.ENFORCE, enforcedPermits);
		final List<String> rejectedUrls = new CopyOnWriteArrayList<>();
		final IntegrationException rejection = Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverExecutorConcurrencyLimitTest.execute(ServiceClientFailoverExecutorConcurrencyLimitTest.createExecutor(enforcingLimiter),
						List.of("http://only"), ServiceClientFailoverExecutorConcurrencyLimitTest.createTemplate(null, rejectedUrls, enforcingLimiter, new ArrayList<>())));
		Assertions.assertTrue(ServiceClientConcurrencyLimiter.isRejection(rejection));
		Assertions.assertEquals(503, rejection.getStatusCode());
		Assertions.assertEquals(Duration.ofSeconds(1), rejection.getRetryIn());
		Assertions.assertTrue(rejectedUrls.isEmpty());
		Assertions.assertEquals(10, enforcingLimiter.getSnapshot(ServiceClientFailoverExecutorConcurrencyLimitTest.TARGET).orElseThrow().inFlight());
		enforcedPermits.forEach(enforcingLimiter::release);
		final List<ServiceClientConcurrencyLimiter.TargetLimit> observedPermits = new ArrayList<>();
		final ServiceClientConcurrencyLimiter observingLimiter = this.createOverloadedLimiter(ServiceClientConcurrencyLimiter.Mode.OBSERVE, observedPermits);
		final List<String> observedUrls = new CopyOnWriteArrayList<>();
		final ServiceClientFailoverExecutor.Outcome<String> outcome = ServiceClientFailoverExecutorConcurrencyLimitTest.execute(
				ServiceClientFailoverExecutorConcurrencyLimitTest.createExecutor(observingLimiter), List.of("http://only"),
				ServiceClientFailoverExecutorConcurrencyLimitTest.createTemplate(null, observedUrls, observingLimiter, new ArrayList<>()));
		Assertions.assertNull(outcome.failure());
		Assertions.assertEquals(1, observedUrls.size());
		Assertions.assertEquals(1, observingLimiter.getSnapshot(ServiceClientFailoverExecutorConcurrencyLimitTest.TARGET).orElseThrow().overLimitCalls());
		observedPermits.forEach(observingLimiter::release);
	}

}
