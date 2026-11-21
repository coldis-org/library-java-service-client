package org.coldis.library.test.service.client;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.client.MicrometerServiceClientMetrics;
import org.coldis.library.service.client.ServiceClientConcurrencyLimiter;
import org.coldis.library.service.client.ServiceClientEndpointBalancer;
import org.coldis.library.service.client.ServiceClientFailoverExecutor;
import org.coldis.library.service.client.ServiceClientTarget;
import org.coldis.library.service.client.ServiceClientTargetObservationFilter;
import org.coldis.library.test.service.AbstractServiceTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * Metrics of the service client: the target tag of the HTTP client requests,
 * and the failover and balancing meters.
 */
public class ServiceClientMetricsTest extends AbstractServiceTest {

	/**
	 * Target of the generated client.
	 */
	private static final String TARGET = "TestService2Client";

	/**
	 * How many calls are made (the chance that none starts on the refusing
	 * endpoint of two is 1 in 2^20).
	 */
	private static final Integer REPEATED_CALLS = 20;

	/**
	 * Meter registry.
	 */
	@Autowired
	private MeterRegistry meterRegistry;

	/**
	 * Service client (library default template).
	 */
	@Autowired
	@Qualifier(value = "restServiceClient")
	private GenericRestServiceClient serviceClient;

	/**
	 * Endpoint balancer of the context (used by the generated clients).
	 */
	@Autowired
	private ServiceClientEndpointBalancer contextBalancer;

	/**
	 * Concurrency limiter of the context.
	 */
	@Autowired
	private ServiceClientConcurrencyLimiter concurrencyLimiter;

	/**
	 * Failover executor of the context.
	 */
	@Autowired
	private ServiceClientFailoverExecutor failoverExecutor;

	/**
	 * Generated client endpoints.
	 */
	@Autowired
	private TestService2Properties testService2Properties;

	/**
	 * Generated client.
	 */
	@Autowired
	private TestService2Client testService2Client;

	/**
	 * Port of the local server.
	 */
	@LocalServerPort
	private Integer localServerPort;

	/**
	 * Original generated client endpoint.
	 */
	private String originalTestService2Endpoint;

	/**
	 * Keeps the generated client endpoint.
	 */
	@Override
	@BeforeEach
	public void beforeEach() {
		super.beforeEach();
		this.originalTestService2Endpoint = this.testService2Properties.getEndpoint();
	}

	/**
	 * Restores the generated client endpoint.
	 */
	@AfterEach
	public void restoreEndpoint() {
		this.testService2Properties.setEndpoint(this.originalTestService2Endpoint);
	}

	/**
	 * Gets the count of the HTTP client requests of a target.
	 *
	 * @param  target Target.
	 * @return        The count of the HTTP client requests of the target.
	 */
	private long countHttpClientRequests(
			final String target) {
		return this.meterRegistry.find("http.client.requests").tag(ServiceClientTargetObservationFilter.TARGET_KEY, target).timers().stream()
				.mapToLong(Timer::count).sum();
	}

	/**
	 * Gets the count of a counter (zero if it does not exist yet).
	 *
	 * @param  name     Counter name.
	 * @param  tagKey   Second tag key (after the target).
	 * @param  tagValue Second tag value ({@code null} for none).
	 * @return          The count of the counter.
	 */
	private double count(
			final String name,
			final String tagKey,
			final String tagValue) {
		final Counter counter = (tagValue == null ? this.meterRegistry.find(name).tag("target", ServiceClientMetricsTest.TARGET).counter()
				: this.meterRegistry.find(name).tag("target", ServiceClientMetricsTest.TARGET).tag(tagKey, tagValue).counter());
		return (counter == null ? 0 : counter.count());
	}

