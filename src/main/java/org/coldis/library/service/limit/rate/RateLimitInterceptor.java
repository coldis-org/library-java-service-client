package org.coldis.library.service.limit.rate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.coldis.library.model.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.util.StringValueResolver;

/**
 * Rate limit interceptor.
 */
@Aspect
public class RateLimitInterceptor implements ApplicationContextAware, EmbeddedValueResolverAware {

	/**
	 * Random.
	 */
	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitInterceptor.class);

	/**
	 * Value resolver.
	 */
	public static StringValueResolver VALUE_RESOLVER;

	/**
	 * Application context.
	 */
	private ApplicationContext applicationContext;

	/**
	 * Cached rate limiter beans. Uses Optional to cache misses.
	 */
	private final Map<String, Optional<RateLimiter>> rateLimiters = new ConcurrentHashMap<>();

	/**
	 * @see org.springframework.context.EmbeddedValueResolverAware#setEmbeddedValueResolver(org.springframework.util.StringValueResolver)
	 */
	@Override
	public void setEmbeddedValueResolver(
			final StringValueResolver resolver) {
		RateLimitInterceptor.VALUE_RESOLVER = resolver;
	}

	/**
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(
			final ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	/**
	 * Gets a rate limiter bean by name, caching the result.
	 *
	 * @param  beanName Bean name.
	 * @return          The rate limiter, or null if not found.
	 */
	private RateLimiter getRateLimiter(
			final String beanName) {
		return this.rateLimiters.computeIfAbsent(beanName, name -> {
			try {
				return Optional.of(this.applicationContext.getBean(name, RateLimiter.class));
			}
			catch (final Exception exception) {
				RateLimitInterceptor.LOGGER.warn("Rate limiter bean '{}' not found.", name);
				return Optional.empty();
			}
		}).orElse(null);
	}

	/**
	 * Resolves a long value.
	 *
	 * @param  String value
	 * @return        Long value.
	 */
	private long resolveLongValue(
			final String value) {
		return Long.parseLong(Objects.requireNonNull(RateLimitInterceptor.VALUE_RESOLVER.resolveStringValue(value)));
	}

	/**
	 * Method point-cut.
	 */
	@Pointcut("execution(* *(..))")
	public void methodPointcut() {
	}

	/**
	 * Rate limit method point-cut.
	 */
	@Pointcut("@annotation(org.coldis.library.service.limit.rate.RateLimit)")
	public void rateLimitMethodPointcut() {
	}

	/**
	 * Rate limit method point-cut.
	 */
	@Pointcut("@annotation(org.coldis.library.service.limit.rate.RateLimits)")
	public void rateLimitsMethodPointcut() {
	}

	/**
	 * Checks the limit using the appropriate rate limiter.
	 *
	 * @param  name              Limit name.
	 * @param  key               Limit key.
	 * @param  limit             Limit annotation.
	 * @throws Exception         If the limit was exceeded or an error occurred.
	 */
	private void checkLimit(
			final String name,
			final String key,
			final RateLimit limit) throws Exception {
		// Resolves the limit parameters.
		final RateLimitConfig config = new RateLimitConfig(this.resolveLongValue(limit.limit()),
				Duration.ofSeconds(this.resolveLongValue(limit.period())), Duration.ofSeconds(this.resolveLongValue(limit.backoffPeriod())),
				(int) this.resolveLongValue(limit.bufferSize()), Duration.ofSeconds(this.resolveLongValue(limit.bufferDuration())),
				Duration.ofSeconds(this.resolveLongValue(limit.bucket())));

		// Resolves the rate limiter bean by name.
		final String limiterBeanName = RateLimitInterceptor.VALUE_RESOLVER.resolveStringValue(limit.limiter());
		final RateLimiter rateLimiter = this.getRateLimiter(limiterBeanName);

		// Checks the limit.
		if (rateLimiter != null) {
			try {
				rateLimiter.checkLimit(name, key, config);
			}
			// Uses a delegate exception if needed.
			catch (final RateLimitException exception) {
				Exception actualException = exception;
				if (!Objects.equals(limit.errorType(), RateLimitException.class) || ArrayUtils.isNotEmpty(limit.randomErrorMessages())) {

					// Selects the message to be used.
					String message = exception.getLocalizedMessage();
					if (ArrayUtils.isNotEmpty(limit.randomErrorMessages())) {
						message = limit.randomErrorMessages()[RateLimitInterceptor.RANDOM.nextInt(limit.randomErrorMessages().length)];
					}

					// Gets the exception constructor.
					Constructor<? extends Exception> constructor = null;
					boolean useSimpleMessages = false;
					boolean useSimpleMessage = false;
					boolean useString = false;
					try {
						constructor = limit.errorType().getConstructor(Collection.class);
						useSimpleMessages = true;
					}
					catch (final Exception constructorException1) {
						try {
							constructor = limit.errorType().getConstructor(SimpleMessage.class);
							useSimpleMessage = true;
						}
						catch (final Exception constructorException2) {
							try {
								constructor = limit.errorType().getConstructor(String.class);
								useString = true;
							}
							catch (final Exception constructorException3) {
								try {
									constructor = limit.errorType().getConstructor();
								}
								catch (final Exception constructorException4) {
								}
							}
						}
					}

					// Initializes the exception.
					if (constructor != null) {
						if (useSimpleMessages) {
							actualException = constructor.newInstance(List.of(new SimpleMessage(message)));
						}
						else if (useSimpleMessage) {
							actualException = constructor.newInstance(new SimpleMessage(message));
						}
						else if (useString) {
							actualException = constructor.newInstance(message);
						}
						else {
							actualException = constructor.newInstance();
						}
					}

				}
				RateLimitInterceptor.LOGGER.debug("Rate limited: " + actualException.getLocalizedMessage());
				throw actualException;
			}
		}
	}

	/**
	 * Checks the rate limit for a method.
	 *
	 * @param  proceedingJoinPoint Join point.
	 * @throws Throwable           If the method cannot be executed.
	 */
	@Around(
			value = "methodPointcut() && (rateLimitMethodPointcut() || rateLimitsMethodPointcut()) && target(targetObject)",
			argNames = "targetObject"

	)
	public Object checkRateLimit(
			final ProceedingJoinPoint proceedingJoinPoint,
			final Object targetObject) throws Throwable {
		// If it is really a method.
		final MethodSignature methodSignature = (MethodSignature) proceedingJoinPoint.getSignature();
		if ((methodSignature != null) && (methodSignature instanceof MethodSignature)) {
			final Method method = methodSignature.getMethod();

			// Gets the limits.
			final List<RateLimit> limits = new ArrayList<>();
			final RateLimits rateLimitsAnnotation = method.getAnnotation(RateLimits.class);
			if (rateLimitsAnnotation != null) {
				limits.addAll(Arrays.asList(rateLimitsAnnotation.limits()));
			}
			final RateLimit rateLimitAnnotation = method.getAnnotation(RateLimit.class);
			if (rateLimitAnnotation != null) {
				limits.add(rateLimitAnnotation);
			}

			// Gets the limit key.
			final List<String> keys = new ArrayList<>();
			final List<Parameter> parameters = (method.getParameters() == null ? null : Arrays.asList(method.getParameters()));
			for (Integer argumentIdx = 0; argumentIdx < CollectionUtils.size(parameters); argumentIdx++) {
				final Parameter parameter = parameters.get(argumentIdx);
				if (parameter.getAnnotation(RateLimitKey.class) != null) {
					keys.add(Objects.toString(proceedingJoinPoint.getArgs()[argumentIdx]));
				}
			}
			final String key = StringUtils.join(keys, '-');

			// Checks for limits (if there are limits).
			if (CollectionUtils.isNotEmpty(limits)) {
				for (final RateLimit limit : limits) {
					// Gets the limit name.
					final String name = (StringUtils.isBlank(limit.name())
							? (targetObject.getClass().getSimpleName().toLowerCase() + "-" + method.getName().toLowerCase() + "-" + limit.period())
							: limit.name());
					// Checks the limit.
					this.checkLimit(name, key, limit);
				}
			}
		}

		// Executes the method normally.
		return proceedingJoinPoint.proceed();

	}

}
