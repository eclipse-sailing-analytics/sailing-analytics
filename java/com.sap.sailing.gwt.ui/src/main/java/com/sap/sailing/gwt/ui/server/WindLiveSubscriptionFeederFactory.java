package com.sap.sailing.gwt.ui.server;

import java.util.Collection;

import com.sap.sailing.domain.common.WindSource;
import com.sap.sailing.server.interfaces.WindLiveSubscription;
import com.sap.sailing.server.interfaces.WindLiveSubscriptionFeeder;

/**
 * Produces a {@link WindLiveSubscriptionFeeder} for a set of wind sources that share the same provider type,
 * or returns {@code null} if this factory cannot handle the wind sources given.
 * <p>
 * Implementations bundle requests for wind sources of equal type into a single feeder.
 */
interface WindLiveSubscriptionFeederFactory {
    /**
     * Creates a feeder for the given {@code windSources} if this factory supports their type,
     * or returns {@code null} if the wind sources are not handled by this factory.
     * All wind sources in the collection are guaranteed to have the same {@link com.sap.sailing.domain.common.WindSourceType}.
     *
     * @throws Exception if a supported wind source set cannot be connected
     */
    WindLiveSubscriptionFeeder createFeeder(WindLiveSubscription subscription,
            Collection<WindSource> windSources) throws Exception;
}
