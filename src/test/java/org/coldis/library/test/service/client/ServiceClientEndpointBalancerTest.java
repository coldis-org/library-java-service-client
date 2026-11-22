package org.coldis.library.test.service.client;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.coldis.library.service.client.ServiceClientEndpointBalancer;
import org.coldis.library.service.client.ServiceClientEndpointBalancer.CooldownReason;
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

	/**
	 * Creates a balancer (least in flight, base cooldown 10 s, maximum 40 s) on a
	 * test clock.
	 *
	 * @param  clock Test clock (nanoseconds).
	 * @return       The balancer.
	 */
	private static ServiceClientEndpointBalancer createClockedBalancer(
			final AtomicLong clock) {
		return new ServiceClientEndpointBalancer(Strategy.LEAST_IN_FLIGHT, Duration.ofSeconds(10), Duration.ofSeconds(40), clock::get);
	}

	/**
	 * Fails a request to an endpoint, started now (the request ends first).
	 *
	 * @param  balancer Balancer.
	 * @param  endpoint Endpoint.
	 * @param  reason   Why the cooldown starts.
	 * @return          If the request was the endpoint's probe.
	 */
	private static boolean failRequest(
			final ServiceClientEndpointBalancer balancer,
			final String endpoint,
			final CooldownReason reason) {
		final InFlightRequest request = balancer.startRequest(endpoint);
		request.close();
		balancer.startCooldown(request, reason);
		return request.isProbe();
	}

	/**
	 * Advances a test clock.
	 *
	 * @param clock   Test clock (nanoseconds).
	 * @param advance How much.
	 */
	private static void advance(
			final AtomicLong clock,
			final Duration advance) {
		clock.addAndGet(advance.toNanos());
	}

	/**
	 * An endpoint that fails again on its first request after its cooldown ended
	 * gets twice the cooldown, up to the maximum; an answer resets it to the base.
	 * A failure during the cooldown, or of a request sent before it ended, does
	 * not grow it.
	 */
	@Test
	public void testCooldownGrowsAndResets() {
		final AtomicLong clock = new AtomicLong(1_000_000_000L);
		final ServiceClientEndpointBalancer balancer = ServiceClientEndpointBalancerTest.createClockedBalancer(clock);
		Assertions.assertFalse(ServiceClientEndpointBalancerTest.failRequest(balancer, "http://first", CooldownReason.NOT_CONNECTED));
		Assertions.assertEquals(Duration.ofSeconds(10), balancer.getCooldownDuration("http://first"));
		// During the cooldown, another failure restarts it, no longer.
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(5));
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://first", CooldownReason.NOT_CONNECTED);
		Assertions.assertEquals(Duration.ofSeconds(10), balancer.getCooldownDuration("http://first"));
		// A request sent before the cooldown ended that fails after it does not grow it.
		final InFlightRequest olderRequest = balancer.startRequest("http://first");
		Assertions.assertFalse(olderRequest.isProbe());
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(10));
		olderRequest.close();
		balancer.startCooldown(olderRequest, CooldownReason.NOT_CONNECTED);
		Assertions.assertEquals(Duration.ofSeconds(10), balancer.getCooldownDuration("http://first"));
		// Failing as soon as it comes back: 20 s, 40 s, then the maximum.
		for (final Duration expectedCooldown : List.of(Duration.ofSeconds(20), Duration.ofSeconds(40), Duration.ofSeconds(40))) {
			ServiceClientEndpointBalancerTest.advance(clock, balancer.getCooldownDuration("http://first"));
			Assertions.assertFalse(balancer.isCoolingDown("http://first"));
			Assertions.assertTrue(ServiceClientEndpointBalancerTest.failRequest(balancer, "http://first", CooldownReason.NOT_CONNECTED));
			Assertions.assertEquals(expectedCooldown, balancer.getCooldownDuration("http://first"));
			Assertions.assertTrue(balancer.isCoolingDown("http://first"));
		}
		// An answer after it ends (the probe's) brings it fully back: the next
		// cooldown starts at the base.
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(40));
		final InFlightRequest probe = balancer.startRequest("http://first");
		Assertions.assertTrue(probe.isProbe());
		probe.close();
		balancer.endCooldown(probe);
		Assertions.assertNull(balancer.getCooldownDuration("http://first"));
		Assertions.assertFalse(balancer.isSkipped("http://first"));
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://first", CooldownReason.NOT_CONNECTED);
		Assertions.assertEquals(Duration.ofSeconds(10), balancer.getCooldownDuration("http://first"));
		// A maximum equal to the base disables growth.
		final ServiceClientEndpointBalancer fixedBalancer = new ServiceClientEndpointBalancer(Strategy.LEAST_IN_FLIGHT, Duration.ofSeconds(10),
				Duration.ofSeconds(10), clock::get);
		ServiceClientEndpointBalancerTest.failRequest(fixedBalancer, "http://first", CooldownReason.NOT_CONNECTED);
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(10));
		Assertions.assertTrue(ServiceClientEndpointBalancerTest.failRequest(fixedBalancer, "http://first", CooldownReason.NOT_CONNECTED));
		Assertions.assertEquals(Duration.ofSeconds(10), fixedBalancer.getCooldownDuration("http://first"));
		Assertions.assertEquals(Duration.ofSeconds(10), fixedBalancer.getFailureCooldownMax());
	}

	/**
	 * Once its cooldown ends, a single request at a time goes to an endpoint (the
	 * probe): others skip it until the probe ends, in any way. A probe out for
	 * longer than the cooldown no longer holds it, and its late end does not free
	 * the next probe's slot.
	 */
	@Test
	public void testSingleProbeAfterCooldown() {
		final AtomicLong clock = new AtomicLong(1_000_000_000L);
		final ServiceClientEndpointBalancer balancer = ServiceClientEndpointBalancerTest.createClockedBalancer(clock);
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://first", CooldownReason.CONNECT_TIMEOUT);
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(10));
		Assertions.assertFalse(balancer.isCoolingDown("http://first"));
		Assertions.assertFalse(balancer.isSkipped("http://first"));
		// The first request is the probe; while it is out, the endpoint is skipped.
		final InFlightRequest probe = balancer.chooseAndStartRequest(ServiceClientEndpointBalancerTest.ENDPOINTS.subList(0, 1), List.of());
		Assertions.assertEquals("http://first", probe.getEndpoint());
		Assertions.assertTrue(probe.isProbe());
		Assertions.assertTrue(balancer.isSkipped("http://first"));
		Assertions.assertFalse(
				ServiceClientEndpointBalancerTest.countChoices(balancer, ServiceClientEndpointBalancerTest.ENDPOINTS, List.of()).containsKey("http://first"));
		// A request started on it anyway (every endpoint skipped) is no probe.
		final InFlightRequest otherRequest = balancer.chooseAndStartRequest(ServiceClientEndpointBalancerTest.ENDPOINTS.subList(0, 1), List.of());
		Assertions.assertEquals("http://first", otherRequest.getEndpoint());
		Assertions.assertFalse(otherRequest.isProbe());
		otherRequest.close();
		Assertions.assertTrue(balancer.isSkipped("http://first"));
		// The probe ends with no outcome (a read timeout, say): the next request probes.
		probe.close();
		Assertions.assertFalse(balancer.isSkipped("http://first"));
		Assertions.assertTrue(
				ServiceClientEndpointBalancerTest.countChoices(balancer, ServiceClientEndpointBalancerTest.ENDPOINTS, List.of()).containsKey("http://first"));
		final InFlightRequest hungProbe = balancer.startRequest("http://first");
		Assertions.assertTrue(hungProbe.isProbe());
		// A probe out for as long as the cooldown no longer holds the endpoint.
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(9));
		Assertions.assertTrue(balancer.isSkipped("http://first"));
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(1));
		Assertions.assertFalse(balancer.isSkipped("http://first"));
		final InFlightRequest nextProbe = balancer.startRequest("http://first");
		Assertions.assertTrue(nextProbe.isProbe());
		hungProbe.close();
		Assertions.assertTrue(balancer.isSkipped("http://first"));
		// The probe fails: the next cooldown (twice as long) starts.
		nextProbe.close();
		balancer.startCooldown(nextProbe, CooldownReason.CONNECT_TIMEOUT);
		Assertions.assertTrue(balancer.isCoolingDown("http://first"));
		Assertions.assertEquals(Duration.ofSeconds(20), balancer.getCooldownDuration("http://first"));
		Assertions.assertEquals(0, balancer.getInFlightRequests("http://first"));
	}

	/**
	 * When every endpoint is cooling down or probed, all of them are candidates
	 * again: a request always gets an endpoint.
	 */
	@Test
	public void testAllCoolingDownOrProbedFallsBackToAll() {
		final AtomicLong clock = new AtomicLong(1_000_000_000L);
		final ServiceClientEndpointBalancer balancer = ServiceClientEndpointBalancerTest.createClockedBalancer(clock);
		final List<String> endpoints = List.of("http://first", "http://second");
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://first", CooldownReason.CONNECT_TIMEOUT);
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(10));
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://second", CooldownReason.NOT_CONNECTED);
		final InFlightRequest probe = balancer.startRequest("http://first");
		Assertions.assertTrue(probe.isProbe());
		// As many requests in flight on each, so both are chosen.
		final InFlightRequest secondRequest = balancer.startRequest("http://second");
		Assertions.assertEquals(Set.copyOf(endpoints), ServiceClientEndpointBalancerTest.countChoices(balancer, endpoints, List.of()).keySet());
		for (Integer requestIndex = 0; requestIndex < 100; requestIndex++) {
			final InFlightRequest request = balancer.chooseAndStartRequest(endpoints, List.of());
			Assertions.assertFalse(request.isProbe());
			request.close();
		}
		probe.close();
		secondRequest.close();
	}

	/**
	 * Every endpoint of a list is skipped after a connect timeout only when each
	 * one is cooling down (or probed) after a connect timeout: a refused one, one
	 * with no cooldown, or one back with no probe out, and the answer is no.
	 */
	@Test
	public void testEverySkippedAfterConnectTimeout() {
		final AtomicLong clock = new AtomicLong(1_000_000_000L);
		final ServiceClientEndpointBalancer balancer = ServiceClientEndpointBalancerTest.createClockedBalancer(clock);
		final List<String> endpoints = List.of("http://first", "http://second");
		Assertions.assertFalse(balancer.isEverySkippedAfterConnectTimeout(endpoints));
		Assertions.assertFalse(balancer.isEverySkippedAfterConnectTimeout(List.of()));
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://first", CooldownReason.CONNECT_TIMEOUT);
		Assertions.assertFalse(balancer.isEverySkippedAfterConnectTimeout(endpoints));
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://second", CooldownReason.NOT_CONNECTED);
		Assertions.assertFalse(balancer.isEverySkippedAfterConnectTimeout(endpoints));
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://second", CooldownReason.CONNECT_TIMEOUT);
		Assertions.assertTrue(balancer.isEverySkippedAfterConnectTimeout(endpoints));
		// Back after the cooldown: a probe out keeps it skipped, none does not.
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(10));
		Assertions.assertFalse(balancer.isEverySkippedAfterConnectTimeout(endpoints));
		final InFlightRequest probe = balancer.startRequest("http://first");
		Assertions.assertFalse(balancer.isEverySkippedAfterConnectTimeout(endpoints));
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://second", CooldownReason.CONNECT_TIMEOUT);
		Assertions.assertTrue(balancer.isEverySkippedAfterConnectTimeout(endpoints));
		probe.close();
		Assertions.assertFalse(balancer.isEverySkippedAfterConnectTimeout(endpoints));
		// A cooldown status is no connect timeout either.
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://first", CooldownReason.STATUS);
		Assertions.assertFalse(balancer.isEverySkippedAfterConnectTimeout(endpoints));
		// The random strategy keeps no cooldown.
		final ServiceClientEndpointBalancer randomBalancer = new ServiceClientEndpointBalancer(Strategy.RANDOM, Duration.ofSeconds(10));
		ServiceClientEndpointBalancerTest.failRequest(randomBalancer, "http://first", CooldownReason.CONNECT_TIMEOUT);
		Assertions.assertFalse(randomBalancer.isEverySkippedAfterConnectTimeout(List.of("http://first")));
	}

	/**
	 * A cooldown that ended the maximum cooldown ago with no outcome is forgotten:
	 * the endpoint needs no probe, its next cooldown starts at the base, and the
	 * cooldown is pruned on the next failure.
	 */
	@Test
	public void testEndedCooldownForgotten() {
		final AtomicLong clock = new AtomicLong(1_000_000_000L);
		final ServiceClientEndpointBalancer balancer = ServiceClientEndpointBalancerTest.createClockedBalancer(clock);
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://first", CooldownReason.NOT_CONNECTED);
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://second", CooldownReason.NOT_CONNECTED);
		// Ended less than the maximum cooldown ago: not forgotten yet.
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(49));
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://third", CooldownReason.NOT_CONNECTED);
		Assertions.assertEquals(3, balancer.getCooldownEndpointsCount());
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(1));
		// Forgotten: no probe, base cooldown, pruned.
		final InFlightRequest request = balancer.startRequest("http://first");
		Assertions.assertFalse(request.isProbe());
		request.close();
		balancer.startCooldown(request, CooldownReason.NOT_CONNECTED);
		Assertions.assertEquals(Duration.ofSeconds(10), balancer.getCooldownDuration("http://first"));
		Assertions.assertNull(balancer.getCooldownDuration("http://second"));
		Assertions.assertEquals(2, balancer.getCooldownEndpointsCount());
	}

	/**
	 * A probe's outcome recorded before it ends (as the failover executor does)
	 * leaves no moment with the endpoint neither cooling down nor held: a failed
	 * probe's new cooldown starts while it holds the slot, and its end then frees
	 * nothing; an answered probe's end frees the slot of a cooldown already gone.
	 */
	@Test
	public void testProbeOutcomeRecordedBeforeItEnds() {
		final AtomicLong clock = new AtomicLong(1_000_000_000L);
		final ServiceClientEndpointBalancer balancer = ServiceClientEndpointBalancerTest.createClockedBalancer(clock);
		ServiceClientEndpointBalancerTest.failRequest(balancer, "http://first", CooldownReason.CONNECT_TIMEOUT);
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(10));
		final InFlightRequest failedProbe = balancer.startRequest("http://first");
		Assertions.assertTrue(failedProbe.isProbe());
		balancer.startCooldown(failedProbe, CooldownReason.CONNECT_TIMEOUT);
		Assertions.assertTrue(balancer.isCoolingDown("http://first"));
		failedProbe.close();
		Assertions.assertTrue(balancer.isCoolingDown("http://first"));
		Assertions.assertEquals(Duration.ofSeconds(20), balancer.getCooldownDuration("http://first"));
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(20));
		final InFlightRequest answeredProbe = balancer.startRequest("http://first");
		Assertions.assertTrue(answeredProbe.isProbe());
		balancer.endCooldown(answeredProbe);
		answeredProbe.close();
		Assertions.assertFalse(balancer.isSkipped("http://first"));
		Assertions.assertNull(balancer.getCooldownDuration("http://first"));
		Assertions.assertEquals(0, balancer.getInFlightRequests("http://first"));
	}

	/**
	 * The older {@code startCooldown(String)} counts as a refused connection of a
	 * request sent now: called again after the cooldown ended, it doubles it, and
	 * it never makes a list skipped after a connect timeout.
	 */
	@Test
	public void testEndpointOnlyCooldownGrowsAsRefused() {
		final AtomicLong clock = new AtomicLong(1_000_000_000L);
		final ServiceClientEndpointBalancer balancer = ServiceClientEndpointBalancerTest.createClockedBalancer(clock);
		balancer.startCooldown("http://first");
		Assertions.assertEquals(Duration.ofSeconds(10), balancer.getCooldownDuration("http://first"));
		ServiceClientEndpointBalancerTest.advance(clock, Duration.ofSeconds(10));
		balancer.startCooldown("http://first");
		Assertions.assertEquals(Duration.ofSeconds(20), balancer.getCooldownDuration("http://first"));
		Assertions.assertTrue(balancer.isCoolingDown("http://first"));
		Assertions.assertFalse(balancer.isEverySkippedAfterConnectTimeout(List.of("http://first")));
	}

}