	/**
	 * A generated client's calls are recorded in {@code http.client.requests}
	 * with the client's name as target; a full-URL call with target
	 * {@value ServiceClientTarget#NONE}.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testHttpClientRequestsTaggedWithTarget() throws Exception {
		final long targetRequests = this.countHttpClientRequests(ServiceClientMetricsTest.TARGET);
		final long noTargetRequests = this.countHttpClientRequests(ServiceClientTarget.NONE);
		this.testService2Client.test1();
		Assertions.assertEquals(targetRequests + 1, this.countHttpClientRequests(ServiceClientMetricsTest.TARGET));
		Assertions.assertEquals(noTargetRequests, this.countHttpClientRequests(ServiceClientTarget.NONE));
		this.serviceClient.executeOperation("http://localhost:" + this.localServerPort + "/test2", HttpMethod.GET, null, null, null,
				new ParameterizedTypeReference<Void>() {});
		Assertions.assertEquals(targetRequests + 1, this.countHttpClientRequests(ServiceClientMetricsTest.TARGET));
		Assertions.assertEquals(noTargetRequests + 1, this.countHttpClientRequests(ServiceClientTarget.NONE));
		Assertions.assertEquals(ServiceClientTarget.NONE, ServiceClientTarget.getCurrent());
	}

	/**
	 * A generated client failing over a refused endpoint records its attempts
	 * (successful and not connected), its retries and the cooldown of the refused
	 * endpoint, under its target; no request is left in flight.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testFailoverMetrics() throws Exception {
		final String refusedEndpoint;
		try (ServerSocket closedSocket = new ServerSocket(0)) {
			refusedEndpoint = "http://localhost:" + closedSocket.getLocalPort();
		}
		this.testService2Properties.setEndpoint(refusedEndpoint + ",http://localhost:${local.server.port}");
		final double successes = this.count(MicrometerServiceClientMetrics.ATTEMPTS, "outcome", "success");
		final double notConnected = this.count(MicrometerServiceClientMetrics.ATTEMPTS, "outcome", "not-connected");
		final double retries = this.count(MicrometerServiceClientMetrics.RETRIES, "reason", "not-connected");
		final double cooldowns = this.count(MicrometerServiceClientMetrics.COOLDOWNS, null, null);
		// The balancer cleared before each call, so the refused endpoint is chosen
		// first about half of the time.
		for (Integer callIndex = 0; callIndex < ServiceClientMetricsTest.REPEATED_CALLS; callIndex++) {
			this.contextBalancer.clear();
			this.testService2Client.test1();
		}
		final double newNotConnected = this.count(MicrometerServiceClientMetrics.ATTEMPTS, "outcome", "not-connected") - notConnected;
		Assertions.assertEquals(successes + ServiceClientMetricsTest.REPEATED_CALLS, this.count(MicrometerServiceClientMetrics.ATTEMPTS, "outcome", "success"));
		Assertions.assertTrue(newNotConnected > 0);
		Assertions.assertEquals(retries + newNotConnected, this.count(MicrometerServiceClientMetrics.RETRIES, "reason", "not-connected"));
		Assertions.assertEquals(cooldowns + newNotConnected, this.count(MicrometerServiceClientMetrics.COOLDOWNS, null, null));
		final Gauge inFlight = this.meterRegistry.find(MicrometerServiceClientMetrics.IN_FLIGHT).tag("target", ServiceClientMetricsTest.TARGET).gauge();
		Assertions.assertNotNull(inFlight);
		Assertions.assertEquals(0, inFlight.value());
	}

	/**
	 * The concurrency limit of the context observes by default: a generated
	 * client's calls get its meters (tagged with the target only, and the result),
	 * not enforced, nothing left in flight. A target with its own mode in the
	 * environment enforces it.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testConcurrencyLimitMeters() throws Exception {
		Assertions.assertSame(this.concurrencyLimiter, this.failoverExecutor.getConcurrencyLimiter());
		Assertions.assertEquals(ServiceClientConcurrencyLimiter.Mode.OBSERVE, this.concurrencyLimiter.getSettings().mode());
		this.testService2Client.test1();
		final Gauge limit = this.meterRegistry.find(MicrometerServiceClientMetrics.LIMIT).tag("target", ServiceClientMetricsTest.TARGET).gauge();
		Assertions.assertNotNull(limit);
		Assertions.assertTrue(limit.value() >= this.concurrencyLimiter.getSettings().min());
		Assertions.assertEquals(0, this.meterRegistry.find(MicrometerServiceClientMetrics.LIMIT_ENFORCED).tag("target", ServiceClientMetricsTest.TARGET)
				.gauge().value());
		Assertions.assertEquals(0, this.meterRegistry.find(MicrometerServiceClientMetrics.LIMIT_IN_FLIGHT).tag("target", ServiceClientMetricsTest.TARGET)
				.gauge().value());
		for (final String name : List.of(MicrometerServiceClientMetrics.LIMIT_IN_FLIGHT_PEAK, MicrometerServiceClientMetrics.LIMIT_OVERLOADED_SECONDS,
				MicrometerServiceClientMetrics.LIMIT_LATENCY_BASELINE, MicrometerServiceClientMetrics.LIMIT_LATENCY_CURRENT,
				MicrometerServiceClientMetrics.LIMIT_SUGGESTED, MicrometerServiceClientMetrics.LIMIT_ERRORS)) {
			Assertions.assertNotNull(this.meterRegistry.find(name).tag("target", ServiceClientMetricsTest.TARGET).meter(), name);
		}
		Assertions.assertEquals(2, this.meterRegistry.find(MicrometerServiceClientMetrics.LIMIT_CALLS).tag("target", ServiceClientMetricsTest.TARGET)
				.meters().size());
		this.meterRegistry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith(MicrometerServiceClientMetrics.LIMIT))
				.forEach(meter -> Assertions.assertTrue(Set.of("target", "result").containsAll(
						meter.getId().getTags().stream().map(Tag::getKey).toList()), meter.getId()::toString));
		final String enforcedTarget = "ConcurrencyLimitEnforcedTestClient";
		this.concurrencyLimiter.release(this.concurrencyLimiter.acquire(enforcedTarget));
		Assertions.assertEquals(ServiceClientConcurrencyLimiter.Mode.ENFORCE, this.concurrencyLimiter.getSnapshot(enforcedTarget).orElseThrow().mode());
		Assertions.assertEquals(1, this.meterRegistry.find(MicrometerServiceClientMetrics.LIMIT_ENFORCED).tag("target", enforcedTarget).gauge().value());
	}

	/**
	 * Runs operations of a target through the context limiter, each with one
	 * attempt of a latency (no request).
	 *
	 * @param target        Target.
	 * @param operations    Operations.
	 * @param latencyMillis Latency of each attempt (milliseconds).
	 */
	private void runLimiterOperations(
			final String target,
			final int operations,
			final long latencyMillis) {
		for (int operationIndex = 0; operationIndex < operations; operationIndex++) {
			final ServiceClientConcurrencyLimiter.Permit permit = this.concurrencyLimiter.acquire(target);
			this.concurrencyLimiter.recordAttempt(permit, TimeUnit.MILLISECONDS.toNanos(latencyMillis), true, false);
			this.concurrencyLimiter.release(permit);
		}
	}

