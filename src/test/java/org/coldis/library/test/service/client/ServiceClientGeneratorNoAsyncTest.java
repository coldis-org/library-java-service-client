package org.coldis.library.test.service.client;

import org.coldis.library.test.ContainerExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Service client generator test.
 */
@ExtendWith(ContainerExtension.class)
@SpringBootTest(
		webEnvironment = WebEnvironment.DEFINED_PORT,
		properties = "org.coldis.library.service-client.always-sync=true"
)
public class ServiceClientGeneratorNoAsyncTest extends ServiceClientGeneratorTest {
	

}
