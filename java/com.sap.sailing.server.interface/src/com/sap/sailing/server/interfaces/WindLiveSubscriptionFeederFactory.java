package com.sap.sailing.server.interfaces;

import java.util.Collection;

import com.sap.sailing.domain.common.WindSource;

/**
 * Produces a {@link WindLiveSubscriptionFeeder} for a set of wind sources that share the same provider type,
 * or returns {@code null} if this factory cannot handle the wind sources given.
 * <p>
 * Implementations bundle requests for wind sources of equal type into a single feeder and register themselves
 * as OSGi services of this type. {@code RacingEventServiceImpl} discovers them via a {@code ServiceTracker}.
 */
public interface WindLiveSubscriptionFeederFactory {
    /**
     * Creates a feeder for the given {@code windSources} if this factory supports their type,
     * or returns {@code null} if the wind sources are not handled by this factory.
     *
     * @param correctByDeclination whether to correct raw wind bearings for magnetic declination
     * @throws Exception if a supported wind source set cannot be connected
     */
    WindLiveSubscriptionFeeder createFeeder(WindLiveSubscription subscription,
            Collection<WindSource> windSources, boolean correctByDeclination) throws Exception;
}
