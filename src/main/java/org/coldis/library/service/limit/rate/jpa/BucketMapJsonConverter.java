package org.coldis.library.service.limit.rate.jpa;

import java.util.TreeMap;

import org.coldis.library.persistence.converter.AbstractJsonConverter;
import org.coldis.library.serialization.ObjectMapperHelper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.Converter;

/**
 * TreeMap&lt;Long, Long&gt; from/to JSON converter for bucket-based rate
 * limiting.
 */
@Converter
public class BucketMapJsonConverter extends AbstractJsonConverter<TreeMap<Long, Long>> {

	/**
	 * @see org.coldis.library.persistence.converter.AbstractJsonConverter#convertToEntityAttribute(com.fasterxml.jackson.databind.ObjectMapper,
	 *      java.lang.String)
	 */
	@Override
	protected TreeMap<Long, Long> convertToEntityAttribute(
			final ObjectMapper jsonMapper,
			final String jsonObject) {
		return ObjectMapperHelper.deserialize(jsonMapper, jsonObject, new TypeReference<TreeMap<Long, Long>>() {
		}, false);
	}

}
