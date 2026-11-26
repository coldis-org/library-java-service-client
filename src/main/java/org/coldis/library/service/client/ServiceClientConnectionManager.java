package org.coldis.library.service.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.ConnectionEndpoint;
import org.apache.hc.client5.http.io.LeaseRequest;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pooled connection manager that bounds, per route (endpoint), the requests
 * waiting for a pooled connection, relative to the connections in use on it.
 * <p>
 * The pool gives each route at most its maximum connections; a request beyond
 * them waits (up to its pool wait) for one to be released, and the pool itself
 * does not bound how many wait. Here a request that would wait is refused at
 * once, with nothing sent, when the requests already waiting on its route reach
 * {@code max(1, ceil(ratio x connections leased on the route))}: with ratio 1
 * and 32 connections in use, 32 more may wait and the next is refused. It fails
 * with a {@link ServiceClientPendingLeaseLimitException} (see there for how the
 * failover executor treats it). A ratio of 0 bounds nothing (the pool alone).
 * <p>
 * Requests waiting on a route are counted against the route's maximum only (as
 * set on this manager); a request waiting because the pool's total is reached
 * is not counted as waiting until its route is full too, so it is bounded by
 * the route's maximum plus its waiting cap.
 * <p>
 * Cost per request: one map lookup, a few atomic increments and decrements, and
 * one map put and remove for the leased connection; no lock (the pool's own
 * route statistics scan every waiting request under the pool's lock, so they
 * are not used). The ratio is a {@code double} (not {@code BigDecimal}): it is
 * a sizing factor on the hot path, not a business value.
 */
