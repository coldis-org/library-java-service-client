package org.coldis.library.service.limit.rate.local;

import java.time.Duration;

import org.coldis.library.service.limit.rate.RateLimitConfig;
import org.coldis.library.service.limit.rate.RateLimitException;
import org.coldis.library.service.limit.rate.RateLimitStats;
import org.coldis.library.service.limit.rate.RateLimiter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
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
	public static Map<String, Map<String, RateLimitStats>> EXECUTIONS = new HashMap<>();

	/**
	 * Gets the local executions map.
	 *
	 * @param  name Name.
	 * @return      Local executions map.
	 */
	private Map<String, RateLimitStats> getExecutionsMap(
			final String name) {
		Map<String, RateLimitStats> executionsMap = null;
		synchronized (LocalRateLimiter.EXECUTIONS) {
			executionsMap = LocalRateLimiter.EXECUTIONS.get(name);
			if (executionsMap == null) {
				executionsMap = new TreeMap<>();
				LocalRateLimiter.EXECUTIONS.put(name, executionsMap);
			}
		}
		return executionsMap;
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
		RateLimitStats executions = null;
		final Map<String, RateLimitStats> executionsMap = this.getExecutionsMap(name);
		synchronized (executionsMap) {
			executions = executionsMap.get(key);
			if (executions == null) {
				executions = new RateLimitStats();
				executionsMap.put(key, executions);
			}
		}
		return executions;
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
					.filter(executions -> CollectionUtils.isEmpty(executions.getValue().getExecutions())).map(executions -> executions.getKey())
					.collect(Collectors.toList());
			for (final String emptyExecutions : emptyExecutionsList) {
				synchronized (executionsMap) {
					executionsMap.remove(emptyExecutions);
				}
			}
		}
	}

}
