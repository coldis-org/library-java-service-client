package org.coldis.library.service.helper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * URL helper.
 */
public class UrlHelper {

	/**
	 * Search engines.
	 */
	public static final List<String> SEARCH_ENGINES = List.of("www.google.", "www.yahoo.", "www.bing.");

	/**
	 * Gets the UTM source for the request.
	 *
	 * @param  source     Source URL.
	 * @param  referrer   Referrer URL.
	 * @param  ownDomains Own domains.
	 * @return            The UTM source for the request.
	 */
	private static String getUtmSource(final UriComponents source, final UriComponents referrer, final List<String> ownDomains) {
		// UTM source.
		String utmSource = null;
		// If there is a source.
		if (source != null) {
			// Tries to get the UTM source.
			utmSource = source.getQueryParams().getFirst("utm_source");
		}
		// If there is a referrer.
		if (StringUtils.isEmpty(utmSource) && (referrer != null) && (referrer.getHost() != null)
				&& ownDomains.stream().noneMatch(ownDomain -> (ownDomain != null) && referrer.getHost().toLowerCase().startsWith(ownDomain.toLowerCase()))) {
			// If the UTM source is not defined.
			if (StringUtils.isEmpty(utmSource)) {
				// The source is the domain.
				utmSource = referrer.getHost();
			}
		}
		// If the UTM source is "Direct" if empty.
		utmSource = StringUtils.isEmpty(utmSource) ? "Direct" : utmSource;
		// Returns the UTM source.
		return utmSource.toLowerCase();
	}

	/**
	 * Gets the UTM medium for the request.
	 *
	 * @param  source        Source URL.
	 * @param  referrer      Referrer URL.
	 * @param  ownDomains    Own domains.
	 * @param  searchEngines Search engines.
	 * @return               The UTM medium for the request.
	 */
	private static String getUtmMedium(final UriComponents source, final UriComponents referrer, final List<String> ownDomains,
			final List<String> searchEngines) {
		// UTM medium.
		String utmMedium = null;
		// If there is an URL.
		if (source != null) {
			// Tries to get the UTM medium from the URL.
			utmMedium = source.getQueryParams().getFirst("utm_medium");
		}
		// If there is a referrer.
		if (StringUtils.isEmpty(utmMedium) && (referrer != null) && (referrer.getHost() != null)) {
			// If the referrer is a search engine.
			if (searchEngines.stream()
					.anyMatch(searchEngine -> (searchEngine != null) && referrer.getHost().toLowerCase().startsWith(searchEngine.toLowerCase()))) {
				// If the source is from AdWords.
				if ((source != null) && (source.getQueryParams().get("gclid") != null)) {
					// The UTM medium is "CPC".
					utmMedium = "CPC";
				}
				// If the source is not from AdWords.
				else {
					// The UTM medium is "Organic".
					utmMedium = "Organic";
				}
			}
			// If it is our own domain.
			else if (ownDomains.stream().anyMatch(ownDomain -> (ownDomain != null) && referrer.getHost().toLowerCase().startsWith(ownDomain.toLowerCase()))) {
				// The UTM medium is "Link".
				utmMedium = "Link";
			}
			// If the referrer is not a search engine.
			else {
				// The UTM medium is "Referral".
				utmMedium = "Referral";
			}
		}
		// If the UTM medium is "Direct" if empty.
		utmMedium = StringUtils.isEmpty(utmMedium) ? "Link" : utmMedium;
		// Returns the UTM medium.
		return utmMedium.toLowerCase();
	}

	/**
	 * Gets the UTM from the origin URL.
	 *
	 * @param  source        Source.
	 * @param  referrer      Referrer.
	 * @param  ownDomains    Own domains.
	 * @param  searchEngines Search engines.
	 * @return               The UTM from the origin URL.
	 */
	public static Map<String, String> getUtm(final UriComponents source, final UriComponents referrer, final List<String> ownDomains,
			final List<String> searchEngines) {
		// UTM map.
		final Map<String, String> utm = new HashMap<>();
		// Gets the UTM source and medium.
		utm.put("source", UrlHelper.getUtmSource(source, referrer, ownDomains));
		utm.put("medium", UrlHelper.getUtmMedium(source, referrer, ownDomains, searchEngines));
		// If there is a source.
		if ((source != null) && (source.getQueryParams() != null) && (source.getQueryParams().entrySet() != null)) {
			// For each URL parameter.
			for (final Entry<String, List<String>> urlParameter : source.getQueryParams().entrySet()) {
				// If it is an UTM parameter (but for source and medium).
				if ((urlParameter.getKey() != null) && (urlParameter.getValue() != null) && urlParameter.getKey().startsWith("utm_")
						&& !urlParameter.getKey().equals("utm_source") && !urlParameter.getKey().equals("utm_medium")) {
					// Adds the current UTM parameter.
					final String value = (CollectionUtils.isEmpty(urlParameter.getValue()) ? null : urlParameter.getValue().get(0));
					utm.put(urlParameter.getKey().substring("utm_".length()), (StringUtils.isEmpty(value) ? "" : value.toLowerCase()));
				}
			}
		}
		// Returns the UTM.
		return utm;
	}

	/**
	 * Gets the UTM from the origin URL.
	 *
	 * @param  source        Source.
	 * @param  referrer      Referrer.
	 * @param  ownDomains    Own domains.
	 * @param  searchEngines Search engines.
	 * @return               The UTM from the origin URL.
	 */
	public static Map<String, String> getUtm(final String source, final String referrer, final List<String> ownDomains, final List<String> searchEngines) {
		return UrlHelper.getUtm((StringUtils.isEmpty(source) ? null : UriComponentsBuilder.fromUriString(source).build()),
				(StringUtils.isEmpty(referrer) ? null : UriComponentsBuilder.fromUriString(referrer).build()), ownDomains, searchEngines);
	}

}
