package org.coldis.library.test.service.client;

import java.net.ServerSocket;

import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.client.MicrometerServiceClientMetrics;
import org.coldis.library.service.client.ServiceClientEndpointBalancer;
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

}
