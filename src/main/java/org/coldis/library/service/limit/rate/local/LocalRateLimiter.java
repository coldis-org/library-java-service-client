package org.coldis.library.service.limit.rate.local;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.coldis.library.service.limit.rate.RateLimitConfig;
import org.coldis.library.service.limit.rate.RateLimitException;
import org.coldis.library.service.limit.rate.RateLimitStats;
import org.coldis.library.service.limit.rate.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Local (in-JVM) rate limiter. Uses in-memory sliding window with nanosecond
 * precision.
 */
public class LocalRateLimiter implements RateLimiter {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(LocalRateLimiter.class);

	/**
	 * Local executions.
	 */
	public static Map<String, Map<String, RateLimitStats>> EXECUTIONS = new ConcurrentHashMap<>();

	/**
	 * Gets the local executions map.
	 *
	 * @param  name Name.
	 * @return      Local executions map.
	 */
	private Map<String, RateLimitStats> getExecutionsMap(
			final String name) {
		return LocalRateLimiter.EXECUTIONS.computeIfAbsent(name, k -> new ConcurrentHashMap<>());
	}

	/**
	 * Gets the local executions.
	 *
	 * @param  name Name.
	 * @param  key  Key.
	 * @return      Local executions.
	 */
	private RateLimitStats getExecutions(
			final String name,
			final String key) {
		return this.getExecutionsMap(name).computeIfAbsent(key, k -> new RateLimitStats());
	}

	/**
	 * @see RateLimiter#checkLimit(String, String, RateLimitConfig)
	 */
	@Override
	public void checkLimit(
			final String name,
			final String key,
			final RateLimitConfig config) throws RateLimitException {
		final RateLimitStats executions = this.getExecutions(name, key);
		synchronized (executions) {
			executions.setLimit(config.getLimit());
			executions.setPeriod(config.getPeriod());
			executions.setBackoffPeriod(config.getBackoffPeriod());
			executions.checkLimit(name + (org.apache.commons.lang3.StringUtils.isNotBlank(key) ? "-" + key : ""));
		}
	}

	/**
	 * @see RateLimiter#cleanExpiredEntries()
	 */
	@Override
	@Scheduled(cron = "0 */3 * * * *")
	public void cleanExpiredEntries() {
		for (final Map<String, RateLimitStats> executionsMap : LocalRateLimiter.EXECUTIONS.values()) {
			final List<String> emptyExecutionsList = executionsMap.entrySet().stream()
					.filter(entry -> {
						synchronized (entry.getValue()) {
							return CollectionUtils.isEmpty(entry.getValue().getExecutions());
						}
					}).map(entry -> entry.getKey())
					.collect(Collectors.toList());
			for (final String emptyExecutions : emptyExecutionsList) {
				executionsMap.remove(emptyExecutions);
			}
		}
	}

}
