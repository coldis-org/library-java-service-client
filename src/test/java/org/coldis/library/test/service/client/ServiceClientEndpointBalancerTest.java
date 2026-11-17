package org.coldis.library.test.service.client;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.coldis.library.service.client.ServiceClientEndpointBalancer;
import org.coldis.library.service.client.ServiceClientEndpointBalancer.InFlightRequest;
import org.coldis.library.service.client.ServiceClientEndpointBalancer.Strategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Endpoint balancer test (plain objects: the balancer has no Spring dependency).
 */
public class ServiceClientEndpointBalancerTest {

	/**
	 * Endpoints.
	 */
	private static final List<String> ENDPOINTS = List.of("http://first", "http://second", "http://third");

	/**
	 * Number of choices made per check.
	 */
	private static final Integer CHOICES = 3000;

	/**
	 * Counts the choices per endpoint.
	 *
	 * @param  balancer       Balancer.
	 * @param  endpoints      Endpoints.
	 * @param  triedEndpoints Endpoints already tried.
	 * @return                The choices per endpoint.
	 */
	private static Map<String, Integer> countChoices(
			final ServiceClientEndpointBalancer balancer,
			final List<String> endpoints,
			final List<String> triedEndpoints) {
		final Map<String, Integer> choices = new HashMap<>();
		for (Integer choiceIndex = 0; choiceIndex < ServiceClientEndpointBalancerTest.CHOICES; choiceIndex++) {
			choices.merge(balancer.choose(endpoints, triedEndpoints), 1, Integer::sum);
		}
		return choices;
	}

	/**
	 * The endpoint with fewer requests in flight wins; once its requests finish,
	 * both are chosen again.
	 */
	@Test
	public void testFewerInFlightWins() {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(Strategy.LEAST_IN_FLIGHT, Duration.ofSeconds(10));
		final List<String> endpoints = List.of("http://busy", "http://idle");
		final InFlightRequest firstBusyRequest = balancer.startRequest("http://busy");
		final InFlightRequest secondBusyRequest = balancer.startRequest("http://busy");
		Assertions.assertEquals(Map.of("http://idle", ServiceClientEndpointBalancerTest.CHOICES),
				ServiceClientEndpointBalancerTest.countChoices(balancer, endpoints, List.of()));
		firstBusyRequest.close();
		// Closing twice ends the request once.
		firstBusyRequest.close();
		Assertions.assertEquals(1, balancer.getInFlightRequests("http://busy"));
		secondBusyRequest.close();
		Assertions.assertEquals(0, balancer.getInFlightRequests("http://busy"));
		Assertions.assertEquals(Set.of("http://busy", "http://idle"), ServiceClientEndpointBalancerTest.countChoices(balancer, endpoints, List.of()).keySet());
	}

	/**
	 * An endpoint that failed is skipped during the cooldown, chosen again after
	 * it, and right away once it answers a request sent after it failed.
	 *
	 * @throws InterruptedException If the test is interrupted.
	 */
	@Test
	public void testFailedEndpointCooldown() throws InterruptedException {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(Strategy.LEAST_IN_FLIGHT, Duration.ofSeconds(1));
		balancer.startCooldown("http://first");
		Assertions.assertFalse(
				ServiceClientEndpointBalancerTest.countChoices(balancer, ServiceClientEndpointBalancerTest.ENDPOINTS, List.of()).containsKey("http://first"));
		Thread.sleep(1200);
		Assertions.assertTrue(
				ServiceClientEndpointBalancerTest.countChoices(balancer, ServiceClientEndpointBalancerTest.ENDPOINTS, List.of()).containsKey("http://first"));
		// An answer to a request sent before the failure leaves the cooldown; an
		// answer to a request sent after it ends the cooldown.
		final InFlightRequest olderRequest = balancer.startRequest("http://second");
		Thread.sleep(2);
		balancer.startCooldown("http://second");
		balancer.endCooldown(olderRequest);
		olderRequest.close();
		Assertions.assertTrue(balancer.isCoolingDown("http://second"));
		Thread.sleep(2);
		final InFlightRequest newerRequest = balancer.startRequest("http://second");
		balancer.endCooldown(newerRequest);
		newerRequest.close();
		Assertions.assertFalse(balancer.isCoolingDown("http://second"));
		Assertions.assertTrue(
				ServiceClientEndpointBalancerTest.countChoices(balancer, ServiceClientEndpointBalancerTest.ENDPOINTS, List.of()).containsKey("http://second"));
	}

