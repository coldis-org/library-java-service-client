package org.coldis.library.test.service.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hc.client5.http.classic.HttpClient;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.client.ServiceClientEndpointBalancer;
import org.coldis.library.service.client.ServiceClientFailoverExecutor;
import org.coldis.library.service.client.ServiceClientRequestFactory;
import org.coldis.library.service.model.FileResource;
import org.coldis.library.test.service.AbstractServiceTest;
import org.coldis.library.test.service.client.dto.DtoTestObjectDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpServerErrorException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Service client failover test.
 */
public class ServiceClientFailoverTest extends AbstractServiceTest {



	/**
	 * How many times calls whose first endpoint is random are repeated (the chance
	 * that none starts on a given endpoint of two is 1 in 2^20).
	 */
	private static final Integer REPEATED_CALLS = 20;

	/**
	 * Response type.
	 */
	private static final ParameterizedTypeReference<String> STRING_RESPONSE = new ParameterizedTypeReference<>() {};


	/**
	 * Request handlers of the instances.
	 */
	private static final List<ExecutorService> SERVER_EXECUTORS = new CopyOnWriteArrayList<>();

	/**
	 * First instance.
	 */
	private static HttpServer FIRST_SERVER;

	/**
	 * Second instance.
	 */
	private static HttpServer SECOND_SERVER;

	/**
	 * Third instance.
	 */
	private static HttpServer THIRD_SERVER;

	/**
	 * Requests received by the first instance.
	 */
	private static final AtomicInteger FIRST_SERVER_REQUESTS = new AtomicInteger();

	/**
	 * Requests received by the second instance.
	 */
	private static final AtomicInteger SECOND_SERVER_REQUESTS = new AtomicInteger();

	/**
	 * Last value of the header added by a client hook, as received by an instance.
	 */
	private static final AtomicReference<String> LAST_HOOK_HEADER = new AtomicReference<>();

	/**
	 * Requests received by the third instance.
	 */
	private static final AtomicInteger THIRD_SERVER_REQUESTS = new AtomicInteger();

	/**
	 * Endpoint that refuses connections.
	 */
	private static String REFUSED_ENDPOINT;

	/**
	 * Service client (library default template).
	 */
	@Autowired
	@Qualifier(value = "restServiceClient")
	private GenericRestServiceClient serviceClient;

	/**
	 * HTTP client of the library.
	 */
	@Autowired
	private HttpClient httpClient;

	/**
	 * Port of the local server.
	 */
	@LocalServerPort
	private Integer localServerPort;

	/**
	 * Endpoint balancer of the context (used by the generated clients).
	 */
	@Autowired
	private ServiceClientEndpointBalancer contextBalancer;

	/**
	 * Failover executor.
	 */
	@Autowired
	private ServiceClientFailoverExecutor failoverExecutor;

	/**
	 * Object mapper.
	 */
	@Autowired
	private ObjectMapper objectMapper;


	/**
	 * Rest template builder.
	 */
	@Autowired
	private RestTemplateBuilder restTemplateBuilder;

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
	 * Original generated client endpoint.
	 */
	private String originalTestService2Endpoint;

	/**
	 * Sends a response.
	 *
	 * @param  exchange    HTTP exchange.
	 * @param  status      Response status.
	 * @param  response    Response body.
	 * @throws IOException If the response cannot be sent.
	 */
	private static void respond(
			final HttpExchange exchange,
			final Integer status,
			final String response) throws IOException {
		final byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", MediaType.TEXT_PLAIN_VALUE);
		exchange.sendResponseHeaders(status, responseBytes.length);
		try (OutputStream responseBody = exchange.getResponseBody()) {
			responseBody.write(responseBytes);
		}
	}

