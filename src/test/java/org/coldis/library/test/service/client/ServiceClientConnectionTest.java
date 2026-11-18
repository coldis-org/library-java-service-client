package org.coldis.library.test.service.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.HttpRequestRetryExec;
import org.apache.hc.client5.http.impl.classic.MainClientExec;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.function.Resolver;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.util.TimeValue;
import org.coldis.library.service.client.ServiceClientKeepAliveStrategy;
import org.coldis.library.service.client.ServiceClientRetryStrategy;
import org.coldis.library.test.service.AbstractServiceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sun.net.httpserver.HttpServer;

/**
 * Pooled connections of the service client: their lifetime and keep-alive.
 */
public class ServiceClientConnectionTest extends AbstractServiceTest {

	/**
	 * Instance that records the client port of each request (one per connection).
	 */
	private static HttpServer SERVER;

	/**
	 * Request handler of the instance.
	 */
	private static ExecutorService SERVER_EXECUTOR;

	/**
	 * Client ports seen by the instance (one per connection).
	 */
	private static final Set<Integer> CLIENT_PORTS = ConcurrentHashMap.newKeySet();

	/**
	 * Pooling manager of the library HTTP client.
	 */
	@Autowired
	private PoolingHttpClientConnectionManager httpClientPoolingManager;

	/**
	 * Library HTTP client.
	 */
	@Autowired
	private HttpClient httpClient;

	/**
	 * Retry strategy of the library HTTP client.
	 */
	@Autowired
	private ServiceClientRetryStrategy retryStrategy;

	/**
	 * Keep-alive strategy of the library HTTP client.
	 */
	@Autowired
	private ServiceClientKeepAliveStrategy keepAliveStrategy;