	/**
	 * When every endpoint is in its cooldown, the cooldown is ignored.
	 */
	@Test
	public void testAllEndpointsFailedFallsBackToAll() {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(Strategy.LEAST_IN_FLIGHT, Duration.ofSeconds(10));
		ServiceClientEndpointBalancerTest.ENDPOINTS.forEach(balancer::startCooldown);
		Assertions.assertEquals(Set.copyOf(ServiceClientEndpointBalancerTest.ENDPOINTS),
				ServiceClientEndpointBalancerTest.countChoices(balancer, ServiceClientEndpointBalancerTest.ENDPOINTS, List.of()).keySet());
	}

	/**
	 * A failover attempt goes to an endpoint not tried yet, even one in its
	 * cooldown; when every endpoint was tried, any of them out of their cooldown.
	 */
	@Test
	public void testTriedEndpointsExcluded() {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(Strategy.LEAST_IN_FLIGHT, Duration.ofSeconds(10));
		balancer.startCooldown("http://third");
		Assertions.assertEquals(Map.of("http://third", ServiceClientEndpointBalancerTest.CHOICES), ServiceClientEndpointBalancerTest.countChoices(balancer,
				ServiceClientEndpointBalancerTest.ENDPOINTS, List.of("http://first", "http://second")));
		Assertions.assertEquals(Set.of("http://first", "http://second"), ServiceClientEndpointBalancerTest
				.countChoices(balancer, ServiceClientEndpointBalancerTest.ENDPOINTS, ServiceClientEndpointBalancerTest.ENDPOINTS).keySet());
	}

	/**
	 * The random strategy is uniform and ignores requests in flight and failures,
	 * as before.
	 */
	@Test
	public void testRandomStrategy() {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(Strategy.fromPropertyValue("random"), Duration.ofSeconds(10));
		balancer.startCooldown("http://first");
		balancer.startRequest("http://second");
		final Map<String, Integer> choices = ServiceClientEndpointBalancerTest.countChoices(balancer, ServiceClientEndpointBalancerTest.ENDPOINTS, List.of());
		for (final String endpoint : ServiceClientEndpointBalancerTest.ENDPOINTS) {
			final Integer endpointChoices = choices.getOrDefault(endpoint, 0);
			Assertions.assertTrue((endpointChoices > 850) && (endpointChoices < 1150), () -> endpoint + " chosen " + endpointChoices + " times");
		}
	}

	/**
	 * Idle counters are pruned past the counter limit, a counter with a request in
	 * flight is kept, and a request finishes on the counter it started on.
	 */
	@Test
	public void testIdleCountersPruned() {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(Strategy.LEAST_IN_FLIGHT, Duration.ofSeconds(10));
		final InFlightRequest busyRequest = balancer.startRequest("http://busy");
		for (Integer endpointIndex = 0; endpointIndex < 300; endpointIndex++) {
			balancer.startRequest("http://idle" + endpointIndex).close();
		}
		Assertions.assertTrue(balancer.getCountedEndpointsCount() <= 258, () -> "Counters: " + balancer.getCountedEndpointsCount());
		balancer.startCooldown("http://other");
		Assertions.assertEquals(1, balancer.getInFlightRequests("http://busy"));
		Assertions.assertEquals(1, balancer.getCountedEndpointsCount());
		busyRequest.close();
		Assertions.assertEquals(0, balancer.getInFlightRequests("http://busy"));
	}

	/**
	 * Expired cooldowns are pruned when another endpoint fails, and clearing forgets
	 * every counter and cooldown.
	 *
	 * @throws InterruptedException If the test is interrupted.
	 */
	@Test
	public void testExpiredCooldownsPrunedAndClear() throws InterruptedException {
		final ServiceClientEndpointBalancer balancer = new ServiceClientEndpointBalancer(Strategy.LEAST_IN_FLIGHT, Duration.ofMillis(100));
		balancer.startCooldown("http://first");
		Thread.sleep(200);
		balancer.startCooldown("http://second");
		Assertions.assertEquals(1, balancer.getCooldownEndpointsCount());
		balancer.startRequest("http://third");
		balancer.clear();
		Assertions.assertEquals(0, balancer.getCooldownEndpointsCount());
		Assertions.assertEquals(0, balancer.getCountedEndpointsCount());
	}

}
