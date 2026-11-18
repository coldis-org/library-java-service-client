package org.coldis.library.test.service.client;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.coldis.library.test.service.AbstractServiceTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Service client generator test for a client source that extends
 * {@link org.coldis.library.service.client.GenericRestServiceClient}
 * ({@link TestRestIntegration}): its generated client must expose its own
 * operations only, none of the generic client's methods (copied into the client,
 * the generic {@code executeOperation} overloads do not even compile: the test
 * build fails before this test runs).
 */
public class ServiceClientGeneratorInheritanceTest extends AbstractServiceTest {

	/**
	 * Public methods every generated client declares (from the template).
	 */
	private static final Set<String> TEMPLATE_METHODS = Set.of("setServiceClient", "setApplicationContext", "setEmbeddedValueResolver");

	/**
	 * Generated client of a source that extends the generic REST service client.
	 */
	@Autowired
	private TestRestIntegrationClient testRestIntegrationClient;

	/**
	 * Tests that the generated client exposes the source's own operation and none
	 * of the generic REST service client methods.
	 */
	@Test
	public void testGeneratedClientExposesOnlyOwnOperations() {
		final Set<String> operations = Arrays.stream(TestRestIntegrationClient.class.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()).map(Method::getName)
				.filter(methodName -> !ServiceClientGeneratorInheritanceTest.TEMPLATE_METHODS.contains(methodName)).collect(Collectors.toSet());
		Assertions.assertEquals(Set.of("test4"), operations);
	}

	/**
	 * Tests that the generated client's operation calls the service.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testGeneratedClientOperationCallsService() throws Exception {
		Assertions.assertEquals(Long.valueOf(42L), this.testRestIntegrationClient.test4(42L));
	}

}
