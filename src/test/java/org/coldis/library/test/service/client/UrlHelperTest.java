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

	/**
	 * Tests {@link UrlHelper#safeUrlDecode(String)}.
	 */
	@Test
	public void testSafeUrlDecode() {
		Assertions.assertNull(UrlHelper.safeUrlDecode(null));
		Assertions.assertEquals("", UrlHelper.safeUrlDecode(""));
		Assertions.assertEquals("hello world", UrlHelper.safeUrlDecode("hello%20world"));
		Assertions.assertEquals("a&b=c", UrlHelper.safeUrlDecode("a%26b%3Dc"));
		Assertions.assertEquals("https://www.ticket.com.br/", UrlHelper.safeUrlDecode("https%3a%2f%2fwww.ticket.com.br%2f"));
		// Invalid-UTF-8 percent-escape: URLDecoder produces the Unicode replacement
		// character rather than throwing. normalizeHost then truncates at it.
		Assertions.assertEquals("host�sf", UrlHelper.safeUrlDecode("host%fdsf"));
	}

	/**
	 * Tests {@link UrlHelper#stripProtocol(String)}.
	 */
	@Test
	public void testStripProtocol() {
		Assertions.assertNull(UrlHelper.stripProtocol(null));
		Assertions.assertEquals("", UrlHelper.stripProtocol(""));
		Assertions.assertEquals("example.com", UrlHelper.stripProtocol("http://example.com"));
		Assertions.assertEquals("example.com", UrlHelper.stripProtocol("https://example.com"));
		Assertions.assertEquals("example.com", UrlHelper.stripProtocol("HTTPS://example.com"));
		Assertions.assertEquals("example.com", UrlHelper.stripProtocol("example.com"));
		Assertions.assertEquals("ftp://example.com", UrlHelper.stripProtocol("ftp://example.com"));
	}

	/**
	 * Tests {@link UrlHelper#stripQuery(String)}.
	 */
	@Test
	public void testStripQuery() {
		Assertions.assertNull(UrlHelper.stripQuery(null));
		Assertions.assertEquals("host", UrlHelper.stripQuery("host?utm_source=x"));
		Assertions.assertEquals("host", UrlHelper.stripQuery("host&utm_source=x"));
		// Malformed unicode-escape leaked into raw value.
		Assertions.assertEquals("gridmidia.com", UrlHelper.stripQuery("gridmidia.comu0026utm_medium=ct.com"));
		// Literal "%26" survived without being URL-decoded.
		Assertions.assertEquals("admitad", UrlHelper.stripQuery("admitad%26utm_campaign=123"));
		// Any stray "%" is treated as garbage and truncated.
		Assertions.assertEquals("host", UrlHelper.stripQuery("host%fdsf"));
	}

	/**
	 * Tests {@link UrlHelper#normalizeHost(String)}.
	 */
	@Test
	public void testNormalizeHost() {
		Assertions.assertNull(UrlHelper.normalizeHost(null));
		Assertions.assertNull(UrlHelper.normalizeHost(""));
		Assertions.assertNull(UrlHelper.normalizeHost("   "));

		// Lowercase, strip www, strip trailing slash.
		Assertions.assertEquals("google.com", UrlHelper.normalizeHost("WWW.Google.Com/"));

		// Strip protocol + query.
		Assertions.assertEquals("ticket.com.br", UrlHelper.normalizeHost("https://www.ticket.com.br/?utm_source=x"));

		// URL-decode percent-encoded URL before further cleanup.
		Assertions.assertEquals("ticket.com.br", UrlHelper.normalizeHost("https%3a%2f%2fwww.ticket.com.br%2f"));

		// Truncate at malformed unicode-escape that leaked into the value.
		Assertions.assertEquals("gridmidia.com", UrlHelper.normalizeHost("gridmidia.comu0026utm_medium=ct.com"));

		// Garbage %XX that does not decode cleanly gets truncated.
		Assertions.assertEquals("host", UrlHelper.normalizeHost("host%fdsf"));

		// Non-www subdomain is preserved (callers strip channel-specific prefixes).
		Assertions.assertEquals("m.facebook.com", UrlHelper.normalizeHost("m.facebook.com"));
	}

}
