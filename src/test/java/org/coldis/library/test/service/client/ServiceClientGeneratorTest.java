package org.coldis.library.test.service.client;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.model.FileResource;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.service.client.dto.DtoTestObject2Dto;
import org.coldis.library.test.service.client.dto.DtoTestObjectDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.io.Resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service client generator test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
public class ServiceClientGeneratorTest extends TestHelper {

	/**
	 * Test data.
	 */
	private static final DtoTestObjectDto[] TEST_DATA = { new DtoTestObjectDto().withId(10L).withTest1(new DtoTestObject2Dto().withId(1L).withTest("test1"))
			.withTest2(List.of(new DtoTestObject2Dto().withId(2L).withTest("test2"), new DtoTestObject2Dto().withId(21L).withTest("test21"))).withTest3("test3")
			.withTest4(new DtoTestObject2Dto().withId(4L).withTest("test4")).withTest5("test5")
			.withTest6(new DtoTestObject2Dto[] { new DtoTestObject2Dto().withId(6L).withTest("test6"), new DtoTestObject2Dto().withId(61L).withTest("test61") })
			.withTest7(7).withTest88(new int[] { 2, 3, 4 }).withTest9(9) };

	/**
	 * Test file.
	 */
	@Value(value = "classpath:test.txt")
	private Resource testFile;

	/**
	 * Object mapper.
	 */
	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * Test service.
	 */
	@Autowired
	private TestService service;

	/**
	 * Test service.
	 */
	@Autowired
	private TestService2 service2;

	/**
	 * Test service client.
	 */
	@Autowired
	private TestServiceClient serviceClient;

	/**
	 * Test service client.
	 */
	@Autowired
	private TestService2Client service2Client;

	/**
	 * Tests the service client creation.
	 *
	 * @throws Exception If the test does not succeed.
	 */
	@Test
	public void testServiceClientCreation() throws Exception {
		// For each test data.
		for (final DtoTestObjectDto originalDto : ServiceClientGeneratorTest.TEST_DATA) {
			// Tests the first service.
			this.serviceClient.test1();
			// Clones the test object.
			final DtoTestObjectDto clonedDto = ObjectMapperHelper.deepClone(this.objectMapper, originalDto, null, new TypeReference<DtoTestObjectDto>() {},
					false);
			// Re-sets the attributes to be changed in the service call.
			clonedDto.setTest3("1");
			clonedDto.setTest5("2");
			clonedDto.setTest7(3);
			clonedDto.setTest88(new int[] { 4, 5 });
			clonedDto.setTest10(new ArrayList<>(List.of(6, 7)));
			// Makes sure the object is transformed as expected on service call.
			Assertions.assertEquals(clonedDto, this.serviceClient.test2(originalDto, "1", "2", 3, new int[] { 4, 5 }, new ArrayList<>(List.of(6, 7))));
			// Re-sets the attributes to be changed in the service call.
			clonedDto.setTest3("5");
			clonedDto.setTest5("6");
			clonedDto.setTest7(7);
			clonedDto.setTest88(new int[] { 8, 9 });
			clonedDto.setTest10(new ArrayList<>(List.of(6, 7)));
			// Makes sure the object is transformed as expected on service call.
			Assertions.assertEquals(clonedDto, this.serviceClient.test2(originalDto, "5", "6", 7, new int[] { 8, 9 }, new ArrayList<>(List.of(6, 7))));
			// Serializes the test object.
			final String serializedDto = ObjectMapperHelper.serialize(this.objectMapper, originalDto, null, false);
			// Executes the operation and de-serializes the object.
			final DtoTestObjectDto deserializedDto = ObjectMapperHelper.deserialize(this.objectMapper,
					new String(IOUtils.toByteArray(this.serviceClient.test3(new FileResource("test", serializedDto.getBytes())).getInputStream())),
					new TypeReference<DtoTestObjectDto>() {}, true);
			// Asserts that the response is the serialized object.
			Assertions.assertEquals(originalDto, deserializedDto);

			Assertions.assertEquals(null, this.serviceClient.test3(null));
			// Asserts that the response is the same as the request (converted to integer).
			Assertions.assertEquals(Integer.valueOf(1), this.serviceClient.test4(1L));
			this.serviceClient.test5Async(new JmsMessage().withMessage(5L));
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
				return this.service.getState();
			}, state -> (state.get("test5") != null) && ((long) state.get("test5") == 5L), TestHelper.VERY_LONG_WAIT, TestHelper.SHORT_WAIT));
			Assertions.assertEquals(Integer.valueOf(11), this.serviceClient.test6(11L).get("test6"));
		}
		// Asserts that multipart request succeed.
		Assertions.assertEquals("Test", this.serviceClient.test7(List.of(this.testFile)));
	}

	/**
	 * Tests the service client creation with information inherited from Spring
	 * annotations.
	 *
	 * @throws Exception If the test does not succeed.
	 */
	@Test
	public void testServiceClientCreationInherited() throws Exception {
		// For each test data.
		for (final DtoTestObjectDto originalDto : ServiceClientGeneratorTest.TEST_DATA) {
			// Tests the first service.
			this.service2Client.test1();
			// Clones the test object.
			final DtoTestObjectDto clonedDto = ObjectMapperHelper.deepClone(this.objectMapper, originalDto, null, new TypeReference<DtoTestObjectDto>() {},
					false);
			// Re-sets the attributes to be changed in the service call.
			clonedDto.setTest3("1");
			clonedDto.setTest5("2");
			clonedDto.setTest7(3);
			clonedDto.setTest88(new int[] { 4, 5 });
			clonedDto.setTest10(new ArrayList<>(List.of(6, 7)));
			// Makes sure the object is transformed as expected on service call.
			Assertions.assertEquals(clonedDto, this.service2Client.test2(originalDto, "1", "2", 3, new int[] { 4, 5 }, new ArrayList<>(List.of(6, 7))));
			// Re-sets the attributes to be changed in the service call.
			clonedDto.setTest3("5");
			clonedDto.setTest5("6");
			clonedDto.setTest7(7);
			clonedDto.setTest88(new int[] { 8, 9 });
			clonedDto.setTest10(new ArrayList<>(List.of(6, 7)));
			// Makes sure the object is transformed as expected on service call.
			Assertions.assertEquals(clonedDto, this.service2Client.test2(originalDto, "5", "6", 7, new int[] { 8, 9 }, new ArrayList<>(List.of(6, 7))));
			// Serializes the test object.
			final String serializedDto = ObjectMapperHelper.serialize(this.objectMapper, originalDto, null, false);
			// Executes the operation and de-serializes the object.
			final DtoTestObjectDto deserializedDto = ObjectMapperHelper.deserialize(this.objectMapper,
					new String(IOUtils.toByteArray(this.service2Client.test3(new FileResource("test", serializedDto.getBytes())).getInputStream())),
					new TypeReference<DtoTestObjectDto>() {}, true);
			// Asserts that the response is the serialized object.
			Assertions.assertEquals(originalDto, deserializedDto);
			// Asserts that the response is the same as the request (converted to integer).
			Assertions.assertEquals(Integer.valueOf(1), this.service2Client.test4(1L));
			this.service2Client.test5Async(new JmsMessage().withMessage(5L));
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
				return this.service2.getState();
			}, state -> (state.get("test5") != null) && ((long) state.get("test5") == 5L), TestHelper.VERY_LONG_WAIT, TestHelper.SHORT_WAIT));
			Assertions.assertEquals(Integer.valueOf(11), this.service2Client.test6(11L).get("test6"));
		}
	}

}
