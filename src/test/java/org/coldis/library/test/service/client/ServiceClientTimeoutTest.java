package org.coldis.library.test.service.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.service.AbstractServiceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Service client timeouts test.
 */
public class ServiceClientTimeoutTest extends AbstractServiceTest {



	/**
	 * How long the slow endpoint takes to answer.
	 */
	private static final Duration SLOW_RESPONSE_DELAY = Duration.ofSeconds(2);


	/**
	 * How long the holding endpoint keeps a connection (less than the default read
	 * timeout, more than the default wait for a pooled connection).
	 */
	private static final Duration HOLD_DELAY = Duration.ofSeconds(7);

	/**
	 * Slow HTTP server.
	 */
	private static HttpServer SLOW_SERVER;

	/**
	 * Slow HTTP server request handlers.
	 */
	private static ExecutorService SLOW_SERVER_EXECUTOR;

	/**
	 * Requests received by the holding endpoint.
	 */
	private static final AtomicInteger HOLD_REQUESTS = new AtomicInteger();

	/**
	 * Connection pool.
	 */
	@Autowired
	private PoolingHttpClientConnectionManager httpClientPoolingManager;

	/**
	 * Request factory injected in one place.
	 */
	@Autowired
	private ClientHttpRequestFactory firstRequestFactory;

	/**
	 * Request factory injected in another place.
	 */
	@Autowired
	private ClientHttpRequestFactory secondRequestFactory;

	/**
	 * Rest template builder.
	 */
	@Autowired
	private RestTemplateBuilder restTemplateBuilder;

	/**
	 * Library default rest template.
	 */
	@Autowired
	@Qualifier(value = "restTemplate")
	private RestTemplate restTemplate;

	/**
	 * Answers after a delay.
	 *
	 * @param  exchange    HTTP exchange.
	 * @param  delay       Delay.
	 * @throws IOException If the response cannot be sent.
	 */
	private static void respondAfter(
			final HttpExchange exchange,
			final Duration delay) throws IOException {
		try {
			Thread.sleep(delay.toMillis());
		}
		catch (final InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		final byte[] response = "slow".getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, response.length);
		try (OutputStream responseBody = exchange.getResponseBody()) {
			responseBody.write(response);
		}
	}

	/**
	 * Starts a server that answers after a delay ({@code /slow}: 2 seconds,
	 * {@code /hold}: 7 seconds).
	 *
	 * @throws IOException If the server cannot start.
	 */
	@BeforeAll
	public static void startSlowServer() throws IOException {
		ServiceClientTimeoutTest.SLOW_SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		ServiceClientTimeoutTest.SLOW_SERVER_EXECUTOR = Executors.newCachedThreadPool();
		ServiceClientTimeoutTest.SLOW_SERVER.setExecutor(ServiceClientTimeoutTest.SLOW_SERVER_EXECUTOR);
		ServiceClientTimeoutTest.SLOW_SERVER.createContext("/slow",
				exchange -> ServiceClientTimeoutTest.respondAfter(exchange, ServiceClientTimeoutTest.SLOW_RESPONSE_DELAY));
		ServiceClientTimeoutTest.SLOW_SERVER.createContext("/hold", exchange -> {
			ServiceClientTimeoutTest.HOLD_REQUESTS.incrementAndGet();
			ServiceClientTimeoutTest.respondAfter(exchange, ServiceClientTimeoutTest.HOLD_DELAY);
		});
		ServiceClientTimeoutTest.SLOW_SERVER.start();
	}

	/**
	 * Stops the slow server.
	 */
	@AfterAll
	public static void stopSlowServer() {
		ServiceClientTimeoutTest.SLOW_SERVER.stop(0);
		ServiceClientTimeoutTest.SLOW_SERVER_EXECUTOR.shutdownNow();
	}

	/**
	 * Gets the slow endpoint URL.
	 *
	 * @return The slow endpoint URL.
	 */
	private static String getSlowUrl() {
		return "http://localhost:" + ServiceClientTimeoutTest.SLOW_SERVER.getAddress().getPort() + "/slow";
	}

	/**
	 * Builds a template with the given read timeout from a request factory.
	 *
	 * @param  requestFactory Request factory.
	 * @param  readTimeout    Read timeout.
	 * @return                The template.
	 */
	private RestTemplate buildRestTemplate(
			final ClientHttpRequestFactory requestFactory,
			final Duration readTimeout) {
		return this.restTemplateBuilder.connectTimeout(readTimeout).readTimeout(readTimeout).requestFactory(() -> requestFactory).build();
	}

