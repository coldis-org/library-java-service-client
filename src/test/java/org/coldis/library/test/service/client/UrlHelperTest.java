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
	 * Tests {@link UrlHelper#reverseAmpHost(String)}.
	 */
	@Test
	public void testReverseAmpHost() {
		Assertions.assertNull(UrlHelper.reverseAmpHost(null));
		Assertions.assertEquals("", UrlHelper.reverseAmpHost(""));
		// Non-AMP hosts pass through unchanged.
		Assertions.assertEquals("example.com", UrlHelper.reverseAmpHost("example.com"));
		// Standard dot-only host: each "-" maps back to ".".
		Assertions.assertEquals("www.supersim.com.br", UrlHelper.reverseAmpHost("www-supersim-com-br.cdn.ampproject.org"));
		Assertions.assertEquals("example.com", UrlHelper.reverseAmpHost("example-com.cdn.ampproject.org"));
		// Hyphenated original host: "--" maps back to "-", remaining "-" maps to ".".
		Assertions.assertEquals("foo-bar.com", UrlHelper.reverseAmpHost("foo--bar-com.cdn.ampproject.org"));
		Assertions.assertEquals("a-b-c.example.com", UrlHelper.reverseAmpHost("a--b--c-example-com.cdn.ampproject.org"));
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

		// AMP host reverses to its original form, then www is stripped.
		Assertions.assertEquals("supersim.com.br", UrlHelper.normalizeHost("www-supersim-com-br.cdn.ampproject.org"));
		Assertions.assertEquals("foo-bar.com", UrlHelper.normalizeHost("foo--bar-com.cdn.ampproject.org"));

		// Long real-world utm_source with chained %26-separated tracking parameters:
		// %26 decodes to &, stripQuery truncates at the first &.
		Assertions.assertEquals("google",
				UrlHelper.normalizeHost("google%26utm_medium=cpc%26utm_campaign=defesa-de-marca"
						+ "%26utm_content=psq-defesa-de-marca%26gad_source=1"
						+ "%26gad_campaignid=21958736359"
						+ "%26gclid=cj0kcqiak6rnbhcxarisan5mqltersz9q5pisjmcwqlnbacctw2s2hzcb2tqwp2fkcdy-yc2owvp4isaavjgealw_wcb,1"));
	}

	/**
	 * Tests {@link UrlHelper#getUtm(String, String, List, List)} against a real-world
	 * {@code utm_source} value where the upstream system collapsed an entire UTM
	 * query string (encoded with {@code %26} as the separator) into the source
	 * field. The helper should URL-decode the value, recognize the embedded
	 * query, and pull out the individual UTM tags.
	 */
	@Test
	public void testUtmFromEncodedSource() {
		final String source = "google%26utm_medium=cpc%26utm_campaign=defesa-de-marca"
				+ "%26utm_content=psq-defesa-de-marca%26gad_source=1"
				+ "%26gad_campaignid=21958736359"
				+ "%26gclid=cj0kcqiak6rnbhcxarisan5mqltersz9q5pisjmcwqlnbacctw2s2hzcb2tqwp2fkcdy-yc2owvp4isaavjgealw_wcb,1";
		final Map<String, String> utm = UrlHelper.getUtm(source, null, List.of("coldis.org"), UrlHelper.SEARCH_ENGINES);
		Assertions.assertEquals("google", utm.get("source"));
		Assertions.assertEquals("cpc", utm.get("medium"));
		Assertions.assertEquals("defesa-de-marca", utm.get("campaign"));
		Assertions.assertEquals("psq-defesa-de-marca", utm.get("content"));
	}

}
