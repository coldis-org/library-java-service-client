package org.coldis.library.test.service;

import org.coldis.library.helper.ReflectionHelper;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.StopTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.client.TestService2Client;
import org.coldis.library.test.service.client.TestServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.testcontainers.containers.GenericContainer;

/**
 * Base of the Spring tests: one configuration and one set of containers for every
 * test class, so all of them run on the same cached Spring context (its
 * connection properties point at these containers, which are started once and
 * kept between classes). Nothing may give a class its own context
 * ({@code @DirtiesContext}, {@code @TestPropertySource}, {@code @MockBean},
 * properties on {@code @SpringBootTest}): behaviour a class needs is switched at
 * runtime in its {@code beforeEach()}, after {@code super.beforeEach()}, which
 * puts every switch back to its default.
 */
@TestWithContainer(stopDelay = 600)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(StopTestWithContainerExtension.class)
public abstract class AbstractServiceTest extends TestHelper {

	/**
	 * Postgres container.
	 */
	public static GenericContainer<?> POSTGRES_CONTAINER = TestHelper.createPostgresContainer();

	/**
	 * Artemis container.
	 */
	public static GenericContainer<?> ARTEMIS_CONTAINER = TestHelper.createArtemisContainer();

	/**
	 * Generated service client.
	 */
	@Autowired
	private TestServiceClient testServiceClient;

	/**
	 * Generated service client.
	 */
	@Autowired
	private TestService2Client testService2Client;

	/**
	 * Sets if the generated clients call asynchronous operations synchronously
	 * ({@code always-sync}, off by default).
	 *
	 * @param isAlwaysSync If the generated clients call asynchronous operations
	 *                         synchronously.
	 */
	protected void setAlwaysSync(
			final Boolean isAlwaysSync) {
		ReflectionHelper.setAttribute(this.testServiceClient, "alwaysSync", isAlwaysSync);
		ReflectionHelper.setAttribute(this.testService2Client, "alwaysSync", isAlwaysSync);
	}

	/**
	 * Puts the runtime test switches back to their defaults before each test.
	 */
	@BeforeEach
	public void beforeEach() {
		this.setAlwaysSync(false);
	}

}