public class ServiceClientConnectionManager extends PoolingHttpClientConnectionManager {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceClientConnectionManager.class);

	/**
	 * Requests waiting allowed on any route, whatever its connections in use.
	 */
	public static final int MIN_PENDING = 1;

	/**
	 * Leases of a route.
	 */
	private static final class RouteLeases {

		/**
		 * Lease requests not yet given a connection (nor failed or cancelled).
		 */
		private final AtomicInteger requested = new AtomicInteger();

		/**
		 * Connections given and not yet released.
		 */
		private final AtomicInteger leased = new AtomicInteger();

	}

	/**
	 * Lease request that keeps the route's counts.
	 */
	private final class CountedLeaseRequest implements LeaseRequest {

		/**
		 * Lease request of the pool.
		 */
		private final LeaseRequest poolLeaseRequest;

		/**
		 * Leases of the route.
		 */
		private final RouteLeases routeLeases;

		/**
		 * If the request is no longer counted as requested.
		 */
		private final AtomicBoolean isSettled = new AtomicBoolean();

		/**
		 * If the connection given is counted as leased.
		 */
		private final AtomicBoolean isLeaseCounted = new AtomicBoolean();

		/**
		 * Constructor.
		 *
		 * @param poolLeaseRequest Lease request of the pool.
		 * @param routeLeases      Leases of the route.
		 */
		private CountedLeaseRequest(final LeaseRequest poolLeaseRequest, final RouteLeases routeLeases) {
			this.poolLeaseRequest = poolLeaseRequest;
			this.routeLeases = routeLeases;
		}

		/**
		 * Stops counting the request as requested (once).
		 */
		private void settle() {
			if (this.isSettled.compareAndSet(false, true)) {
				this.routeLeases.requested.decrementAndGet();
			}
		}

		/**
		 * @see LeaseRequest#get(Timeout)
		 */
		@Override
		public ConnectionEndpoint get(
				final Timeout timeout) throws InterruptedException, ExecutionException, TimeoutException {
			final ConnectionEndpoint endpoint;
			try {
				endpoint = this.poolLeaseRequest.get(timeout);
				if ((endpoint != null) && this.isLeaseCounted.compareAndSet(false, true)) {
					this.routeLeases.leased.incrementAndGet();
					ServiceClientConnectionManager.this.leasedEndpoints.put(endpoint, this.routeLeases);
				}
			}
			finally {
				this.settle();
			}
			return endpoint;
		}

		/**
		 * @see LeaseRequest#cancel()
		 */
		@Override
		public boolean cancel() {
			final boolean isCancelled = this.poolLeaseRequest.cancel();
			this.settle();
			return isCancelled;
		}

	}

	/**
	 * Lease request refused at once.
	 */
	private static final class RefusedLeaseRequest implements LeaseRequest {

		/**
		 * Refusal.
		 */
		private final ServiceClientPendingLeaseLimitException refusal;

		/**
		 * Constructor.
		 *
		 * @param refusal Refusal.
		 */
		private RefusedLeaseRequest(final ServiceClientPendingLeaseLimitException refusal) {
			this.refusal = refusal;
		}

		/**
		 * @see LeaseRequest#get(Timeout)
		 */
		@Override
		public ConnectionEndpoint get(
				final Timeout timeout) throws ExecutionException {
			throw new ExecutionException(this.refusal.getMessage(), this.refusal);
		}

		/**
		 * @see LeaseRequest#cancel()
		 */
		@Override
		public boolean cancel() {
			return false;
		}

	}

	/**
	 * Requests waiting allowed per connection leased on the route (0 for no
	 * bound).
	 */
	private final double maxPendingRatio;

	/**
	 * Leases per route.
	 */
	private final Map<HttpRoute, RouteLeases> routesLeases = new ConcurrentHashMap<>();

	/**
	 * Route of each connection leased.
	 */
	private final Map<ConnectionEndpoint, RouteLeases> leasedEndpoints = new ConcurrentHashMap<>();

	/**
	 * Maximum connections of the routes with a maximum of their own.
	 */
	private final Map<HttpRoute, Integer> routesMaxConnections = new ConcurrentHashMap<>();

	/**
	 * Maximum connections of the other routes.
	 */
	private volatile int defaultRouteMaxConnections;

	/**
	 * Requests refused.
	 */
	private final LongAdder refusedLeases = new LongAdder();

	/**
	 * Constructor.
	 *
	 * @param maxPendingRatio Requests waiting allowed per connection leased on
	 *                            the route (0 for no bound; at least
	 *                            {@value #MIN_PENDING} may always wait).
	 */
	public ServiceClientConnectionManager(final double maxPendingRatio) {
		super();
		if (!Double.isFinite(maxPendingRatio) || (maxPendingRatio < 0)) {
			throw new IllegalArgumentException("The ratio of requests waiting for a pooled connection must be zero or more: " + maxPendingRatio);
		}
		this.maxPendingRatio = maxPendingRatio;
		this.defaultRouteMaxConnections = super.getDefaultMaxPerRoute();
	}

	/**
	 * Gets the requests waiting allowed per connection leased on the route.
	 *
	 * @return The ratio (0 for no bound).
	 */
	public double getMaxPendingRatio() {
		return this.maxPendingRatio;
	}

	/**
	 * Gets the requests refused so far.
	 *
	 * @return The requests refused.
	 */
	public long getRefusedLeases() {
		return this.refusedLeases.sum();
	}

	/**
	 * Gets the connections leased on a route (as counted here).
	 *
	 * @param  route Route.
	 * @return       The connections leased.
	 */
	public int getLeased(
			final HttpRoute route) {
		final RouteLeases routeLeases = this.routesLeases.get(route);
		return (routeLeases == null ? 0 : routeLeases.leased.get());
	}

	/**
	 * Gets the requests waiting allowed on a route.
	 *
	 * @param  leased Connections leased on the route.
	 * @return        The requests waiting allowed.
	 */
	private int getMaxPending(
			final int leased) {
		return Math.max(ServiceClientConnectionManager.MIN_PENDING, (int) Math.ceil(this.maxPendingRatio * leased));
	}

	/**
	 * @see PoolingHttpClientConnectionManager#setDefaultMaxPerRoute(int)
	 */
	@Override
	public void setDefaultMaxPerRoute(
			final int max) {
		super.setDefaultMaxPerRoute(max);
		this.defaultRouteMaxConnections = max;
	}

	/**
	 * @see PoolingHttpClientConnectionManager#setMaxPerRoute(HttpRoute, int)
	 */
	@Override
	public void setMaxPerRoute(
			final HttpRoute route,
			final int max) {
		super.setMaxPerRoute(route, max);
		this.routesMaxConnections.put(route, max);
	}

	/**
	 * Refuses a lease request.
	 *
	 * @param  route   Route.
	 * @param  pending Requests waiting with the refused one.
	 * @param  leased  Connections leased on the route.
	 * @return         The refused lease request.
	 */
	private LeaseRequest refuse(
			final HttpRoute route,
			final int pending,
			final int leased) {
		this.refusedLeases.increment();
		final String message = "Requests waiting for a pooled connection to " + route.getTargetHost() + " reached their limit (" + (pending - 1) + " waiting, "
				+ leased + " in use, limit " + this.getMaxPending(leased) + "): request refused.";
		ServiceClientConnectionManager.LOGGER.debug(message);
		return new RefusedLeaseRequest(new ServiceClientPendingLeaseLimitException(message));
	}

	/**
	 * @see PoolingHttpClientConnectionManager#lease(String, HttpRoute, Timeout,
	 *      Object)
	 */
	@Override
	public LeaseRequest lease(
			final String id,
			final HttpRoute route,
			final Timeout requestTimeout,
			final Object state) {
		final RouteLeases routeLeases = this.routesLeases.computeIfAbsent(route, newRoute -> new RouteLeases());
		final int requested = routeLeases.requested.incrementAndGet();
		final int leased = routeLeases.leased.get();
		final int pending = (leased + requested) - this.routesMaxConnections.getOrDefault(route, this.defaultRouteMaxConnections);
		final LeaseRequest leaseRequest;
		if ((this.maxPendingRatio > 0) && (pending > this.getMaxPending(leased))) {
			routeLeases.requested.decrementAndGet();
			leaseRequest = this.refuse(route, pending, leased);
		}
		else {
			try {
				leaseRequest = new CountedLeaseRequest(super.lease(id, route, requestTimeout, state), routeLeases);
			}
			catch (final RuntimeException exception) {
				routeLeases.requested.decrementAndGet();
				throw exception;
			}
		}
		return leaseRequest;
	}

	/**
	 * @see PoolingHttpClientConnectionManager#release(ConnectionEndpoint, Object,
	 *      TimeValue)
	 */
	@Override
	public void release(
			final ConnectionEndpoint endpoint,
			final Object state,
			final TimeValue keepAlive) {
		try {
			super.release(endpoint, state, keepAlive);
		}
		finally {
			final RouteLeases routeLeases = this.leasedEndpoints.remove(endpoint);
			if (routeLeases != null) {
				routeLeases.leased.decrementAndGet();
			}
		}
	}

}
