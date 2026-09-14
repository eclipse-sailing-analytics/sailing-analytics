package com.sap.sailing.server.interfaces;

/**
 * Provider-specific connectivity that feeds a {@link WindLiveSubscription}.
 */
public interface WindLiveSubscriptionFeeder extends AutoCloseable {
    /**
     * Returns whether this feeder has successfully connected at least once.
     */
    boolean hasConnected();
}
