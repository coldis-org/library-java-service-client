package org.coldis.library.test.service.client;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.coldis.library.service.helper.UrlHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * URL helper test.
 */
public class UrlHelperTest {

	/**
	 * UTM test data.
	 */
	private static final Map<Map<String, String>, Map<String, String>> UTM_TEST_DATA = Map.of(
			Map.of("referrer", "https://www.domain1.com.br/", "source",
					"http://www.dev.coldis.org/path/domain1/"
							+ "?utm_source=domain1.com.br&utm_medium=partner&utm_campaign=std_campaign&utm_content=std_content"),
			Map.of("source", "domain1.com.br", "medium", "partner", "campaign", "std_campaign", "content", "std_content"),

			Map.of("referrer", "https://domain2.net.br", "source", "http://domain2..api.coldis.org/domain2/test/*/1"),
			Map.of("source", "domain2.net.br", "medium", "referral"),

			Map.of("referrer", "https://coldis.org", "source", "http://coldis.org"), Map.of("source", "direct", "medium", "link"),

			Map.of("referrer", "coldis-org.cdn.ampproject.org", "source", "coldis.org?amp"), Map.of("source", "www.google.com", "medium", "organic"),

			Map.of("referrer", "coldis.org", "source", "coldis.org?amp"), Map.of("source", "www.google.com", "medium", "organic"),

			Map.of("referrer", "coldis.org?amp", "source", "http://coldis.org"), Map.of("source", "www.google.com", "medium", "organic")

	);

	/**
	 * Tests UTM variables.
	 */
	@Test
	public void testUtm() {
		// For each test data.
		for (final Entry<Map<String, String>, Map<String, String>> testData : UrlHelperTest.UTM_TEST_DATA.entrySet()) {
			// Makes sure the UTM variables match.
			Assertions.assertEquals(testData.getValue(),
					UrlHelper.getUtm(testData.getKey().get("source"), testData.getKey().get("referrer"), List.of("coldis.org"), UrlHelper.SEARCH_ENGINES));
		}
	}

}