	/**
	 * A target set to enforce in the environment, overloaded and at its limit:
	 * a call through the generic client (as a generated client makes it) gets the
	 * rejection as is (code, status 503, retry in 1 s), and no request is sent.
	 * The overload is fed to the context limiter directly (11 windows of 1 s of
	 * real time): a real slow target would take as long and add nothing the plain
	 * executor tests do not cover.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testEnforcedTargetRejectsTheCall() throws Exception {
		final String enforcedTarget = "ConcurrencyLimitEnforcedTestClient";
		final long windowMillis = this.concurrencyLimiter.getSettings().window().toMillis() + 50;
		for (int windowIndex = 0; windowIndex <= ServiceClientConcurrencyLimiter.WARM_UP_WINDOWS; windowIndex++) {
			this.runLimiterOperations(enforcedTarget, 10, 70);
			Thread.sleep(windowMillis);
		}
		this.runLimiterOperations(enforcedTarget, 10, 4000);
		Thread.sleep(windowMillis);
		this.runLimiterOperations(enforcedTarget, 1, 4000);
		final ServiceClientConcurrencyLimiter.Snapshot snapshot = this.concurrencyLimiter.getSnapshot(enforcedTarget).orElseThrow();
		Assertions.assertTrue(snapshot.isOverloaded());
		final List<ServiceClientConcurrencyLimiter.Permit> permits = new ArrayList<>();
		try {
			for (int operationIndex = 0; operationIndex < snapshot.limit(); operationIndex++) {
				permits.add(this.concurrencyLimiter.acquire(enforcedTarget));
			}
			final long requests = this.countHttpClientRequests(enforcedTarget);
			final IntegrationException rejection = Assertions.assertThrows(IntegrationException.class,
					() -> this.serviceClient.executeOperation(List.of("http://localhost:" + this.localServerPort), "/test2", HttpMethod.GET, null, null, null,
							new ParameterizedTypeReference<Void>() {}, null, null, enforcedTarget));
			Assertions.assertEquals(ServiceClientConcurrencyLimiter.REJECTED_CODE, rejection.getInternalMessage().getCode());
			Assertions.assertEquals(503, rejection.getStatusCode());
			Assertions.assertEquals(Duration.ofSeconds(1), rejection.getRetryIn());
			Assertions.assertEquals(requests, this.countHttpClientRequests(enforcedTarget));
		}
		finally {
			permits.forEach(this.concurrencyLimiter::release);
		}
	}

}
