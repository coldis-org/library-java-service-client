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
	 * Google AMP project domain.
	 */
	public static final String GOOGLE_AMP_DOMAIN = ".cdn.ampproject.org";

	/**
	 * If the domain is from the given list.
	 *
	 * @param  url     URL.
	 * @param  domains Domains list.
	 * @return         If the domain is from the given list.
	 */
	private static boolean isFromDomain(
			final UriComponents url,
			final List<String> domains) {
		return ((url != null)
				&& domains.stream().anyMatch(searchEngine -> (searchEngine != null) && url.getHost().toLowerCase().startsWith(searchEngine.toLowerCase())));
	}

	/**
	 * If the domain is from Google AMP.
	 *
	 * @param  referrer   Referrer.
	 * @param  ownDomains Own domains.
	 * @return            If the domain is from Google AMP.
	 */
	private static boolean isGoogleAmp(
			final UriComponents referrer,
			final UriComponents source,
			final List<String> ownDomains) {
		final boolean isFromAmpDomain = ((referrer != null) && referrer.getHost().toLowerCase().endsWith(UrlHelper.GOOGLE_AMP_DOMAIN)
				&& ownDomains.stream().anyMatch(
						ownDomain -> (ownDomain != null) && referrer.getHost().substring(0, referrer.getHost().length() - UrlHelper.GOOGLE_AMP_DOMAIN.length())
								.replace("-", ".").toLowerCase().startsWith(ownDomain.toLowerCase())));
		final boolean hasAmpParameter = (((UrlHelper.isFromDomain(referrer, ownDomains) && referrer.getQueryParams().containsKey("amp"))
				|| (UrlHelper.isFromDomain(source, ownDomains) && source.getQueryParams().containsKey("amp"))));
		return (isFromAmpDomain || hasAmpParameter);
	}

	/**
	 * Gets the UTM source for the request.
	 *
	 * @param  source     Source URL.
	 * @param  referrer   Referrer URL.
	 * @param  ownDomains Own domains.
	 * @return            The UTM source for the request.
	 */
	private static String getUtmSource(
			final UriComponents source,
			final UriComponents referrer,
			final List<String> ownDomains) {
		// UTM source.
		String utmSource = null;
		// Tries to get the source from source UTM tag.
		if (source != null) {
			utmSource = source.getQueryParams().getFirst("utm_source");
		}

		// If there is a referrer.
		if ((referrer != null) && (referrer.getHost() != null)) {

			// If the source is still not available, tries to get the source from referrer
			// UTM tag.
			if (StringUtils.isBlank(utmSource) && UrlHelper.isFromDomain(referrer, ownDomains)) {
				utmSource = referrer.getQueryParams().getFirst("utm_source");
			}

			// If the source is still not available, checks if it is an AMP URL
			// (Google/Organic source).
			if (StringUtils.isBlank(utmSource) && UrlHelper.isGoogleAmp(referrer, source, ownDomains)) {
				utmSource = "www.google.com";
			}

			// If the source is still not available, and it is not an own domain, uses the
			// domain from the referrer.
			if (StringUtils.isBlank(utmSource) && !UrlHelper.isFromDomain(referrer, ownDomains)) {
				utmSource = referrer.getHost();
			}

		}

		// If the UTM source is "Direct" if empty.
		if (StringUtils.isBlank(utmSource)) {
			utmSource = "Direct";
		}

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
	private static String getUtmMedium(
			final UriComponents source,
			final UriComponents referrer,
			final List<String> ownDomains,
			final List<String> searchEngines) {
		// UTM medium.
		String utmMedium = null;
		// If there is an URL.
		if (source != null) {
			// Tries to get the UTM medium from the URL.
			utmMedium = source.getQueryParams().getFirst("utm_medium");
		}
		// If there is a referrer.
		if (StringUtils.isBlank(utmMedium) && (referrer != null) && (referrer.getHost() != null)) {
			// If the domain is for Google AMP.
			if (UrlHelper.isGoogleAmp(referrer, source, ownDomains)) {
				// The UTM medium is "Organic".
				utmMedium = "Organic";
			}
			// If the referrer is a search engine.
			else if (UrlHelper.isFromDomain(referrer, searchEngines)) {
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
			else if (UrlHelper.isFromDomain(referrer, ownDomains)) {
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
		utmMedium = StringUtils.isBlank(utmMedium) ? "Link" : utmMedium;
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
	public static Map<String, String> getUtm(
			final UriComponents source,
			final UriComponents referrer,
			final List<String> ownDomains,
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
					utm.put(urlParameter.getKey().substring("utm_".length()), (StringUtils.isBlank(value) ? "" : value.toLowerCase()));
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
	public static Map<String, String> getUtm(
			final String source,
			final String referrer,
			final List<String> ownDomains,
			final List<String> searchEngines) {
		// Gets the actual source (filling the protocol if not filled).
		UriComponents actualSource = (StringUtils.isNotBlank(source) ? UriComponentsBuilder.fromUriString(source).build() : null);
		actualSource = ((actualSource != null) && (actualSource.getHost() == null) ? UriComponentsBuilder.fromUriString("http://" + source).build()
				: actualSource);
		// Gets the actual referrer (filling the protocol if not filled).
		UriComponents actualReferrer = (StringUtils.isNotBlank(referrer) ? UriComponentsBuilder.fromUriString(referrer).build() : null);
		actualReferrer = ((actualReferrer != null) && (actualReferrer.getHost() == null) ? UriComponentsBuilder.fromUriString("http://" + referrer).build()
				: actualReferrer);
		// Gets the UTM variables.
		return UrlHelper.getUtm(actualSource, actualReferrer, ownDomains, searchEngines);
	}

}