	/**
	 * Starts the instance.
	 *
	 * @throws IOException If the instance cannot start.
	 */
	@BeforeAll
	public static void startServer() throws IOException {
		ServiceClientConnectionTest.SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		ServiceClientConnectionTest.SERVER.createContext("/", exchange -> {
			ServiceClientConnectionTest.CLIENT_PORTS.add(exchange.getRemoteAddress().getPort());
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		ServiceClientConnectionTest.SERVER_EXECUTOR = Executors.newCachedThreadPool();
		ServiceClientConnectionTest.SERVER.setExecutor(ServiceClientConnectionTest.SERVER_EXECUTOR);
		ServiceClientConnectionTest.SERVER.start();
	}

	/**
	 * Stops the instance.
	 */
	@AfterAll
	public static void stopServer() {
		ServiceClientConnectionTest.SERVER.stop(0);
		ServiceClientConnectionTest.SERVER_EXECUTOR.shutdownNow();
	}

	/**
	 * Forgets the connections of earlier tests.
	 */
	@Override
	@BeforeEach
	public void beforeEach() {
		super.beforeEach();
		ServiceClientConnectionTest.CLIENT_PORTS.clear();
	}

	/**
	 * The shipped connection settings reach the pool and the library HTTP client
	 * (its retry strategy too, which does not re-send a 503 by default):
	 * at most 15 seconds of keep-alive (a shorter one from the server wins), 60
	 * seconds of time to live, validation after 1 second of inactivity. The pool's
	 * configuration and the client's keep-alive strategy are private to the HTTP
	 * client (field names as of HttpClient 5.5): they are read by reflection.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testConnectionSettingsReachThePool() throws Exception {
		final ConnectionConfig connectionConfig = ((Resolver<Object, ConnectionConfig>) FieldUtils.readField(this.httpClientPoolingManager,
				"connectionConfigResolver", true)).resolve(null);
		Assertions.assertEquals(TimeValue.ofSeconds(60), connectionConfig.getTimeToLive());
		Assertions.assertEquals(TimeValue.ofSeconds(1), connectionConfig.getValidateAfterInactivity());
		Assertions.assertEquals(TimeValue.ofSeconds(15), this.keepAliveStrategy.getMaxKeepAlive());
		Assertions.assertSame(this.keepAliveStrategy, ServiceClientConnectionTest.getChainStepField(this.httpClient, MainClientExec.class, "keepAliveStrategy"));
		// The retry strategy, with the 503 re-send off by default.
		Assertions.assertSame(this.retryStrategy, ServiceClientConnectionTest.getChainStepField(this.httpClient, HttpRequestRetryExec.class, "retryStrategy"));
		Assertions.assertFalse(this.retryStrategy.isRetryingServiceUnavailable());
		// No Keep-Alive header, or a longer one: the maximum. A shorter one: the server's.
		final BasicHttpResponse response = new BasicHttpResponse(HttpStatus.SC_OK);
		Assertions.assertEquals(TimeValue.ofSeconds(15), this.keepAliveStrategy.getKeepAliveDuration(response, HttpClientContext.create()));
		response.setHeader("Keep-Alive", "timeout=60");
		Assertions.assertEquals(TimeValue.ofSeconds(15), this.keepAliveStrategy.getKeepAliveDuration(response, HttpClientContext.create()));
		response.setHeader("Keep-Alive", "timeout=5");
		Assertions.assertEquals(TimeValue.ofSeconds(5), this.keepAliveStrategy.getKeepAliveDuration(response, HttpClientContext.create()));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new ServiceClientKeepAliveStrategy(Duration.ofSeconds(-1)));
	}

	/**
	 * Gets a field of a step of an HTTP client's execution chain (private to the
	 * HTTP client, names as of HttpClient 5.5: read by reflection).
	 *
	 * @param  httpClient             HTTP client.
	 * @param  stepType               Type of the step.
	 * @param  fieldName              Field name.
	 * @return                        The field value ({@code null} if the step is
	 *                                not found).
	 * @throws IllegalAccessException If the chain cannot be read.
	 */
	private static Object getChainStepField(
			final HttpClient httpClient,
			final Class<?> stepType,
			final String fieldName) throws IllegalAccessException {
		Object fieldValue = null;
		Object chainElement = FieldUtils.readField(httpClient, "execChain", true);
		while ((chainElement != null) && (fieldValue == null)) {
			final Object handler = FieldUtils.readField(chainElement, "handler", true);
			fieldValue = (stepType.isInstance(handler) ? FieldUtils.readField(handler, fieldName, true) : null);
			chainElement = FieldUtils.readField(chainElement, "next", true);
		}
		return fieldValue;
	}

	/**
	 * Sends a GET through a client and counts the connections the instance saw.
	 *
	 * @param  httpClient  HTTP client.
	 * @return             The connections the instance saw so far.
	 * @throws IOException If the request fails.
	 */
	private static Integer get(
			final CloseableHttpClient httpClient) throws IOException {
		httpClient.execute(new HttpGet("http://localhost:" + ServiceClientConnectionTest.SERVER.getAddress().getPort() + "/"), response -> null);
		return ServiceClientConnectionTest.CLIENT_PORTS.size();
	}

	/**
	 * A pooled connection idle for longer than the maximum keep-alive is not
	 * reused (a new connection reaches the server), while one idle for less is.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testIdleConnectionNotReusedBeyondKeepAlive() throws Exception {
		try (CloseableHttpClient shortKeepAliveClient = HttpClients.custom().setConnectionManager(new PoolingHttpClientConnectionManager())
				.setKeepAliveStrategy(new ServiceClientKeepAliveStrategy(Duration.ofMillis(300))).build();
				CloseableHttpClient longKeepAliveClient = HttpClients.custom().setConnectionManager(new PoolingHttpClientConnectionManager())
						.setKeepAliveStrategy(new ServiceClientKeepAliveStrategy(Duration.ofSeconds(15))).build()) {
			// Back to back, the connection is reused; idle past the maximum, it is not.
			Assertions.assertEquals(1, ServiceClientConnectionTest.get(shortKeepAliveClient));
			Assertions.assertEquals(1, ServiceClientConnectionTest.get(shortKeepAliveClient));
			Thread.sleep(800);
			Assertions.assertEquals(2, ServiceClientConnectionTest.get(shortKeepAliveClient));
			// Idle for as long, but within the maximum: reused.
			ServiceClientConnectionTest.CLIENT_PORTS.clear();
			Assertions.assertEquals(1, ServiceClientConnectionTest.get(longKeepAliveClient));
			Thread.sleep(800);
			Assertions.assertEquals(1, ServiceClientConnectionTest.get(longKeepAliveClient));
		}
	}

}
