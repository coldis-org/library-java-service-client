package org.coldis.library.test.service.client;

import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.StopTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Service client generator test.
 */
@TestWithContainer
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "org.coldis.library.service-client.always-sync=true"
)
@ExtendWith(StopTestWithContainerExtension.class)
public class ServiceClientGeneratorNoAsyncTest extends ServiceClientGeneratorTest {

}
