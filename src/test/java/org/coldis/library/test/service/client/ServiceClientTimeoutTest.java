package org.coldis.library.test.service.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.StopTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;

import com.sun.net.httpserver.HttpServer;

/**
 * Service client timeouts test.
 */
@TestWithContainer
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(StopTestWithContainerExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ServiceClientTimeoutTest extends TestHelper {

	/**
	 * Postgres container.
	 */
	public static GenericContainer<?> POSTGRES_CONTAINER = TestHelper.createPostgresContainer();

	/**
	 * Artemis container.
	 */
	public static GenericContainer<?> ARTEMIS_CONTAINER = TestHelper.createArtemisContainer();

	/**
	 * How long the slow endpoint takes to answer.
	 */
	private static final Duration SLOW_RESPONSE_DELAY = Duration.ofSeconds(2);

	/**
	 * An address that does not answer (connect attempts time out).
	 */
	private static final String UNROUTABLE_URL = "http://10.255.255.1:81/";

	/**
	 * Slow HTTP server.
	 */
	private static HttpServer SLOW_SERVER;

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
	 * Starts a server that answers after a delay.
	 *
	 * @throws IOException If the server cannot start.
	 */
	@BeforeAll
	public static void startSlowServer() throws IOException {
		ServiceClientTimeoutTest.SLOW_SERVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		ServiceClientTimeoutTest.SLOW_SERVER.createContext("/slow", exchange -> {
			try {
				Thread.sleep(ServiceClientTimeoutTest.SLOW_RESPONSE_DELAY.toMillis());
			}
			catch (final InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			final byte[] response = "slow".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream responseBody = exchange.getResponseBody()) {
				responseBody.write(response);
			}
		});
		ServiceClientTimeoutTest.SLOW_SERVER.start();
	}

	/**
	 * Stops the slow server.
	 */
	@AfterAll
	public static void stopSlowServer() {
		ServiceClientTimeoutTest.SLOW_SERVER.stop(0);
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
		// Builds another short timeout template, and the default template (9s read)
		// still waits.
		this.buildRestTemplate(this.firstRequestFactory, Duration.ofSeconds(1));
		Assertions.assertEquals("slow", this.restTemplate.getForObject(ServiceClientTimeoutTest.getSlowUrl(), String.class));
	}

	/**
	 * A connect to an address that does not answer fails within the default
	 * template connect timeout (3s), not its read timeout (9s).
	 */
	@Test
	public void testDefaultTemplateConnectTimeout() {
		final long startNanos = System.nanoTime();
		final ResourceAccessException exception = Assertions.assertThrows(ResourceAccessException.class,
				() -> this.restTemplate.getForObject(ServiceClientTimeoutTest.UNROUTABLE_URL, String.class));
		final Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
		Assertions.assertTrue(ExceptionUtils.indexOfType(exception, ConnectTimeoutException.class) >= 0, () -> "Not a connect timeout: " + exception);
		Assertions.assertTrue(elapsed.compareTo(Duration.ofSeconds(5)) < 0, () -> "Connect took " + elapsed);
	}

}
