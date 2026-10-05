package org.coldis.library.service.limit.rate.jpa;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.limit.rate.RateLimitConfig;
import org.coldis.library.service.limit.rate.RateLimitException;
import org.coldis.library.service.limit.rate.RateLimitStats;
import org.coldis.library.service.limit.rate.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 * JPA-based centralized rate limiter. Uses a local buffer to reduce database
 * round-trips, flushing to the database every {@code bufferSize} executions or
 * every {@code bufferDuration} (configured per rate limit via annotation).
 * Entries whose window has elapsed are removed from the database and from the
 * local buffer by {@link #cleanExpiredEntries()}.
 */
public class JpaRateLimiter implements RateLimiter {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(JpaRateLimiter.class);

	/**
	 * Entity manager.
	 */
	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * Transaction template with REQUIRES_NEW propagation.
	 */
	private final TransactionTemplate transactionTemplate;

	/**
	 * Local buffer state per rate limit (name-key).
	 */
	private final Map<String, BufferedState> buffers = new ConcurrentHashMap<>();

	/**
	 * Last used configuration per rate limit name.
	 */
	private final Map<String, RateLimitConfig> configsByName = new ConcurrentHashMap<>();

	/**
	 * Deletes the entries of a rate limit that are not blocked and whose newest
	 * bucket is outside the window.
	 */
	private static final String DELETE_IDLE_ENTRIES = "DELETE FROM rate_limit WHERE name = :name"
			+ " AND (limited_until IS NULL OR limited_until < :now)"
			+ " AND COALESCE((SELECT MAX(CAST(bucket_key AS BIGINT)) FROM jsonb_object_keys(buckets) AS bucket_key),"
			+ " :lastExpiredBucket) <= :lastExpiredBucket";

	/**
	 * Clears all local buffer state.
	 */
	public void clearBuffers() {
		this.buffers.clear();
	}

	/**
	 * Gets the number of entries held in the local buffer.
	 *
	 * @return The number of entries held in the local buffer.
	 */
	public int getBufferedEntryCount() {
		return this.buffers.size();
	}

	/**
	 * Buffered state for a single rate limit entry.
	 */
	static class BufferedState {

		/**
		 * Rate limit name.
		 */
		final String name;

		/**
		 * Rate limit key.
		 */
		final String key;

		/**
		 * Local rate limit entry (transient, not JPA-managed).
		 */
		final RateLimitEntry localEntry = new RateLimitEntry();

		/**
		 * Pending bucket increments since last flush.
		 */
		final TreeMap<Long, Long> pending = new TreeMap<>();

		/**
		 * Pending execution count since last flush.
		 */
		int pendingCount = 0;

		/**
		 * Last flush time (epoch millis).
		 */
		long lastFlushTimeMillis = DateTimeHelper.getClock().millis();

		/**
		 * Last used configuration.
		 */
		RateLimitConfig lastConfig;

		/**
		 * If the state was removed from the local buffer.
		 */
		boolean evicted = false;

		/**
		 * Constructor.
		 *
		 * @param name Rate limit name.
		 * @param key  Rate limit key.
		 */
		BufferedState(final String name, final String key) {
			this.name = name;
			this.key = key;
		}

		/**
		 * Checks if the buffer needs to be flushed.
		 *
		 * @param  config Rate limit configuration.
		 * @return        True if the buffer needs to be flushed.
		 */
		boolean needsFlush(
				final RateLimitConfig config) {
			return this.pendingCount >= config.getBufferSize()
					|| (DateTimeHelper.getClock().millis() - this.lastFlushTimeMillis) >= config.getBufferDuration().toMillis();
		}

		/**
		 * Checks if the state holds nothing: no pending execution, no bucket inside
		 * the window and no active block.
		 *
		 * @return True if the state holds nothing.
		 */
		boolean isIdle() {
			return this.pending.isEmpty() && this.localEntry.getBuckets().isEmpty() && (this.localEntry.getLimitedUntil() == null);
		}

	}

	/**
	 * Constructor.
	 *
	 * @param transactionManager Transaction manager.
	 */
	public JpaRateLimiter(final PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * @see RateLimiter#checkLimit(String, String, RateLimitConfig)
	 */
	@Override
	public void checkLimit(
			final String name,
			final String key,
			final RateLimitConfig config) throws RateLimitException {

		final String bufKey = name + "-" + key;
		this.configsByName.put(name, config);

		// Retries while the state taken from the buffer has been evicted meanwhile.
		while (true) {
			final BufferedState state = this.buffers.computeIfAbsent(bufKey, k -> new BufferedState(name, key));
			synchronized (state) {
				if (state.evicted) {
					continue;
				}

				// Stores the latest config for shutdown flush.
				state.lastConfig = config;

				// Updates the constraints.
				state.localEntry.setLimit(config.getLimit());
				state.localEntry.setPeriod(config.getPeriod());
				state.localEntry.setBackoffPeriod(config.getBackoffPeriod());
				state.localEntry.setBucketDuration(config.getBucket());
				state.localEntry.setResetOnBlock(config.getResetOnBlock());

				// Flushes to database if buffer threshold reached.
				if (state.needsFlush(config)) {
					this.flushToDatabase(name, key, state, config);
				}

				// Checks local limit (adds current execution to bucket).
				final String limitName = name + (StringUtils.isNotBlank(key) ? "-" + key : "");
				final long bucketKey = state.localEntry.toBucketKey(DateTimeHelper.getClock().millis());
				state.localEntry.checkLimit(limitName);
				state.pending.merge(bucketKey, 1L, Long::sum);
				state.pendingCount++;
				return;
			}
		}

	}

	/**
	 * Flushes pending executions to the database.
	 *
	 * @param  name               Rate limit name.
	 * @param  key                Rate limit key.
	 * @param  state              Local buffer state.
	 * @param  config             Rate limit configuration.
	 * @throws RateLimitException If the merged state exceeds the limit.
	 */
	private void flushToDatabase(
			final String name,
			final String key,
			final BufferedState state,
			final RateLimitConfig config) throws RateLimitException {

		final RateLimitException exception = this.transactionTemplate.execute(status -> {

			// Finds or creates the entry with pessimistic lock.
			final RateLimitEntryId id = new RateLimitEntryId(name, key);
			RateLimitEntry dbEntry = this.entityManager.find(RateLimitEntry.class, id, LockModeType.PESSIMISTIC_WRITE);

			if (dbEntry == null) {
				dbEntry = new RateLimitEntry();
				dbEntry.setName(name);
				dbEntry.setKey(key);
				try {
					this.entityManager.persist(dbEntry);
					this.entityManager.flush();
				}
				catch (final PersistenceException persistException) {
					this.entityManager.clear();
					dbEntry = this.entityManager.find(RateLimitEntry.class, id, LockModeType.PESSIMISTIC_WRITE);
				}
			}

			// Updates the constraints.
			dbEntry.setLimit(config.getLimit());
			dbEntry.setPeriod(config.getPeriod());
			dbEntry.setBackoffPeriod(config.getBackoffPeriod());
			dbEntry.setBucketDuration(config.getBucket());
			dbEntry.setResetOnBlock(config.getResetOnBlock());

			final TreeMap<Long, Long> dbBuckets = dbEntry.getBuckets();
			final TreeMap<Long, Long> updated = new TreeMap<>(dbBuckets);
			for (final Map.Entry<Long, Long> pendingEntry : state.pending.entrySet()) {
				updated.merge(pendingEntry.getKey(), pendingEntry.getValue(), Long::sum);
			}
			dbEntry.setBuckets(updated);

			JpaRateLimiter.LOGGER.debug("flush name={} key={} dbBuckets={} pending={} merged={} dbLimitedUntil={} dbCount={}",
					name, key, dbBuckets, state.pending, updated, dbEntry.getLimitedUntil(),
					updated.values().stream().mapToLong(Long::longValue).sum());

			state.localEntry.setBuckets(new TreeMap<>(updated));
			state.localEntry.setLimitedUntil(dbEntry.getLimitedUntil());

			if (dbEntry.getLimitedUntil() != null) {
				return new RateLimitException(name + (StringUtils.isNotBlank(key) ? "-" + key : ""), config.getLimit());
			}

			return null;
		});

		// Resets buffer state.
		state.pending.clear();
		state.pendingCount = 0;
		state.lastFlushTimeMillis = DateTimeHelper.getClock().millis();

		if (exception != null) {
			throw exception;
		}

	}

	/**
	 * Flushes all pending buffers to the database on application shutdown.
	 */
	@PreDestroy
	public void flushAllBuffers() {
		for (final BufferedState state : this.buffers.values()) {
			synchronized (state) {
				if (!state.pending.isEmpty() && state.lastConfig != null) {
					try {
						this.flushToDatabase(state.name, state.key, state, state.lastConfig);
					}
					catch (final RateLimitException rateLimitException) {
						// Ignore rate limit exceptions during shutdown.
					}
					catch (final Exception exception) {
						JpaRateLimiter.LOGGER.warn("Error flushing rate limit buffer on shutdown for {}-{}: {}", state.name, state.key,
								exception.getMessage());
					}
				}
			}
		}
	}

	/**
	 * Gets the newest bucket key that is outside the window of a rate limit.
	 *
	 * @param  config Rate limit configuration.
	 * @param  now    Current time (epoch millis).
	 * @return        The newest bucket key that is outside the window.
	 */
	private static long getLastExpiredBucket(
			final RateLimitConfig config,
			final long now) {
		final RateLimitStats stats = new RateLimitStats();
		stats.setPeriod(config.getPeriod());
		stats.setBucketDuration(config.getBucket());
		return stats.toBucketKey(now - config.getPeriod().toMillis());
	}

	/**
	 * Removes from the local buffer the states that hold nothing.
	 */
	private void evictIdleBuffers() {
		for (final Map.Entry<String, BufferedState> entry : this.buffers.entrySet()) {
			final BufferedState state = entry.getValue();
			synchronized (state) {
				if (state.isIdle()) {
					state.evicted = true;
					this.buffers.remove(entry.getKey(), state);
				}
			}
		}
	}

	/**
	 * Removes the entries whose block has expired and, for each rate limit used by
	 * this instance, the entries that are not blocked and whose window has
	 * elapsed. Then removes the idle states from the local buffer.
	 *
	 * @see RateLimiter#cleanExpiredEntries()
	 */
	@Override
	@Scheduled(cron = "0 */3 * * * *")
	public void cleanExpiredEntries() {
		final long now = DateTimeHelper.getClock().millis();
		try {
			this.transactionTemplate.executeWithoutResult(status -> {
				// Removes the entries whose block has expired.
				int deleted = this.entityManager
						.createQuery("DELETE FROM RateLimitEntry e WHERE e.limitedUntil IS NOT NULL AND e.limitedUntil < :now")
						.setParameter("now", now).executeUpdate();
				// Removes the entries whose window has elapsed.
				for (final Map.Entry<String, RateLimitConfig> config : this.configsByName.entrySet()) {
					deleted += this.entityManager.createNativeQuery(JpaRateLimiter.DELETE_IDLE_ENTRIES)
							.setParameter("name", config.getKey()).setParameter("now", now)
							.setParameter("lastExpiredBucket", JpaRateLimiter.getLastExpiredBucket(config.getValue(), now)).executeUpdate();
				}
				if (deleted > 0) {
					JpaRateLimiter.LOGGER.debug("Cleaned up {} expired rate limit entries", deleted);
				}
			});
		}
		catch (final Exception exception) {
			JpaRateLimiter.LOGGER.warn("Error cleaning up rate limit entries: {}", exception.getMessage());
		}
		// Removes the idle states from the local buffer.
		this.evictIdleBuffers();
	}

}
