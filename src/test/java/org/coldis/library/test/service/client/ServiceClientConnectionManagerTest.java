package org.coldis.library.test.service.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.coldis.library.service.client.ServiceClientConnectionManager;
import org.coldis.library.service.client.ServiceClientPendingLeaseLimitException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.sun.net.httpserver.HttpServer;

/**
 * Bound of the requests waiting for a pooled connection to an endpoint.
 */
public class ServiceClientConnectionManagerTest {

	/**
	 * Longest a held request is kept, and longest the test waits for a state.
	 */
	private static final Duration MAX_WAIT = Duration.ofSeconds(30);

	/**
	 * Holding HTTP server: answers once the current release latch is opened.
	 */
	private static HttpServer HOLDING_SERVER;

	/**
	 * Request handlers of the holding server.
	 */
	private static ExecutorService HOLDING_SERVER_EXECUTOR;

	/**
	 * Latch that releases the held requests.
	 */
	private static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>(new CountDownLatch(0));

	/**
	 * Requests received by the holding server.
	 */
	private static final AtomicInteger RECEIVED_REQUESTS = new AtomicInteger();

	/**
	 * Starts the holding server.
	 *
	 * @throws IOException If the server cannot start.
	 */
	@BeforeAll
	public static void startHoldingServer() throws IOException {
		ServiceClientConnectionManagerTest.HOLDING_SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		ServiceClientConnectionManagerTest.HOLDING_SERVER_EXECUTOR = Executors.newCachedThreadPool();
		ServiceClientConnectionManagerTest.HOLDING_SERVER.setExecutor(ServiceClientConnectionManagerTest.HOLDING_SERVER_EXECUTOR);
		ServiceClientConnectionManagerTest.HOLDING_SERVER.createContext("/hold", exchange -> {
			ServiceClientConnectionManagerTest.RECEIVED_REQUESTS.incrementAndGet();
			try {
				ServiceClientConnectionManagerTest.RELEASE.get().await(ServiceClientConnectionManagerTest.MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (final InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			final byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream responseBody = exchange.getResponseBody()) {
				responseBody.write(response);
			}
		});
		ServiceClientConnectionManagerTest.HOLDING_SERVER.start();
	}

	/**
	 * Stops the holding server.
	 */
	@AfterAll
	public static void stopHoldingServer() {
		ServiceClientConnectionManagerTest.RELEASE.get().countDown();
		ServiceClientConnectionManagerTest.HOLDING_SERVER.stop(0);
		ServiceClientConnectionManagerTest.HOLDING_SERVER_EXECUTOR.shutdownNow();
	}

	/**
	 * Gets the holding URL.
	 *
	 * @return The holding URL.
	 */
	private static String getHoldUrl() {
		return "http://localhost:" + ServiceClientConnectionManagerTest.HOLDING_SERVER.getAddress().getPort() + "/hold";
	}

	/**
	 * Gets the route of the holding server.
	 *
	 * @return The route.
	 */
	private static HttpRoute getRoute() {
		return new HttpRoute(new HttpHost("http", "localhost", ServiceClientConnectionManagerTest.HOLDING_SERVER.getAddress().getPort()));
	}

	/**
	 * Waits until a value is reached (fails if it is not in time).
	 *
	 * @param  value                Value.
	 * @param  expectedValue        Expected value.
	 * @param  description          What the value is.
	 * @throws InterruptedException If interrupted.
	 */
	private static void awaitValue(
			final IntSupplier value,
			final int expectedValue,
			final String description) throws InterruptedException {
		final long deadlineNanos = System.nanoTime() + ServiceClientConnectionManagerTest.MAX_WAIT.toNanos();
		while ((value.getAsInt() != expectedValue) && (System.nanoTime() < deadlineNanos)) {
			Thread.sleep(10);
		}
		Assertions.assertEquals(expectedValue, value.getAsInt(), description);
	}

	/**
	 * Creates a template over a new client of the connection manager.
	 *
	 * @param  connectionManager Connection manager.
	 * @param  poolWait          Wait for a pooled connection.
	 * @return                   The template.
	 */
	private static RestTemplate createTemplate(
			final ServiceClientConnectionManager connectionManager,
			final Duration poolWait) {
		connectionManager.setMaxTotal(1000);
		final CloseableHttpClient httpClient = HttpClients.custom().setConnectionManager(connectionManager).setConnectionManagerShared(true).build();
		final HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
		requestFactory.setConnectionRequestTimeout(poolWait);
		return new RestTemplate(requestFactory);
	}

	/**
	 * Holds every connection of the route, then lets the given requests wait, and
	 * checks that the next one is refused at once (with nothing sent) and that the
	 * held and waiting requests still finish.
	 *
	 * @param  connectionManager Connection manager.
	 * @param  template          Template over the connection manager.
	 * @param  maxPerRoute       Connections of the route.
	 * @param  allowedWaiting    Requests expected to be allowed to wait.
	 * @param  refusedBefore     Requests refused before.
	 * @throws Exception         If the test fails.
	 */
	private static void assertWaitingBoundOnce(
			final ServiceClientConnectionManager connectionManager,
			final RestTemplate template,
			final int maxPerRoute,
			final int allowedWaiting,
			final long refusedBefore) throws Exception {
		final ExecutorService callers = Executors.newCachedThreadPool();
		ServiceClientConnectionManagerTest.RELEASE.set(new CountDownLatch(1));
		ServiceClientConnectionManagerTest.RECEIVED_REQUESTS.set(0);
		try {
			final List<Future<String>> calls = new ArrayList<>();
			for (Integer callIndex = 0; callIndex < maxPerRoute; callIndex++) {
				calls.add(callers.submit(() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class)));
			}
			ServiceClientConnectionManagerTest.awaitValue(ServiceClientConnectionManagerTest.RECEIVED_REQUESTS::get, maxPerRoute, "Held requests");
			Assertions.assertEquals(maxPerRoute, connectionManager.getLeased(ServiceClientConnectionManagerTest.getRoute()));
			for (Integer callIndex = 0; callIndex < allowedWaiting; callIndex++) {
				calls.add(callers.submit(() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class)));
			}
			ServiceClientConnectionManagerTest.awaitValue(() -> connectionManager.getRequested(ServiceClientConnectionManagerTest.getRoute()),
					allowedWaiting, "Waiting requests");
			// One more is refused at once, with nothing sent.
			final long startNanos = System.nanoTime();
			final ResourceAccessException exception = Assertions.assertThrows(ResourceAccessException.class,
					() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class));
			final Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
			Assertions.assertTrue(ExceptionUtils.indexOfType(exception, ServiceClientPendingLeaseLimitException.class) >= 0, () -> "Not a refusal: " + exception);
			Assertions.assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0, () -> "Refusal took " + elapsed);
			Assertions.assertEquals(refusedBefore + 1, connectionManager.getRefusedLeases());
			Assertions.assertEquals(maxPerRoute, ServiceClientConnectionManagerTest.RECEIVED_REQUESTS.get());
			// The held and waiting requests finish.
			ServiceClientConnectionManagerTest.RELEASE.get().countDown();
			for (final Future<String> call : calls) {
				Assertions.assertEquals("ok", call.get(ServiceClientConnectionManagerTest.MAX_WAIT.toSeconds(), TimeUnit.SECONDS));
			}
			Assertions.assertEquals(0, connectionManager.getLeased(ServiceClientConnectionManagerTest.getRoute()));
		}
		finally {
			ServiceClientConnectionManagerTest.RELEASE.get().countDown();
			callers.shutdownNow();
		}
	}

	/**
	 * Checks the waiting bound twice on the same manager: the counts left by the
	 * first round (none, if every exit lowers them) would change the second.
	 *
	 * @param  maxPerRoute     Connections of the route.
	 * @param  maxPendingRatio Ratio of the requests waiting.
	 * @param  allowedWaiting  Requests expected to be allowed to wait.
	 * @throws Exception       If the test fails.
	 */
	private static void assertWaitingBound(
			final int maxPerRoute,
			final String maxPendingRatio,
			final int allowedWaiting) throws Exception {
		final ServiceClientConnectionManager connectionManager = new ServiceClientConnectionManager(new BigDecimal(maxPendingRatio));
		connectionManager.setDefaultMaxPerRoute(maxPerRoute);
		final RestTemplate template = ServiceClientConnectionManagerTest.createTemplate(connectionManager, ServiceClientConnectionManagerTest.MAX_WAIT);
		try {
			ServiceClientConnectionManagerTest.assertWaitingBoundOnce(connectionManager, template, maxPerRoute, allowedWaiting, 0);
			ServiceClientConnectionManagerTest.assertWaitingBoundOnce(connectionManager, template, maxPerRoute, allowedWaiting, 1);
		}
		finally {
			connectionManager.close();
		}
	}

	/**
	 * With ratio 1, as many requests as connections in use may wait; the next is
	 * refused at once.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testWaitingAsManyAsInUse() throws Exception {
		ServiceClientConnectionManagerTest.assertWaitingBound(3, "1.0", 3);
	}

	/**
	 * The requests waiting allowed follow the ratio of the connections in use
	 * (rounded up).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testWaitingRelativeToInUse() throws Exception {
		ServiceClientConnectionManagerTest.assertWaitingBound(4, "0.5", 2);
		ServiceClientConnectionManagerTest.assertWaitingBound(3, "0.5", 2);
		// Exact: 0.1 x 30 is 3 (3.0000000000000004 in binary floating point).
		ServiceClientConnectionManagerTest.assertWaitingBound(30, "0.1", 3);
	}

	/**
	 * At least one request may always wait, however small the ratio.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testAtLeastOneWaiting() throws Exception {
		ServiceClientConnectionManagerTest.assertWaitingBound(2, "0.1", 1);
	}

	/**
	 * With ratio 0, any number of requests may wait (the pool alone).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testNoBoundWithZeroRatio() throws Exception {
		final ServiceClientConnectionManager connectionManager = new ServiceClientConnectionManager(BigDecimal.ZERO);
		connectionManager.setDefaultMaxPerRoute(1);
		final RestTemplate template = ServiceClientConnectionManagerTest.createTemplate(connectionManager, ServiceClientConnectionManagerTest.MAX_WAIT);
		final ExecutorService callers = Executors.newCachedThreadPool();
		ServiceClientConnectionManagerTest.RELEASE.set(new CountDownLatch(1));
		try {
			final List<Future<String>> calls = new ArrayList<>();
			for (Integer callIndex = 0; callIndex < 6; callIndex++) {
				calls.add(callers.submit(() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class)));
			}
			ServiceClientConnectionManagerTest.awaitValue(() -> connectionManager.getRequested(ServiceClientConnectionManagerTest.getRoute()), 5,
					"Waiting requests");
			ServiceClientConnectionManagerTest.RELEASE.get().countDown();
			for (final Future<String> call : calls) {
				Assertions.assertEquals("ok", call.get(ServiceClientConnectionManagerTest.MAX_WAIT.toSeconds(), TimeUnit.SECONDS));
			}
			Assertions.assertEquals(0, connectionManager.getRefusedLeases());
		}
		finally {
			ServiceClientConnectionManagerTest.RELEASE.get().countDown();
			callers.shutdownNow();
			connectionManager.close();
		}
	}

	/**
	 * A request that gave up waiting (pool wait timeout) no longer counts as
	 * waiting: the next one waits too, instead of being refused.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testTimedOutWaitNoLongerCounts() throws Exception {
		final ServiceClientConnectionManager connectionManager = new ServiceClientConnectionManager(BigDecimal.ONE);
		connectionManager.setDefaultMaxPerRoute(1);
		final RestTemplate holdingTemplate = ServiceClientConnectionManagerTest.createTemplate(connectionManager, ServiceClientConnectionManagerTest.MAX_WAIT);
		final RestTemplate waitingTemplate = ServiceClientConnectionManagerTest.createTemplate(connectionManager, Duration.ofMillis(200));
		final ExecutorService callers = Executors.newCachedThreadPool();
		ServiceClientConnectionManagerTest.RELEASE.set(new CountDownLatch(1));
		ServiceClientConnectionManagerTest.RECEIVED_REQUESTS.set(0);
		try {
			final Future<String> holdingCall = callers.submit(() -> holdingTemplate.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class));
			ServiceClientConnectionManagerTest.awaitValue(ServiceClientConnectionManagerTest.RECEIVED_REQUESTS::get, 1, "Held requests");
			for (Integer callIndex = 0; callIndex < 3; callIndex++) {
				final ResourceAccessException exception = Assertions.assertThrows(ResourceAccessException.class,
						() -> waitingTemplate.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class));
				Assertions.assertTrue(ExceptionUtils.indexOfType(exception, ConnectionRequestTimeoutException.class) >= 0,
						() -> "Not a pool wait timeout: " + exception);
			}
			Assertions.assertEquals(0, connectionManager.getRefusedLeases());
			ServiceClientConnectionManagerTest.RELEASE.get().countDown();
			Assertions.assertEquals("ok", holdingCall.get(ServiceClientConnectionManagerTest.MAX_WAIT.toSeconds(), TimeUnit.SECONDS));
			Assertions.assertEquals(0, connectionManager.getLeased(ServiceClientConnectionManagerTest.getRoute()));
		}
		finally {
			ServiceClientConnectionManagerTest.RELEASE.get().countDown();
			callers.shutdownNow();
			connectionManager.close();
		}
	}

	/**
	 * A route's own maximum is its capacity: requests beyond it wait (up to the
	 * bound), not beyond the default maximum.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testRouteMaximum() throws Exception {
		final ServiceClientConnectionManager connectionManager = new ServiceClientConnectionManager(BigDecimal.ONE);
		connectionManager.setDefaultMaxPerRoute(10);
		connectionManager.setMaxPerRoute(ServiceClientConnectionManagerTest.getRoute(), 1);
		final RestTemplate template = ServiceClientConnectionManagerTest.createTemplate(connectionManager, ServiceClientConnectionManagerTest.MAX_WAIT);
		final ExecutorService callers = Executors.newCachedThreadPool();
		ServiceClientConnectionManagerTest.RELEASE.set(new CountDownLatch(1));
		ServiceClientConnectionManagerTest.RECEIVED_REQUESTS.set(0);
		try {
			final List<Future<String>> calls = new ArrayList<>();
			calls.add(callers.submit(() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class)));
			ServiceClientConnectionManagerTest.awaitValue(ServiceClientConnectionManagerTest.RECEIVED_REQUESTS::get, 1, "Held requests");
			calls.add(callers.submit(() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class)));
			ServiceClientConnectionManagerTest.awaitValue(() -> connectionManager.getRequested(ServiceClientConnectionManagerTest.getRoute()), 1,
					"Waiting requests");
			final ResourceAccessException exception = Assertions.assertThrows(ResourceAccessException.class,
					() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class));
			Assertions.assertTrue(ExceptionUtils.indexOfType(exception, ServiceClientPendingLeaseLimitException.class) >= 0, () -> "Not a refusal: " + exception);
			ServiceClientConnectionManagerTest.RELEASE.get().countDown();
			for (final Future<String> call : calls) {
				Assertions.assertEquals("ok", call.get(ServiceClientConnectionManagerTest.MAX_WAIT.toSeconds(), TimeUnit.SECONDS));
			}
		}
		finally {
			ServiceClientConnectionManagerTest.RELEASE.get().countDown();
			callers.shutdownNow();
			connectionManager.close();
		}
	}

	/**
	 * A negative or missing ratio is refused.
	 */
	@Test
	public void testInvalidRatio() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new ServiceClientConnectionManager(new BigDecimal("-0.5")));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new ServiceClientConnectionManager(null));
	}

	/**
	 * Waits until a request waits for a pooled connection, then ends its wait, and
	 * checks that the call is over and the request no longer counts as waiting.
	 *
	 * @param  connectionManager Connection manager.
	 * @param  call              The waiting call.
	 * @param  endWait           Ends the wait.
	 * @throws Exception         If the test fails.
	 */
	private static void endWait(
			final ServiceClientConnectionManager connectionManager,
			final Future<?> call,
			final Runnable endWait) throws Exception {
		ServiceClientConnectionManagerTest.awaitValue(() -> connectionManager.getRequested(ServiceClientConnectionManagerTest.getRoute()), 1,
				"Waiting requests");
		endWait.run();
		final long deadlineNanos = System.nanoTime() + ServiceClientConnectionManagerTest.MAX_WAIT.toNanos();
		while (!call.isDone() && (System.nanoTime() < deadlineNanos)) {
			Thread.sleep(10);
		}
		Assertions.assertTrue(call.isDone(), "Ended wait");
		ServiceClientConnectionManagerTest.awaitValue(() -> connectionManager.getRequested(ServiceClientConnectionManagerTest.getRoute()), 0,
				"Waiting requests");
	}

	/**
	 * Requests whose wait was aborted (the request cancelled) or interrupted (its
	 * thread) no longer count as waiting: afterwards, exactly as many requests as
	 * before may wait, and the next is refused.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testAbortedAndInterruptedWaitsNoLongerCount() throws Exception {
		final ServiceClientConnectionManager connectionManager = new ServiceClientConnectionManager(BigDecimal.ONE);
		connectionManager.setDefaultMaxPerRoute(1);
		final CloseableHttpClient httpClient = HttpClients.custom().setConnectionManager(connectionManager).setConnectionManagerShared(true).build();
		final RestTemplate template = ServiceClientConnectionManagerTest.createTemplate(connectionManager, ServiceClientConnectionManagerTest.MAX_WAIT);
		final ExecutorService callers = Executors.newCachedThreadPool();
		ServiceClientConnectionManagerTest.RELEASE.set(new CountDownLatch(1));
		ServiceClientConnectionManagerTest.RECEIVED_REQUESTS.set(0);
		try {
			final Future<String> holdingCall = callers.submit(() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class));
			ServiceClientConnectionManagerTest.awaitValue(ServiceClientConnectionManagerTest.RECEIVED_REQUESTS::get, 1, "Held requests");
			// A waiting request aborted.
			for (Integer roundIndex = 0; roundIndex < 3; roundIndex++) {
				final HttpGet abortedRequest = new HttpGet(ServiceClientConnectionManagerTest.getHoldUrl());
				final Future<String> abortedCall = callers
						.submit(() -> httpClient.execute(abortedRequest, response -> EntityUtils.toString(response.getEntity())));
				ServiceClientConnectionManagerTest.endWait(connectionManager, abortedCall, abortedRequest::cancel);
			}
			// A waiting request interrupted.
			for (Integer roundIndex = 0; roundIndex < 3; roundIndex++) {
				final Future<String> interruptedCall = callers
						.submit(() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class));
				ServiceClientConnectionManagerTest.endWait(connectionManager, interruptedCall, () -> interruptedCall.cancel(true));
			}
			// Exactly one request may still wait, and the next is refused.
			final Future<String> waitingCall = callers.submit(() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class));
			ServiceClientConnectionManagerTest.awaitValue(() -> connectionManager.getRequested(ServiceClientConnectionManagerTest.getRoute()), 1,
					"Waiting requests");
			final ResourceAccessException exception = Assertions.assertThrows(ResourceAccessException.class,
					() -> template.getForObject(ServiceClientConnectionManagerTest.getHoldUrl(), String.class));
			Assertions.assertTrue(ExceptionUtils.indexOfType(exception, ServiceClientPendingLeaseLimitException.class) >= 0, () -> "Not a refusal: " + exception);
			Assertions.assertEquals(1, connectionManager.getRefusedLeases());
			ServiceClientConnectionManagerTest.RELEASE.get().countDown();
			Assertions.assertEquals("ok", holdingCall.get(ServiceClientConnectionManagerTest.MAX_WAIT.toSeconds(), TimeUnit.SECONDS));
			Assertions.assertEquals("ok", waitingCall.get(ServiceClientConnectionManagerTest.MAX_WAIT.toSeconds(), TimeUnit.SECONDS));
			Assertions.assertEquals(0, connectionManager.getLeased(ServiceClientConnectionManagerTest.getRoute()));
		}
		finally {
			ServiceClientConnectionManagerTest.RELEASE.get().countDown();
			callers.shutdownNow();
			httpClient.close();
			connectionManager.close();
		}
	}

}
