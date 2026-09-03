package com.sap.sailing.gwt.ui.server;

/**
 * Provider-specific connectivity that feeds a {@link WindLiveSubscription}.
 */
interface WindLiveSubscriptionFeeder extends AutoCloseable {
    /**
     * Returns whether this feeder has successfully connected at least once.
     */
    boolean hasConnected();
}