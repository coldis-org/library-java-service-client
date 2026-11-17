package org.coldis.library.test.service.client;

import org.junit.jupiter.api.BeforeEach;

/**
 * Service client generator test, with the generated clients calling the
 * asynchronous operations synchronously ({@code always-sync}), switched on at
 * runtime so the test runs on the same Spring context as the others.
 */
public class ServiceClientGeneratorNoAsyncTest extends ServiceClientGeneratorTest {

	/**
	 * Makes the generated clients call asynchronous operations synchronously.
	 *
	 * @see org.coldis.library.test.service.AbstractServiceTest#beforeEach()
	 */
	@Override
	@BeforeEach
	public void beforeEach() {
		super.beforeEach();
		this.setAlwaysSync(true);
	}

}
