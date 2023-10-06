package org.coldis.library.test.service.client;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Service client generator test.
 */
@SpringBootTest(
		webEnvironment = WebEnvironment.DEFINED_PORT,
		properties = "org.coldis.library.service-client.always-sync=true"
)
public class ServiceClientGeneratorNoAsyncTest extends ServiceClientGeneratorTest {

}