	/**
	 * Starts an instance.
	 * <ul>
	 * <li>{@code /ok}: 200 with the instance name.</li>
	 * <li>{@code /test2/slow}: 200 after 3 seconds.</li>
	 * <li>{@code /unavailable}, {@code /test2/**} (the generated client's paths):
	 * 503.</li>
	 * <li>{@code /unavailable-on-first}: 503 on the first instance, 200 on the
	 * second.</li>
	 * <li>{@code /slow}: 200 after 3 seconds.</li>
	 * <li>{@code /slow-on-first}: 200, after 300 milliseconds on the first
	 * instance.</li>
	 * <li>{@code /echo}: 200 with the request body.</li>
	 * <li>{@code /rate-limited}: 429, retry after 1 second.</li>
	 * <li>{@code /drop}: connection closed without a response.</li>
	 * <li>{@code /drop-on-first}: connection closed without a response on the first
	 * instance, 200 on the second.</li>
	 * </ul>
	 *
	 * @param  name        Instance name.
	 * @param  requests    Request counter.
	 * @return             The instance.
	 * @throws IOException If the instance cannot start.
	 */
	private static HttpServer startServer(
			final String name,
			final AtomicInteger requests) throws IOException {
		final HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			ServiceClientFailoverTest.LAST_HOOK_HEADER.set(exchange.getRequestHeaders().getFirst("X-Hook"));
			final String path = exchange.getRequestURI().getPath();
			final byte[] requestBody = exchange.getRequestBody().readAllBytes();
			if ("/test2/slow".equals(path)) {
				try {
					Thread.sleep(3000);
				}
				catch (final InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				ServiceClientFailoverTest.respond(exchange, 200, "");
			}
			else if ("/unavailable".equals(path) || path.startsWith("/test2") || ("/unavailable-on-first".equals(path) && "first".equals(name))) {
				ServiceClientFailoverTest.respond(exchange, 503, "");
			}
			else if ("/slow".equals(path)) {
				try {
					Thread.sleep(3000);
				}
				catch (final InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				ServiceClientFailoverTest.respond(exchange, 200, name);
			}
			else if ("/drop".equals(path) || ("/drop-on-first".equals(path) && "first".equals(name))) {
				// Closes the connection without a response.
				exchange.close();
			}
			else if ("/rate-limited".equals(path)) {
				exchange.getResponseHeaders().add("Retry-After", "1");
				ServiceClientFailoverTest.respond(exchange, 429, "");
			}
			else if ("/slow-on-first".equals(path)) {
				try {
					Thread.sleep("first".equals(name) ? 300 : 5);
				}
				catch (final InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				ServiceClientFailoverTest.respond(exchange, 200, name);
			}
			else if ("/bad-request".equals(path)) {
				ServiceClientFailoverTest.respond(exchange, 400, "");
			}
			else if ("/echo".equals(path)) {
				ServiceClientFailoverTest.respond(exchange, 200, new String(requestBody, StandardCharsets.UTF_8));
			}
			else {
				ServiceClientFailoverTest.respond(exchange, 200, name);
			}
		});
		final ExecutorService serverExecutor = Executors.newCachedThreadPool();
		ServiceClientFailoverTest.SERVER_EXECUTORS.add(serverExecutor);
		server.setExecutor(serverExecutor);
		server.start();
		return server;
	}

	/**
	 * Gets an instance endpoint.
	 *
	 * @param  server Instance.
	 * @return        The instance endpoint.
	 */
	private static String getEndpoint(
			final HttpServer server) {
		return "http://localhost:" + server.getAddress().getPort();
	}

	/**
	 * Starts the instances and finds a port that refuses connections.
	 *
	 * @throws IOException If the instances cannot start.
	 */
	@BeforeAll
	public static void startServers() throws IOException {
		ServiceClientFailoverTest.FIRST_SERVER = ServiceClientFailoverTest.startServer("first", ServiceClientFailoverTest.FIRST_SERVER_REQUESTS);
		ServiceClientFailoverTest.SECOND_SERVER = ServiceClientFailoverTest.startServer("second", ServiceClientFailoverTest.SECOND_SERVER_REQUESTS);
		ServiceClientFailoverTest.THIRD_SERVER = ServiceClientFailoverTest.startServer("third", ServiceClientFailoverTest.THIRD_SERVER_REQUESTS);
		try (ServerSocket closedSocket = new ServerSocket(0)) {
			ServiceClientFailoverTest.REFUSED_ENDPOINT = "http://localhost:" + closedSocket.getLocalPort();
		}
	}

	/**
	 * Stops the instances.
	 */
	@AfterAll
	public static void stopServers() {
		ServiceClientFailoverTest.FIRST_SERVER.stop(0);
		ServiceClientFailoverTest.SECOND_SERVER.stop(0);
		ServiceClientFailoverTest.THIRD_SERVER.stop(0);
		ServiceClientFailoverTest.SERVER_EXECUTORS.forEach(ExecutorService::shutdownNow);
	}

	/**
	 * Resets the request counters and keeps the generated client endpoint.
	 */
	@Override
	@BeforeEach
	public void beforeEach() {
		super.beforeEach();
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.THIRD_SERVER_REQUESTS.set(0);
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
	 * Gets the total requests received by the instances.
	 *
	 * @return The total requests received by the instances.
	 */
	private static Integer getTotalRequests() {
		return ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get() + ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.get();
	}

	/**
	 * Creates a client with a balancer of its own, whose template (over the library
	 * HTTP client) records the URL of each attempt (before connecting) through an
	 * interceptor, unless no list is given.
	 *
	 * @param  balancer      Endpoint balancer.
	 * @param  attemptedUrls Where the attempted URLs are recorded ({@code null} for
	 *                           no interceptor).
	 * @return               The client.
	 */
	private GenericRestServiceClient createRecordingClient(
			final ServiceClientEndpointBalancer balancer,
			final List<String> attemptedUrls) {
		final GenericRestServiceClient recordingClient = new GenericRestServiceClient() {
			{
				this.setObjectMapper(ServiceClientFailoverTest.this.objectMapper);
			}
		};
		final RestTemplateBuilder clientTemplateBuilder = this.restTemplateBuilder
				.requestFactory(() -> new HttpComponentsClientHttpRequestFactory(this.httpClient));
		recordingClient.setRestTemplate(attemptedUrls == null ? clientTemplateBuilder.build()
				: clientTemplateBuilder.additionalInterceptors((request, body, execution) -> {
					attemptedUrls.add(request.getURI().toString());
					return execution.execute(request, body);
				}).build());
		recordingClient.setFailoverExecutor(
				new ServiceClientFailoverExecutor(true, 3, new String[] { "GET", "HEAD", "OPTIONS" }, new String[] { "502", "503", "504" }, new String[] { "502", "503", "504" }, false,
						balancer, ServiceClientRequestFactory.READ_TIMEOUT_SCOPE));
		return recordingClient;
	}

	/**
	 * Creates a least-in-flight balancer.
	 *
	 * @param  failureCooldown How long an endpoint that failed is skipped (zero for
	 *                             never).
	 * @return                 The balancer.
	 */
	private static ServiceClientEndpointBalancer createBalancer(
			final Duration failureCooldown) {
		return new ServiceClientEndpointBalancer(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, failureCooldown);
	}

	/**
	 * Counts the attempts to the refusing endpoint.
	 *
	 * @param  attemptedUrls Attempted URLs.
	 * @return               The attempts to the refusing endpoint.
	 */
	private static long countAttempts(
			final List<String> attemptedUrls) {
		return attemptedUrls.stream().filter(url -> url.startsWith(ServiceClientFailoverTest.REFUSED_ENDPOINT)).count();
	}

	/**
	 * Executes an operation over the given endpoints.
	 *
	 * @param  serviceClient     Service client.
	 * @param  endpoints         Endpoints.
	 * @param  path              Path.
	 * @param  method            Method.
	 * @param  body              Body.
	 * @param  idempotent        If the operation is idempotent ({@code null} by
	 *                               method).
	 * @return                   The response body.
	 * @throws BusinessException If the operation fails.
	 */
	private static String execute(
			final GenericRestServiceClient serviceClient,
			final List<String> endpoints,
			final String path,
			final HttpMethod method,
			final Object body,
			final Boolean idempotent) throws BusinessException {
		return serviceClient.executeOperation(endpoints, path, method, null, body, null, ServiceClientFailoverTest.STRING_RESPONSE, idempotent).getBody();
	}

	/**
	 * Executes an operation over the given endpoints.
	 *
	 * @param  serviceClient Service client.
	 * @param  endpoints     Endpoints.
	 * @param  path          Path.
	 * @param  method        Method.
	 * @param  body          Body.
	 * @return                   The response body.
	 * @throws BusinessException If the operation fails.
	 */
	private static String execute(
			final GenericRestServiceClient serviceClient,
			final List<String> endpoints,
			final String path,
			final HttpMethod method,
			final Object body) throws BusinessException {
		return ServiceClientFailoverTest.execute(serviceClient, endpoints, path, method, body, null);
	}

	/**
	 * A POST is retried on the next endpoint when the connection is refused
	 * (nothing was sent).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testPostRetriedOnConnectionRefused() throws Exception {
		// No cooldown, so the refusing endpoint keeps being chosen.
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		final GenericRestServiceClient recordingClient = this.createRecordingClient(ServiceClientFailoverTest.createBalancer(Duration.ZERO), attemptedUrls);
		final List<String> endpoints = List.of(ServiceClientFailoverTest.REFUSED_ENDPOINT,
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			Assertions.assertEquals("first", ServiceClientFailoverTest.execute(recordingClient, endpoints, "/ok", HttpMethod.POST, "body"));
		}
		Assertions.assertEquals(ServiceClientFailoverTest.REPEATED_CALLS, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
		Assertions.assertTrue(ServiceClientFailoverTest.countAttempts(attemptedUrls) > 0);
	}

	/**
	 * A POST is not retried once an endpoint answered, even with a retry status.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testPostNotRetriedAfterResponse() throws Exception {
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/unavailable", HttpMethod.POST, "body"));
		Assertions.assertEquals(Integer.valueOf(503), exception.getStatusCode());
		Assertions.assertEquals(1, ServiceClientFailoverTest.getTotalRequests());
	}

	/**
	 * A GET is retried on the next endpoint after a 503; the exception after the
	 * last attempt is mapped once.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testGetRetriedOnUnavailable() throws Exception {
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		// No cooldown, so the first endpoint keeps being chosen.
		final GenericRestServiceClient noCooldownClient = this.createRecordingClient(ServiceClientFailoverTest.createBalancer(Duration.ZERO), new CopyOnWriteArrayList<>());
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			Assertions.assertEquals("second", ServiceClientFailoverTest.execute(noCooldownClient, endpoints, "/unavailable-on-first", HttpMethod.GET, null));
		}
		Assertions.assertEquals(ServiceClientFailoverTest.REPEATED_CALLS, ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.get());
		Assertions.assertTrue(ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get() > 0);
		// When every endpoint answers 503, each is tried once and the last 503 is
		// thrown.
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.set(0);
		final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/unavailable", HttpMethod.GET, null));
		Assertions.assertEquals(Integer.valueOf(503), exception.getStatusCode());
		Assertions.assertEquals(1, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
		Assertions.assertEquals(1, ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.get());
	}

	/**
	 * Creates a client with a 1 second read timeout over the library HTTP client.
	 *
	 * @param  failoverExecutor Failover executor ({@code null} for none).
	 * @return                  The client.
	 */
	private GenericRestServiceClient createShortTimeoutClient(
			final ServiceClientFailoverExecutor failoverExecutor) {
		final GenericRestServiceClient shortTimeoutClient = new GenericRestServiceClient() {
			{
				this.setObjectMapper(ServiceClientFailoverTest.this.objectMapper);
			}
		};
		shortTimeoutClient.setFailoverExecutor(failoverExecutor);
		shortTimeoutClient.setRestTemplate(this.restTemplateBuilder.connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofSeconds(1))
				.requestFactory(() -> new HttpComponentsClientHttpRequestFactory(this.httpClient)).build());
		return shortTimeoutClient;
	}

	/**
	 * A read timeout is not retried, even for a GET; a client with its own template
	 * keeps it on failover calls.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testReadTimeoutNotRetried() throws Exception {
		final GenericRestServiceClient shortTimeoutClient = this.createShortTimeoutClient(this.failoverExecutor);
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		final long startNanos = System.nanoTime();
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(shortTimeoutClient, endpoints, "/slow", HttpMethod.GET, null));
		Assertions.assertTrue(Duration.ofNanos(System.nanoTime() - startNanos).compareTo(Duration.ofSeconds(3)) < 0);
		Assertions.assertEquals(1, ServiceClientFailoverTest.getTotalRequests());
	}

	/**
	 * A multipart body with a stream part reaches the next endpoint whole after a
	 * refused connection.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testStreamBodySurvivesConnectFailure() throws Exception {
		// No cooldown, so the refusing endpoint keeps being chosen (about half of the
		// calls start on it), and no interceptor, so the body is streamed.
		final GenericRestServiceClient streamingClient = this.createRecordingClient(ServiceClientFailoverTest.createBalancer(Duration.ZERO), null);
		final List<String> endpoints = List.of(ServiceClientFailoverTest.REFUSED_ENDPOINT,
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			final String content = "stream content " + callIndex;
			final MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
			parts.add("file", new InputStreamResource(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {

				@Override
				public String getFilename() {
					return "file.txt";
				}

				@Override
				public long contentLength() {
					return -1;
				}
			});
			final MultiValueMap<String, String> headers = GenericRestServiceClient.addContentTypeHeaders(null, MediaType.MULTIPART_FORM_DATA_VALUE);
			final String response = streamingClient
					.executeOperation(endpoints, "/echo", HttpMethod.POST, headers, parts, null, ServiceClientFailoverTest.STRING_RESPONSE, null).getBody();
			Assertions.assertTrue(response.contains(content), response);
		}
	}

	/**
	 * A single endpoint gets one attempt, through the client's own template, which
	 * keeps the HTTP client's automatic retry: a GET whose connection dropped is
	 * re-sent once (it reaches the instance twice), a 429 is re-sent once for any
	 * method, but a 503 is not re-sent, for a GET nor for a POST.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testSingleEndpointKeepsHttpClientRetry() throws Exception {
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		Assertions.assertThrows(IntegrationException.class, () -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/drop", HttpMethod.GET, null));
		Assertions.assertEquals(2, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
		for (final HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST)) {
			ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
			final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
					() -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/unavailable", method, "body"));
			Assertions.assertEquals(Integer.valueOf(503), exception.getStatusCode());
			Assertions.assertEquals(1, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get(), method::name);
			ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
			Assertions.assertThrows(BusinessException.class, () -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/rate-limited", method, "body"));
			Assertions.assertEquals(2, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get(), method::name);
		}
	}

	/**
	 * A generated client fails over a refused endpoint in its endpoint list, for a
	 * GET and for a multipart PUT.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testGeneratedClientFailover() throws Exception {
		// Through the auto-configured client (shipped properties): every call succeeds.
		this.testService2Properties.setEndpoint(ServiceClientFailoverTest.REFUSED_ENDPOINT + ",http://localhost:${local.server.port}");
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			this.testService2Client.test1();
		}
		// The shipped properties reach the context balancer the generated clients use:
		// least in flight, and the refusing endpoint is in its cooldown.
		Assertions.assertEquals(ServiceClientEndpointBalancer.Strategy.LEAST_IN_FLIGHT, this.contextBalancer.getStrategy());
		Assertions.assertEquals(Duration.ofSeconds(10), this.contextBalancer.getFailureCooldown());
		Assertions.assertTrue(this.contextBalancer.isCoolingDown(ServiceClientFailoverTest.REFUSED_ENDPOINT));
		Assertions.assertFalse(this.contextBalancer.isCoolingDown("http://localhost:" + this.localServerPort));
		// Still through the auto-configured client (streamed body), the balancer
		// cleared before each call so the refusing endpoint is chosen first about half
		// of the time: a multipart PUT fails over and its body arrives whole.
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			this.contextBalancer.clear();
			final String content = "content " + callIndex;
			Assertions.assertEquals(content,
					new String(this.testService2Client.test3(new FileResource("test", content.getBytes(StandardCharsets.UTF_8))).getInputStream().readAllBytes(),
							StandardCharsets.UTF_8));
		}
	}

	/**
	 * A generated client whose endpoint list gets empty (as when its target has no
	 * healthy instance left) keeps calling its last non-empty list, then takes the
	 * new list when it is back: a refusing instance as the last list makes the
	 * calls in the empty period fail on it (not on a missing host), and the working
	 * one, once back, makes them succeed.
	 */
	@Test
	public void testGeneratedClientKeepsLastEndpoints() {
		this.testService2Properties.setEndpoint("http://localhost:${local.server.port}");
		Assertions.assertDoesNotThrow(() -> this.testService2Client.test1());
		for (final String emptyEndpoints : List.of("", " , ", ",")) {
			this.testService2Properties.setEndpoint(emptyEndpoints);
			Assertions.assertDoesNotThrow(() -> this.testService2Client.test1(), emptyEndpoints);
		}
		this.testService2Properties.setEndpoint(ServiceClientFailoverTest.REFUSED_ENDPOINT);
		Assertions.assertThrows(IntegrationException.class, () -> this.testService2Client.test1());
		this.testService2Properties.setEndpoint(" , ");
		final IntegrationException exception = Assertions.assertThrows(IntegrationException.class, () -> this.testService2Client.test1());
		Assertions.assertEquals("rest.operation.execution.error", exception.getInternalMessage().getCode());
		// It failed on the refusing endpoint: a refused connection, not a missing host.
		boolean isRefused = false;
		for (Throwable cause = exception; (cause != null) && !isRefused; cause = cause.getCause()) {
			isRefused = (cause instanceof java.net.ConnectException);
		}
		Assertions.assertTrue(isRefused, exception::toString);
		this.testService2Properties.setEndpoint(" , http://localhost:${local.server.port} ,");
		Assertions.assertDoesNotThrow(() -> this.testService2Client.test1());
	}

	/**
	 * The context's failover executor has the shipped time budget.
	 */
	@Test
	public void testShippedBudget() {
		Assertions.assertEquals(Duration.ofSeconds(10), this.failoverExecutor.getMaxTotalTime());
	}

	/**
	 * Under concurrent load, a slow instance gets fewer requests than the fast ones
	 * (fewer requests in flight wins).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testSlowEndpointGetsFewerRequests() throws Exception {
		// A client with a balancer of its own (no state left by other tests).
		final GenericRestServiceClient balancedClient = this.createRecordingClient(ServiceClientFailoverTest.createBalancer(Duration.ofSeconds(10)), null);
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.THIRD_SERVER));
		final ExecutorService callers = Executors.newFixedThreadPool(12);
		try {
			final List<Future<String>> calls = new ArrayList<>();
			for (Integer callIndex = 0; callIndex < 240; callIndex++) {
				calls.add(callers.submit(() -> ServiceClientFailoverTest.execute(balancedClient, endpoints, "/slow-on-first", HttpMethod.GET, null)));
			}
			for (final Future<String> call : calls) {
				call.get();
			}
		}
		finally {
			callers.shutdown();
		}
		final Integer slowRequests = ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get();
		Assertions.assertTrue(slowRequests < ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.get(), () -> "Slow instance got " + slowRequests);
		Assertions.assertTrue(slowRequests < ServiceClientFailoverTest.THIRD_SERVER_REQUESTS.get(), () -> "Slow instance got " + slowRequests);
		Assertions.assertTrue(slowRequests < 40, () -> "Slow instance got " + slowRequests);
	}

	/**
	 * An endpoint that refuses connections is skipped during the failure cooldown
	 * and tried again after it.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testRefusingEndpointCooldown() throws Exception {
		// A client whose balancer has a short cooldown (far longer than the calls made
		// during it).
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		final GenericRestServiceClient recordingClient = this.createRecordingClient(ServiceClientFailoverTest.createBalancer(Duration.ofSeconds(3)),
				attemptedUrls);
		final List<String> endpoints = List.of(ServiceClientFailoverTest.REFUSED_ENDPOINT,
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		// Calls until the refusing endpoint is tried once.
		for (Integer callIndex = 0; (callIndex < ServiceClientFailoverTest.REPEATED_CALLS)
				&& ServiceClientFailoverTest.countAttempts(attemptedUrls) == 0; callIndex++) {
			Assertions.assertEquals("first", ServiceClientFailoverTest.execute(recordingClient, endpoints, "/ok", HttpMethod.POST, "body"));
		}
		Assertions.assertEquals(1, ServiceClientFailoverTest.countAttempts(attemptedUrls));
		// During the cooldown it is skipped.
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			Assertions.assertEquals("first", ServiceClientFailoverTest.execute(recordingClient, endpoints, "/ok", HttpMethod.POST, "body"));
		}
		Assertions.assertEquals(1, ServiceClientFailoverTest.countAttempts(attemptedUrls));
		// After the cooldown it is tried again.
		Thread.sleep(3200);
		for (Integer callIndex = 0; (callIndex < ServiceClientFailoverTest.REPEATED_CALLS)
				&& ServiceClientFailoverTest.countAttempts(attemptedUrls) == 1; callIndex++) {
			Assertions.assertEquals("first", ServiceClientFailoverTest.execute(recordingClient, endpoints, "/ok", HttpMethod.POST, "body"));
		}
		Assertions.assertEquals(2, ServiceClientFailoverTest.countAttempts(attemptedUrls));
	}

	/**
	 * A connection dropped before a response is retried for a GET and not for a
	 * POST (the request may have been processed).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testDroppedConnection() throws Exception {
		// No cooldown, so the first endpoint keeps being chosen.
		final GenericRestServiceClient noCooldownClient = this.createRecordingClient(ServiceClientFailoverTest.createBalancer(Duration.ZERO), null);
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			Assertions.assertEquals("second", ServiceClientFailoverTest.execute(noCooldownClient, endpoints, "/drop-on-first", HttpMethod.GET, null));
		}
		Assertions.assertTrue(ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get() > 0);
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(noCooldownClient, endpoints, "/drop", HttpMethod.POST, "body"));
		Assertions.assertEquals(1, ServiceClientFailoverTest.getTotalRequests());
	}

	/**
	 * The operation's idempotency overrides the method: a POST marked idempotent is
	 * retried on a 503, a GET marked not idempotent is not.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testIdempotencyOverride() throws Exception {
		final GenericRestServiceClient noCooldownClient = this.createRecordingClient(ServiceClientFailoverTest.createBalancer(Duration.ZERO), null);
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(noCooldownClient, endpoints, "/unavailable", HttpMethod.POST, "body", Boolean.TRUE));
		Assertions.assertEquals(2, ServiceClientFailoverTest.getTotalRequests());
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(noCooldownClient, endpoints, "/unavailable", HttpMethod.GET, null, Boolean.FALSE));
		Assertions.assertEquals(1, ServiceClientFailoverTest.getTotalRequests());
	}

	/**
	 * The attempts are capped by the maximum and by the number of endpoints, and are
	 * one when failover is disabled.
	 */
	@Test
	public void testAttemptLimits() {
		final List<String> endpoints = List.of("http://first", "http://second", "http://third", "http://fourth");
		final ServiceClientFailoverExecutor cappedExecutor = new ServiceClientFailoverExecutor(true, 3, new String[] { "GET" }, new String[] { "503" }, new String[] { "503" }, false, ServiceClientFailoverTest.createBalancer(Duration.ZERO), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE);
		Assertions.assertEquals(3, cappedExecutor.getMaxAttempts(cappedExecutor.getAvailableEndpoints(endpoints).size()));
		Assertions.assertEquals(2, cappedExecutor.getMaxAttempts(cappedExecutor.getAvailableEndpoints(endpoints.subList(0, 2)).size()));
		Assertions.assertEquals(List.of(), cappedExecutor.getAvailableEndpoints(List.of(" ", "")));
		final ServiceClientFailoverExecutor disabledExecutor = new ServiceClientFailoverExecutor(false, 3, new String[] { "GET" }, new String[] { "503" }, new String[] { "503" },
				false, ServiceClientFailoverTest.createBalancer(Duration.ZERO), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE);
		Assertions.assertEquals(1, disabledExecutor.getMaxAttempts(endpoints.size()));
	}

	/**
	 * With the shipped defaults (no retry status, no cooldown status), a 5xx is
	 * neither retried on another endpoint nor puts the endpoint in its cooldown:
	 * between services it comes from the application, not from the instance.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testStatusesNeitherRetriedNorCooledDownByDefault() throws Exception {
		// The shipped properties retry no status and cool down on no status.
		final java.util.Properties shipped = new java.util.Properties();
		try (java.io.InputStream input = ServiceClientFailoverTest.class.getResourceAsStream("/service-client.properties")) {
			shipped.load(input);
		}
		Assertions.assertEquals("", shipped.getProperty("org.coldis.library.service-client.failover.retry-statuses"));
		Assertions.assertEquals("", shipped.getProperty("org.coldis.library.service-client.balancing.cooldown-statuses"));
		// An executor built with those defaults.
		final ServiceClientEndpointBalancer balancer = ServiceClientFailoverTest.createBalancer(Duration.ofSeconds(30));
		final GenericRestServiceClient defaultClient = this.createRecordingClient(balancer, null);
		defaultClient.setFailoverExecutor(new ServiceClientFailoverExecutor(true, 3, new String[] { "GET", "HEAD", "OPTIONS" }, null, null, false, balancer,
				ServiceClientRequestFactory.READ_TIMEOUT_SCOPE));
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		// A GET answered 503 reaches one instance only, once, and cools nothing down.
		final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(defaultClient, endpoints, "/unavailable", HttpMethod.GET, null));
		Assertions.assertEquals(Integer.valueOf(503), exception.getStatusCode());
		Assertions.assertEquals(1, ServiceClientFailoverTest.getTotalRequests());
		Assertions.assertEquals(0, balancer.getCooldownEndpointsCount());
		// A refused connection still fails over and still starts the cooldown.
		final List<String> withRefused = List.of(ServiceClientFailoverTest.REFUSED_ENDPOINT, ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		for (Integer callIndex = 0; (callIndex < ServiceClientFailoverTest.REPEATED_CALLS) && !balancer.isCoolingDown(ServiceClientFailoverTest.REFUSED_ENDPOINT); callIndex++) {
			Assertions.assertEquals("first", ServiceClientFailoverTest.execute(defaultClient, withRefused, "/ok", HttpMethod.GET, null));
		}
		Assertions.assertTrue(balancer.isCoolingDown(ServiceClientFailoverTest.REFUSED_ENDPOINT));
	}

	/**
	 * A retry status starts the endpoint's cooldown, a success or a non-retry status
	 * ends it, and no request is left counted in flight after failed attempts.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testOutcomesDriveCooldown() throws Exception {
		final ServiceClientEndpointBalancer balancer = ServiceClientFailoverTest.createBalancer(Duration.ofSeconds(30));
		final GenericRestServiceClient balancedClient = this.createRecordingClient(balancer, null);
		final String firstEndpoint = ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER);
		final String secondEndpoint = ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER);
		final List<String> endpoints = List.of(firstEndpoint, secondEndpoint);
		// Calls until the first instance answers a 503 (it then cools down).
		for (Integer callIndex = 0; (callIndex < ServiceClientFailoverTest.REPEATED_CALLS)
				&& (ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get() == 0); callIndex++) {
			Assertions.assertEquals("second", ServiceClientFailoverTest.execute(balancedClient, endpoints, "/unavailable-on-first", HttpMethod.GET, null));
		}
		Assertions.assertEquals(1, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
		// During the cooldown, first attempts skip it.
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			Assertions.assertEquals("second", ServiceClientFailoverTest.execute(balancedClient, endpoints, "/unavailable-on-first", HttpMethod.GET, null));
		}
		Assertions.assertEquals(1, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
		Assertions.assertEquals(0, balancer.getInFlightRequests(firstEndpoint));
		Assertions.assertEquals(0, balancer.getInFlightRequests(secondEndpoint));
		// A success from it (as the only candidate) ends the cooldown.
		Assertions.assertEquals(1, balancer.getCooldownEndpointsCount());
		ServiceClientFailoverTest.execute(balancedClient, List.of(firstEndpoint), "/ok", HttpMethod.GET, null);
		Assertions.assertEquals(0, balancer.getCooldownEndpointsCount());
		// After another 503, a 400 from it ends the cooldown too: it is chosen again.
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(balancedClient, List.of(firstEndpoint), "/unavailable", HttpMethod.GET, null));
		Assertions.assertEquals(1, balancer.getCooldownEndpointsCount());
		Assertions.assertThrows(BusinessException.class,
				() -> ServiceClientFailoverTest.execute(balancedClient, List.of(firstEndpoint), "/bad-request", HttpMethod.GET, null));
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		for (Integer callIndex = 0; (callIndex < ServiceClientFailoverTest.REPEATED_CALLS)
				&& (ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get() == 0); callIndex++) {
			ServiceClientFailoverTest.execute(balancedClient, endpoints, "/ok", HttpMethod.GET, null);
		}
		Assertions.assertTrue(ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get() > 0);
	}

	/**
	 * With read timeout retries enabled, a GET is retried after a read timeout and
	 * a POST is not.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testReadTimeoutRetriedWhenEnabled() throws Exception {
		final GenericRestServiceClient retryingClient = this
				.createShortTimeoutClient(new ServiceClientFailoverExecutor(true, 3, new String[] { "GET" }, new String[] { "503" }, new String[] { "503" }, true, ServiceClientFailoverTest.createBalancer(Duration.ZERO), ServiceClientRequestFactory.READ_TIMEOUT_SCOPE));
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(retryingClient, endpoints, "/slow", HttpMethod.GET, null));
		Assertions.assertEquals(1, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
		Assertions.assertEquals(1, ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.get());
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(retryingClient, endpoints, "/slow", HttpMethod.POST, "body"));
		Assertions.assertEquals(1, ServiceClientFailoverTest.getTotalRequests());
	}

	/**
	 * A client without a failover executor makes one attempt on one endpoint of the
	 * list (the HTTP client does not re-send a 503: one request).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testNoFailoverExecutor() throws Exception {
		final GenericRestServiceClient singleAttemptClient = this.createShortTimeoutClient(null);
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(singleAttemptClient, endpoints, "/unavailable", HttpMethod.GET, null));
		Assertions.assertEquals(1, ServiceClientFailoverTest.getTotalRequests());
	}

	/**
	 * The idempotency written by the generator reaches the call: against instances
	 * answering 503, the operation marked idempotent (a PUT) reaches both, the one
	 * marked not idempotent (a GET) reaches one.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testGeneratedClientIdempotency() throws Exception {
		this.testService2Properties.setEndpoint(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER) + ","
				+ ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		Assertions.assertThrows(IntegrationException.class,
				() -> this.testService2Client.test2(new DtoTestObjectDto().withId(1L), "1", "2", 3, new int[] { 4 }, List.of(5)));
		Assertions.assertEquals(1, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
		Assertions.assertEquals(1, ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.get());
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(IntegrationException.class, () -> this.testService2Client.test8());
		Assertions.assertEquals(1, ServiceClientFailoverTest.getTotalRequests());
	}

	/**
	 * Through a template that buffers the body (an interceptor), a stream body gets
	 * one attempt (the connect failure is reported, nothing reaches the other
	 * endpoint), while a byte-array body still fails over.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testStreamBodyThroughBufferingTemplate() throws Exception {
		final List<String> attemptedUrls = new CopyOnWriteArrayList<>();
		final GenericRestServiceClient bufferingClient = this.createShortTimeoutClient(this.failoverExecutor);
		bufferingClient.setRestTemplate(this.restTemplateBuilder.requestFactory(() -> new HttpComponentsClientHttpRequestFactory(this.httpClient))
				.additionalInterceptors((request, body, execution) -> {
					attemptedUrls.add(request.getURI().toString());
					return execution.execute(request, body);
				}).build());
		final List<String> endpoints = List.of(ServiceClientFailoverTest.REFUSED_ENDPOINT,
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		final MultiValueMap<String, String> headers = GenericRestServiceClient.addContentTypeHeaders(null, MediaType.MULTIPART_FORM_DATA_VALUE);
		IntegrationException failure = null;
		for (Integer callIndex = 0; (callIndex < ServiceClientFailoverTest.REPEATED_CALLS) && (failure == null); callIndex++) {
			final MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
			parts.add("file", new InputStreamResource(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8))));
			attemptedUrls.clear();
			ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
			try {
				bufferingClient.executeOperation(endpoints, "/echo", HttpMethod.POST, headers, parts, null, ServiceClientFailoverTest.STRING_RESPONSE, null);
			}
			catch (final IntegrationException exception) {
				failure = exception;
			}
		}
		Assertions.assertNotNull(failure);
		Assertions.assertTrue(ExceptionUtils.indexOfType(failure, ConnectException.class) >= 0, failure::toString);
		Assertions.assertEquals(1, attemptedUrls.size());
		Assertions.assertEquals(0, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
		// A byte-array body on the same template still fails over.
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			final MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
			parts.add("file", new FileResource("file.txt", ("content " + callIndex).getBytes(StandardCharsets.UTF_8)));
			Assertions.assertTrue(bufferingClient
					.executeOperation(endpoints, "/echo", HttpMethod.POST, headers, parts, null, ServiceClientFailoverTest.STRING_RESPONSE, null).getBody()
					.contains("content " + callIndex));
		}
	}

	/**
	 * The full-URL overload is unchanged apart from the HTTP client's retry: one
	 * attempt on the given URL, a 503 not re-sent (for a GET nor for a POST) and
	 * mapped to an integration exception, a 429 still re-sent once, and the body of
	 * a 200 returned.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testFullUrlOverloadResendsRateLimitedOnly() throws Exception {
		final String endpoint = ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER);
		for (final HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST)) {
			ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
			final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
					() -> this.serviceClient.executeOperation(endpoint + "/unavailable", method, null, "body", null, ServiceClientFailoverTest.STRING_RESPONSE));
			Assertions.assertEquals(Integer.valueOf(503), exception.getStatusCode());
			Assertions.assertEquals(1, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get(), method::name);
		}
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(BusinessException.class,
				() -> this.serviceClient.executeOperation(endpoint + "/rate-limited", HttpMethod.POST, null, "body", null, ServiceClientFailoverTest.STRING_RESPONSE));
		Assertions.assertEquals(2, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
		Assertions.assertEquals("first",
				this.serviceClient.executeOperation(endpoint + "/ok", HttpMethod.GET, null, null, null, ServiceClientFailoverTest.STRING_RESPONSE).getBody());
	}

	/**
	 * A 429 keeps the HTTP client's own delayed re-send on the same endpoint during
	 * a call with several attempts, and is not failed over (a client error); the
	 * HTTP client retries are back for the next single-attempt call.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testRateLimitedAndRetriesRestored() throws Exception {
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		Assertions.assertThrows(BusinessException.class,
				() -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/rate-limited", HttpMethod.GET, null));
		Assertions.assertEquals(2, ServiceClientFailoverTest.getTotalRequests());
		Assertions.assertTrue((ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get() == 0) || (ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.get() == 0));
		// After a failed call with several attempts, a single-attempt call gets the
		// HTTP client re-send again (of a GET whose connection dropped).
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(IntegrationException.class, () -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/drop", HttpMethod.GET, null));
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(IntegrationException.class, () -> ServiceClientFailoverTest.execute(this.serviceClient,
				List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER)), "/drop", HttpMethod.GET, null));
		Assertions.assertEquals(2, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
	}

	/**
	 * The client hooks run once per call on the endpoint-list overload: the header
	 * hook before the attempts (its header reaches the instances), the failure hook
	 * after the last attempt, with its failure and URL.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testClientHooksRunOncePerCall() throws Exception {
		final AtomicInteger headerHookCalls = new AtomicInteger();
		final List<String> handledUrls = new CopyOnWriteArrayList<>();
		final List<Exception> handledFailures = new CopyOnWriteArrayList<>();
		final GenericRestServiceClient hookedClient = new GenericRestServiceClient() {
			{
				this.setObjectMapper(ServiceClientFailoverTest.this.objectMapper);
			}

			@Override
			protected MultiValueMap<String, String> autoModifyHeaders(
					final MultiValueMap<String, String> headers) {
				headerHookCalls.incrementAndGet();
				return GenericRestServiceClient.addHeaders(headers, true, "X-Hook", "hooked");
			}

			@Override
			protected <ResponseType> ResponseEntity<ResponseType> autoHandleExecutionExceptions(
					final String path,
					final HttpMethod method,
					final MultiValueMap<String, String> headers,
					final Object body,
					final Map<String, Object> uriParameters,
					final ParameterizedTypeReference<ResponseType> responseType,
					final Exception operationException) throws Exception {
				handledUrls.add(path);
				handledFailures.add(operationException);
				throw operationException;
			}
		};
		hookedClient.setRestTemplate(this.serviceClient.getRestTemplate());
		hookedClient.setFailoverExecutor(this.failoverExecutor);
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		Assertions.assertThrows(IntegrationException.class,
				() -> hookedClient.executeOperation(endpoints, "/unavailable", HttpMethod.GET, null, null, null, ServiceClientFailoverTest.STRING_RESPONSE, null));
		Assertions.assertEquals(2, ServiceClientFailoverTest.getTotalRequests());
		Assertions.assertEquals("hooked", ServiceClientFailoverTest.LAST_HOOK_HEADER.get());
		Assertions.assertEquals(1, headerHookCalls.get());
		Assertions.assertEquals(1, handledFailures.size());
		Assertions.assertTrue(handledFailures.get(0) instanceof HttpServerErrorException, handledFailures.get(0)::toString);
		Assertions.assertTrue(handledUrls.get(0).endsWith("/unavailable"), handledUrls.get(0));
	}

	/**
	 * A generated client with its own read timeout times out at it, on every
	 * failover attempt; without one (or with zero or a negative one) it keeps the
	 * template's (9 seconds); a full-URL
	 * call is not affected by a generated client's read timeout.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testGeneratedClientReadTimeout() throws Exception {
		this.testService2Properties.setEndpoint(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		// Its own read timeout (1 second) against a 3 second answer.
		this.setService2ReadTimeoutSeconds(1L);
		long startNanos = System.nanoTime();
		final IntegrationException timeoutException = Assertions.assertThrows(IntegrationException.class, () -> this.testService2Client.test1("slow"));
		Assertions.assertTrue(ExceptionUtils.indexOfThrowable(timeoutException, SocketTimeoutException.class) >= 0, ExceptionUtils.getStackTrace(timeoutException));
		Assertions.assertTrue(Duration.ofNanos(System.nanoTime() - startNanos).compareTo(Duration.ofMillis(2500)) < 0);
		// A full-URL call in between keeps the template's read timeout: the answer
		// arrives after 3 seconds.
		startNanos = System.nanoTime();
		this.serviceClient.executeOperation(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER) + "/test2/slow", HttpMethod.GET,
				null, null, null, ServiceClientFailoverTest.STRING_RESPONSE);
		Assertions.assertTrue(Duration.ofNanos(System.nanoTime() - startNanos).compareTo(Duration.ofMillis(2900)) > 0);
		// Across a failover attempt (a refused endpoint first), the attempt that
		// reaches the instance still times out at 1 second.
		this.testService2Properties.setEndpoint(
				ServiceClientFailoverTest.REFUSED_ENDPOINT + "," + ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		for (Integer callIndex = 0; (callIndex < ServiceClientFailoverTest.REPEATED_CALLS)
				&& !this.contextBalancer.isCoolingDown(ServiceClientFailoverTest.REFUSED_ENDPOINT); callIndex++) {
			this.contextBalancer.clear();
			ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
			final long attemptStartNanos = System.nanoTime();
			final IntegrationException attemptException = Assertions.assertThrows(IntegrationException.class, () -> this.testService2Client.test1("slow"));
			Assertions.assertTrue(ExceptionUtils.indexOfThrowable(attemptException, SocketTimeoutException.class) >= 0,
					ExceptionUtils.getStackTrace(attemptException));
			Assertions.assertTrue(Duration.ofNanos(System.nanoTime() - attemptStartNanos).compareTo(Duration.ofMillis(2500)) < 0);
		}
		// The last call failed over from the refused endpoint to the instance, which
		// got the request and timed out.
		Assertions.assertTrue(this.contextBalancer.isCoolingDown(ServiceClientFailoverTest.REFUSED_ENDPOINT));
		Assertions.assertEquals(1, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
		// Without its own read timeout, the template's applies (the answer arrives).
		this.setService2ReadTimeoutSeconds(null);
		this.testService2Properties.setEndpoint(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		startNanos = System.nanoTime();
		this.testService2Client.test1("slow");
		Assertions.assertTrue(Duration.ofNanos(System.nanoTime() - startNanos).compareTo(Duration.ofMillis(2900)) > 0);
		// Zero or negative is not a read timeout of its own either.
		for (final Long ignoredReadTimeoutSeconds : List.of(0L, -1L)) {
			this.setService2ReadTimeoutSeconds(ignoredReadTimeoutSeconds);
			final long ignoredStartNanos = System.nanoTime();
			this.testService2Client.test1("slow");
			Assertions.assertTrue(Duration.ofNanos(System.nanoTime() - ignoredStartNanos).compareTo(Duration.ofMillis(2900)) > 0);
		}
	}

}
