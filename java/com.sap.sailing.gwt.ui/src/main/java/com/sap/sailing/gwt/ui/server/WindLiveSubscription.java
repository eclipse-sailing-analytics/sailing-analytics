package com.sap.sailing.gwt.ui.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.sap.sailing.domain.common.Wind;
import com.sap.sailing.domain.common.WindSource;
import com.sap.sailing.gwt.ui.shared.SailingServiceConstants;
import com.sap.sse.common.Duration;
import com.sap.sse.common.TimePoint;

/**
 * Provider-neutral server-side subscription that buffers {@link Wind} fixes per {@link WindSource}. Connectivity is
 * supplied by {@link WindLiveSubscriptionFeeder}s and is therefore kept separate from the subscription lifecycle and
 * buffered wind data.
 */
public class WindLiveSubscription {
    private static final Duration IDLE_TIMEOUT = Duration.ONE_MINUTE.times(2);
    private static final Duration CONNECTION_TIMEOUT = Duration.ONE_MINUTE.times(2);

    private final String subscriptionId;
    private final String ownerName;
    private final Map<WindSource, LinkedList<Wind>> windsByWindSource;
    private final TimePoint createdAt;
    private volatile TimePoint lastAccess;
    private boolean stopped;
    private final List<WindLiveSubscriptionFeeder> feeders;
    
    public WindLiveSubscription(String ownerName) {
        subscriptionId = UUID.randomUUID().toString();
        this.ownerName = ownerName;
        windsByWindSource = new HashMap<>();
        createdAt = TimePoint.now();
        lastAccess = createdAt;
        feeders = new ArrayList<>();
    }
    
    void addWind(WindSource windSource, Wind wind) {
        synchronized (windsByWindSource) {
            final LinkedList<Wind> winds = windsByWindSource.computeIfAbsent(windSource, key -> new LinkedList<>());
            if (winds.size() >= SailingServiceConstants.MAX_NUMBER_OF_WIND_FIXES_TO_DELIVER_IN_ONE_CALL) {
                winds.removeFirst();
            }
            winds.add(wind);
        }
    }
    
    public String getSubscriptionId() {
        return subscriptionId;
    }
    
    public Map<WindSource, List<Wind>> getAndClearWinds(String ownerName) {
        checkOwnerAndTouch(ownerName);
        final Map<WindSource, List<Wind>> result = new HashMap<>();
        synchronized (windsByWindSource) {
            windsByWindSource.forEach((windSource, winds) -> result.put(windSource, new ArrayList<>(winds)));
            windsByWindSource.clear();
        }
        return result;
    }
    
    public void stop(String ownerName) throws Exception {
        checkOwner(ownerName);
        stop();
    }
    
    /**
     * Returns whether the subscription has not been accessed for the configured idle timeout (currently two minutes).
     * Such subscriptions are eligible for background cleanup.
     */
    boolean isIdle(TimePoint currentTime) {
        return lastAccess.until(currentTime).compareTo(IDLE_TIMEOUT) >= 0;
    }
    
    /**
     * Returns whether the connection grace period (currently two minutes) has elapsed while at least one feeder has
     * still never connected successfully. This is independent of {@link #isIdle(TimePoint)} because a client may keep
     * polling an unusable subscription.
     */
    boolean hasFailedToConnect(TimePoint currentTime) {
        if (createdAt.until(currentTime).compareTo(CONNECTION_TIMEOUT) < 0) {
            return false;
        }
        for (final WindLiveSubscriptionFeeder feeder : feeders) {
            if (!feeder.hasConnected()) {
                return true;
            }
        }
        return false;
    }
    
    synchronized void stop() throws Exception {
        if (!stopped) {
            stopped = true;
            Exception firstException = null;
            for (final WindLiveSubscriptionFeeder feeder : feeders) {
                try {
                    feeder.close();
                } catch (final Exception e) {
                    if (firstException == null) {
                        firstException = e;
                    }
                }
            }
            if (firstException != null) {
                throw firstException;
            }
        }
    }
    
    protected void checkOwnerAndTouch(String ownerName) {
        checkOwner(ownerName);
        lastAccess = TimePoint.now();
    }

    private void checkOwner(String ownerName) {
        if (!Objects.equals(this.ownerName, ownerName)) {
            throw new SecurityException("Wind live subscription belongs to a different user");
        }
    }
    
    /**
     * Adds the connectivity component that feeds this subscription and that must be closed when the subscription stops.
     */
    void addFeeder(WindLiveSubscriptionFeeder feeder) {
        feeders.add(feeder);
    }
}