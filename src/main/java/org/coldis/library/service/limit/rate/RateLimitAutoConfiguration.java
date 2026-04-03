package org.coldis.library.service.limit.rate;

import org.aspectj.lang.Aspects;
import org.coldis.library.service.limit.rate.jpa.JpaRateLimiter;
import org.coldis.library.service.limit.rate.local.LocalRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManager;

/**
 * Rate limit configuration.
 */
@Order(100)
@Configuration
public class RateLimitAutoConfiguration {

	/**
	 * Create rate limit interceptor.
	 *
	 * @return the rate limit interceptor
	 */
	@Bean(name = "rateLimitInterceptor")
	public RateLimitInterceptor createRateLimitInterceptor() {
		return Aspects.aspectOf(RateLimitInterceptor.class);
	}

	/**
	 * Creates the local rate limiter.
	 *
	 * @return The local rate limiter.
	 */
	@Bean(name = "localRateLimiter")
	@ConditionalOnMissingBean(name = "localRateLimiter")
	public LocalRateLimiter createLocalRateLimiter() {
		return new LocalRateLimiter();
	}

	/**
	 * JPA rate limiter auto-configuration. Only active when JPA is available.
	 */
	@Configuration
	@ConditionalOnClass(EntityManager.class)
	static class JpaRateLimiterAutoConfiguration {

		/**
		 * Creates the central rate limiter backed by JPA.
		 *
		 * @param  transactionManager Transaction manager.
		 * @return                    The central rate limiter.
		 */
		@Bean(name = "jpaRateLimiter")
		@ConditionalOnMissingBean(name = "jpaRateLimiter")
		public JpaRateLimiter createCentralRateLimiter(final PlatformTransactionManager transactionManager) {
			return new JpaRateLimiter(transactionManager);
		}

	}

}
