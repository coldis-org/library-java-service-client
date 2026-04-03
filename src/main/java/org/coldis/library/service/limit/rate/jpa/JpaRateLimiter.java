package org.coldis.library.service.limit.rate.jpa;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.coldis.library.service.limit.rate.RateLimitConfig;
import org.coldis.library.service.limit.rate.RateLimitException;
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
	public final Map<String, BufferedState> buffers = new ConcurrentHashMap<>();

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
		long lastFlushTimeMillis = System.currentTimeMillis();

		/**
		 * Last used configuration.
		 */
		RateLimitConfig lastConfig;

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
					|| (System.currentTimeMillis() - this.lastFlushTimeMillis) >= config.getBufferDuration().toMillis();
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
		final BufferedState state = this.buffers.computeIfAbsent(bufKey, k -> new BufferedState(name, key));

		synchronized (state) {
			// Stores the latest config for shutdown flush.
			state.lastConfig = config;

			// Updates the constraints.
			state.localEntry.setLimit(config.getLimit());
			state.localEntry.setPeriod(config.getPeriod());
			state.localEntry.setBackoffPeriod(config.getBackoffPeriod());
			state.localEntry.setBucketDuration(config.getBucket());

			// Flushes to database if buffer threshold reached.
			if (state.needsFlush(config)) {
				this.flushToDatabase(name, key, state, config);
			}

			// Checks local limit (adds current execution to bucket).
			final String limitName = name + (StringUtils.isNotBlank(key) ? "-" + key : "");
			final long bucketKey = state.localEntry.toBucketKey(state.localEntry.currentTime());
			state.localEntry.checkLimit(limitName);
			state.pending.merge(bucketKey, 1L, Long::sum);
			state.pendingCount++;
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

			// Merges pending bucket counts into the database entry.
			final TreeMap<Long, Long> updated = new TreeMap<>(dbEntry.getBuckets());
			for (final Map.Entry<Long, Long> pendingEntry : state.pending.entrySet()) {
				updated.merge(pendingEntry.getKey(), pendingEntry.getValue(), Long::sum);
			}
			dbEntry.setBuckets(updated);

			// Replaces local state with the database state.
			state.localEntry.setBuckets(new TreeMap<>(updated));
			state.localEntry.setLimitedUntil(dbEntry.getLimitedUntil());

			// Checks if limit is exceeded on the merged state.
			if (dbEntry.getLimitedUntil() != null) {
				return new RateLimitException(name + (StringUtils.isNotBlank(key) ? "-" + key : ""), config.getLimit());
			}

			return null;
		});

		// Resets buffer state.
		state.pending.clear();
		state.pendingCount = 0;
		state.lastFlushTimeMillis = System.currentTimeMillis();

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
	 * @see RateLimiter#cleanExpiredEntries()
	 */
	@Override
	@Scheduled(cron = "0 */3 * * * *")
	public void cleanExpiredEntries() {
		try {
			this.transactionTemplate.executeWithoutResult(status -> {
				final int deleted = this.entityManager
						.createQuery("DELETE FROM RateLimitEntry e WHERE e.limitedUntil IS NOT NULL AND e.limitedUntil < :now")
						.setParameter("now", System.currentTimeMillis()).executeUpdate();
				if (deleted > 0) {
					JpaRateLimiter.LOGGER.debug("Cleaned up {} expired rate limit entries", deleted);
				}
			});
		}
		catch (final Exception exception) {
			JpaRateLimiter.LOGGER.warn("Error cleaning up rate limit entries: {}", exception.getMessage());
		}
	}

}
