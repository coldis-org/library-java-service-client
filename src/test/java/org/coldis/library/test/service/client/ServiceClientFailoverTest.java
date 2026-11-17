package org.coldis.library.test.service.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hc.client5.http.classic.HttpClient;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.client.ServiceClientFailoverExecutor;
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
	 * <li>{@code /unavailable}, {@code /test2/**} (the generated client's paths):
	 * 503.</li>
	 * <li>{@code /unavailable-on-first}: 503 on the first instance, 200 on the
	 * second.</li>
	 * <li>{@code /slow}: 200 after 3 seconds.</li>
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
			if ("/unavailable".equals(path) || path.startsWith("/test2") || ("/unavailable-on-first".equals(path) && "first".equals(name))) {
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
		final List<String> endpoints = List.of(ServiceClientFailoverTest.REFUSED_ENDPOINT,
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			Assertions.assertEquals("first", ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/ok", HttpMethod.POST, "body"));
		}
		Assertions.assertEquals(ServiceClientFailoverTest.REPEATED_CALLS, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
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
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			Assertions.assertEquals("second", ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/unavailable-on-first", HttpMethod.GET, null));
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
			final String response = this.serviceClient
					.executeOperation(endpoints, "/echo", HttpMethod.POST, headers, parts, null, ServiceClientFailoverTest.STRING_RESPONSE, null).getBody();
			Assertions.assertTrue(response.contains(content), response);
		}
	}

	/**
	 * A single endpoint gets one attempt, through the client's own template: the
	 * HTTP client's automatic retry stays as it was before failover (one re-send on
	 * a 503), so the request reaches the instance twice, not three times.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testSingleEndpointKeepsHttpClientRetry() throws Exception {
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER));
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/unavailable", HttpMethod.GET, null));
		Assertions.assertEquals(2, ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get());
	}

	/**
	 * A generated client fails over a refused endpoint in its endpoint list, for a
	 * GET and for a multipart PUT.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testGeneratedClientFailover() throws Exception {
		this.testService2Properties.setEndpoint(ServiceClientFailoverTest.REFUSED_ENDPOINT + ",http://localhost:${local.server.port}");
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			this.testService2Client.test1();
			final String content = "content " + callIndex;
			Assertions.assertEquals(content,
					new String(this.testService2Client.test3(new FileResource("test", content.getBytes(StandardCharsets.UTF_8))).getInputStream().readAllBytes(),
							StandardCharsets.UTF_8));
		}
	}

	/**
	 * A connection dropped before a response is retried for a GET and not for a
	 * POST (the request may have been processed).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testDroppedConnection() throws Exception {
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		for (Integer callIndex = 0; callIndex < ServiceClientFailoverTest.REPEATED_CALLS; callIndex++) {
			Assertions.assertEquals("second", ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/drop-on-first", HttpMethod.GET, null));
		}
		Assertions.assertTrue(ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get() > 0);
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/drop", HttpMethod.POST, "body"));
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
		final List<String> endpoints = List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER),
				ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.SECOND_SERVER));
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/unavailable", HttpMethod.POST, "body", Boolean.TRUE));
		Assertions.assertEquals(2, ServiceClientFailoverTest.getTotalRequests());
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(IntegrationException.class,
				() -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/unavailable", HttpMethod.GET, null, Boolean.FALSE));
		Assertions.assertEquals(1, ServiceClientFailoverTest.getTotalRequests());
	}

	/**
	 * The attempts are capped by the maximum and by the number of endpoints, and are
	 * one when failover is disabled.
	 */
	@Test
	public void testAttemptLimits() {
		final List<String> endpoints = List.of("http://first", "http://second", "http://third", "http://fourth");
		final ServiceClientFailoverExecutor cappedExecutor = new ServiceClientFailoverExecutor(true, 3, new String[] { "GET" }, new String[] { "503" }, false);
		Assertions.assertEquals(3, cappedExecutor.getMaxAttempts(cappedExecutor.getAvailableEndpoints(endpoints).size()));
		Assertions.assertEquals(2, cappedExecutor.getMaxAttempts(cappedExecutor.getAvailableEndpoints(endpoints.subList(0, 2)).size()));
		Assertions.assertEquals(List.of(""), cappedExecutor.getAvailableEndpoints(List.of(" ", "")));
		final ServiceClientFailoverExecutor disabledExecutor = new ServiceClientFailoverExecutor(false, 3, new String[] { "GET" }, new String[] { "503" },
				false);
		Assertions.assertEquals(1, disabledExecutor.getMaxAttempts(endpoints.size()));
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
				.createShortTimeoutClient(new ServiceClientFailoverExecutor(true, 3, new String[] { "GET" }, new String[] { "503" }, true));
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
	 * list (and keeps the HTTP client's own retry: two requests on a 503).
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
		Assertions.assertEquals(2, ServiceClientFailoverTest.getTotalRequests());
		Assertions.assertTrue((ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.get() == 0) || (ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.get() == 0));
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
	 * The full-URL overload is unchanged: one attempt on the given URL, with the
	 * HTTP client's own re-send on a 503, the 503 mapped to an integration
	 * exception, and the body of a 200 returned.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testFullUrlOverloadUnchanged() throws Exception {
		final String endpoint = ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER);
		final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
				() -> this.serviceClient.executeOperation(endpoint + "/unavailable", HttpMethod.GET, null, null, null, ServiceClientFailoverTest.STRING_RESPONSE));
		Assertions.assertEquals(Integer.valueOf(503), exception.getStatusCode());
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
		// HTTP client re-send again.
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		ServiceClientFailoverTest.SECOND_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(IntegrationException.class, () -> ServiceClientFailoverTest.execute(this.serviceClient, endpoints, "/unavailable", HttpMethod.GET, null));
		ServiceClientFailoverTest.FIRST_SERVER_REQUESTS.set(0);
		Assertions.assertThrows(IntegrationException.class, () -> ServiceClientFailoverTest.execute(this.serviceClient,
				List.of(ServiceClientFailoverTest.getEndpoint(ServiceClientFailoverTest.FIRST_SERVER)), "/unavailable", HttpMethod.GET, null));
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

}
