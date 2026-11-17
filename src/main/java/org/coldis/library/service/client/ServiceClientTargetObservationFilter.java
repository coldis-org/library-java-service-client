package org.coldis.library.service.client;

import org.springframework.http.client.observation.ClientRequestObservationContext;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

/**
 * Adds the {@link ServiceClientTarget} of the current thread to the HTTP client
 * request observations (Spring Boot records them as {@code http.client.requests}
 * for templates built with its template builder), as the low-cardinality key
 * {@value #TARGET_KEY}: the generated client's name, or
 * {@value ServiceClientTarget#NONE} for any other request (so every request has
 * the key). Spring Boot registers it with the observation registry when the
 * actuator is present; it is applied when the observation stops, on the calling
 * thread.
 */
public class ServiceClientTargetObservationFilter implements ObservationFilter {

	/**
	 * Key of the target.
	 */
	public static final String TARGET_KEY = "target";

	/**
	 * @see ObservationFilter#map(Observation.Context)
	 */
	@Override
	public Observation.Context map(
			final Observation.Context context) {
		if (context instanceof ClientRequestObservationContext) {
			context.addLowCardinalityKeyValue(KeyValue.of(ServiceClientTargetObservationFilter.TARGET_KEY, ServiceClientTarget.getCurrent()));
		}
		return context;
	}

}