	/**
	 * Templates built from the injected request factory keep their own timeouts,
	 * whatever the order they are built in, and do not change the default
	 * template's.
	 */
	@Test
	public void testTemplatesKeepTheirOwnTimeouts() {
		final RestTemplate shortTimeoutTemplate = this.buildRestTemplate(this.firstRequestFactory, Duration.ofSeconds(1));
		final RestTemplate longTimeoutTemplate = this.buildRestTemplate(this.secondRequestFactory, Duration.ofSeconds(30));
		// The short timeout template still times out, although the long one was built
		// after it.
		Assertions.assertThrows(ResourceAccessException.class, () -> shortTimeoutTemplate.getForObject(ServiceClientTimeoutTest.getSlowUrl(), String.class));
		// The long timeout template waits.
		Assertions.assertEquals("slow", longTimeoutTemplate.getForObject(ServiceClientTimeoutTest.getSlowUrl(), String.class));
		// The other build order: the long one first, then the short one.
		final RestTemplate secondLongTimeoutTemplate = this.buildRestTemplate(this.secondRequestFactory, Duration.ofSeconds(30));
		final RestTemplate secondShortTimeoutTemplate = this.buildRestTemplate(this.firstRequestFactory, Duration.ofSeconds(1));
		Assertions.assertEquals("slow", secondLongTimeoutTemplate.getForObject(ServiceClientTimeoutTest.getSlowUrl(), String.class));
		Assertions.assertThrows(ResourceAccessException.class,
				() -> secondShortTimeoutTemplate.getForObject(ServiceClientTimeoutTest.getSlowUrl(), String.class));
		// Builds another short timeout template, and the default template (9s read)
		// still waits.
		this.buildRestTemplate(this.firstRequestFactory, Duration.ofSeconds(1));
		Assertions.assertEquals("slow", this.restTemplate.getForObject(ServiceClientTimeoutTest.getSlowUrl(), String.class));
	}

	/**
	 * A connect that does not complete fails within the default template connect
	 * timeout (3s), not its read timeout (9s). The connect goes to a local server
	 * socket whose accept queue is full and that never accepts: the operating
	 * system drops the new connection request, so the connect hangs on any network.
	 *
	 * @throws IOException If the local server socket cannot be set up.
	 */
	@Test
	public void testDefaultTemplateConnectTimeout() throws IOException {
		final List<Socket> queuedSockets = new ArrayList<>();
		try (ServerSocket fullServerSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			final InetSocketAddress fullAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), fullServerSocket.getLocalPort());
			// Fills the accept queue until a connect no longer completes.
			boolean isQueueFull = false;
			while (!isQueueFull && (queuedSockets.size() < 16)) {
				final Socket queuedSocket = new Socket();
				queuedSockets.add(queuedSocket);
				try {
					queuedSocket.connect(fullAddress, 200);
				}
				catch (final SocketTimeoutException exception) {
					isQueueFull = true;
				}
			}
			Assertions.assertTrue(isQueueFull, "The accept queue could not be filled.");
			final long startNanos = System.nanoTime();
			final ResourceAccessException exception = Assertions.assertThrows(ResourceAccessException.class,
					() -> this.restTemplate.getForObject("http://" + fullAddress.getHostString() + ":" + fullAddress.getPort() + "/", String.class));
			final Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
			Assertions.assertTrue(ExceptionUtils.indexOfType(exception, ConnectTimeoutException.class) >= 0, () -> "Not a connect timeout: " + exception);
			Assertions.assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) < 0, () -> "Connect took " + elapsed);
		}
		finally {
			for (final Socket queuedSocket : queuedSockets) {
				queuedSocket.close();
			}
		}
	}

	/**
	 * With every pooled connection to a host busy, the default template gives up
	 * waiting for one after its pool wait (5s), not the HTTP client default (3
	 * minutes).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testDefaultTemplatePoolWaitTimeout() throws Exception {
		final String holdUrl = "http://localhost:" + ServiceClientTimeoutTest.SLOW_SERVER.getAddress().getPort() + "/hold";
		final int maxPerRoute = this.httpClientPoolingManager.getDefaultMaxPerRoute();
		final ExecutorService callers = Executors.newFixedThreadPool(maxPerRoute);
		try {
			// Holds every connection of the route.
			ServiceClientTimeoutTest.HOLD_REQUESTS.set(0);
			final List<Future<String>> holdingCalls = new ArrayList<>();
			for (Integer callIndex = 0; callIndex < maxPerRoute; callIndex++) {
				holdingCalls.add(callers.submit(() -> this.restTemplate.getForObject(holdUrl, String.class)));
			}
			final long holdStartNanos = System.nanoTime();
			while ((ServiceClientTimeoutTest.HOLD_REQUESTS.get() < maxPerRoute) && (Duration.ofNanos(System.nanoTime() - holdStartNanos).toSeconds() < 5)) {
				Thread.sleep(10);
			}
			Assertions.assertEquals(maxPerRoute, ServiceClientTimeoutTest.HOLD_REQUESTS.get());
			// One more request waits for a pooled connection and gives up.
			final long startNanos = System.nanoTime();
			final ResourceAccessException exception = Assertions.assertThrows(ResourceAccessException.class,
					() -> this.restTemplate.getForObject(holdUrl, String.class));
			final Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
			Assertions.assertTrue(ExceptionUtils.indexOfType(exception, ConnectionRequestTimeoutException.class) >= 0,
					() -> "Not a pool wait timeout: " + exception);
			Assertions.assertTrue((elapsed.compareTo(Duration.ofSeconds(4)) > 0) && (elapsed.compareTo(ServiceClientTimeoutTest.HOLD_DELAY) < 0),
					() -> "Pool wait took " + elapsed);
			// The held requests still finish.
			for (final Future<String> holdingCall : holdingCalls) {
				Assertions.assertEquals("slow", holdingCall.get());
			}
		}
		finally {
			// Frees the pool before the next test.
			callers.shutdown();
			callers.awaitTermination(ServiceClientTimeoutTest.HOLD_DELAY.toSeconds() * 2, TimeUnit.SECONDS);
		}
	}

}
