package org.coldis.library.test.service.client;

import java.util.List;
import java.util.Map;

import org.coldis.library.serialization.json.JsonHelper;
import org.coldis.library.test.service.client.dto.DtoTestObject2Dto;
import org.coldis.library.test.service.client.dto.DtoTestObjectDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service client generator test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
public class ServiceClientGeneratorTest {

	/**
	 * Simples JSON object test data.
	 */
	private static final DtoTestObjectDto[] TEST_DATA = { new DtoTestObjectDto(10L, new DtoTestObject2Dto(1L, "test1"),
			List.of(new DtoTestObject2Dto(2L, "test2"), new DtoTestObject2Dto(3L, "test3")), "a",
			Map.of("id", 4L, "test", "test4"), "b",
			new DtoTestObject2Dto[] { new DtoTestObject2Dto(5L, "test5"), new DtoTestObject2Dto(6L, "test6") }, 1,
			new int[] { 2, 3, 4 }, 5) };

	/**
	 * Object mapper.
	 */
	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * Test service client.
	 */
	@Autowired
	private TestServiceClient serviceClient;

	/**
	 * Tests the service client creation.
	 *
	 * @throws Exception If the test does not succeed.
	 */
	@Test
	public void test00DtoCreation() throws Exception {
		// For each test data.
		for (final DtoTestObjectDto originalDto : ServiceClientGeneratorTest.TEST_DATA) {
			// Tests the first service.
			this.serviceClient.test1();
			// Clones the test object.
			final DtoTestObjectDto clonedDto = JsonHelper.deepClone(this.objectMapper, originalDto,
					new TypeReference<DtoTestObjectDto>() {
			});
			// Re-sets the attributes to be changed in the service call.
			clonedDto.setTest3("1");
			clonedDto.setTest5("2");
			clonedDto.setTest7(3);
			clonedDto.setTest88(new int[] { 4 });
			// Makes sure the object is transformed as expected on service call.
			Assertions.assertEquals(clonedDto, this.serviceClient.test2(originalDto, "1", "2", 3, new int[] { 4 }));
			// Re-sets the attributes to be changed in the service call.
			clonedDto.setTest3("5");
			clonedDto.setTest5("6");
			clonedDto.setTest7(7);
			clonedDto.setTest88(new int[] { 8 });
			// Makes sure the object is transformed as expected on service call.
			Assertions.assertEquals(clonedDto, this.serviceClient.test2(originalDto, "5", "6", 7, new int[] { 8 }));

		}
	}

}
