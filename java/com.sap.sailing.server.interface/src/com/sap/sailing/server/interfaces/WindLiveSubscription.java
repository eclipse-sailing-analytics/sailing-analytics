package com.sap.sailing.server.interfaces;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.sap.sailing.domain.common.Wind;
import com.sap.sailing.domain.common.WindImportConstants;
import com.sap.sailing.domain.common.WindSource;
import com.sap.sse.common.Duration;
import com.sap.sse.common.TimePoint;

/**
 * Provider-neutral server-side subscription that buffers {@link Wind} fixes per {@link WindSource}. Connectivity is
 * supplied by {@link WindLiveSubscriptionFeeder}s and is therefore kept separate from the subscription lifecycle and
 * buffered wind data.
 * <p>
 *
 * Clients receive the subscription {@link #getSubscriptionId() ID} which they can use in subsequent calls to work with
 * the subscription. The typical pattern then is that the requesting client keeps asking {@link #getAndClearWinds(String)}
 * on a regular basis, thus obtaining new {@link Wind} fixes supplied by any of the feeders. Access timestamps
 * are updated, and so {@link #isIdle(TimePoint)} is used by the service to recognize, stop and remove idle
 * subscriptions.<p>
 *
 * The {@link WindLiveSubscriptionFeeder feeders} have to be added after construction using
 * {@link #addFeeder(WindLiveSubscriptionFeeder)}. The feeders supply {@link Wind} objects to this subscription
 * using the {@link #addWind(WindSource, Wind)} method. This subscription will queue those new readings for the
 * client to pick up with {@link #getAndClearWinds(String)} asynchronously.<p>
 *
 * An instance of this type is tied to an "owner" by name. The owner name is validated in methods like
 * {@link #getAndClearWinds(String)} and {@link #stop(String)}, and a {@link SecurityException} is thrown in case
 * of a mismatch.
 */
public class WindLiveSubscription {
    private static final int MAX_WIND_FIXES_PER_DELIVERY = WindImportConstants.WIND_LIVE_MAX_FIXES_PER_DELIVERY;
    private static final Duration IDLE_TIMEOUT = Duration.ONE_MINUTE.times(2);
    private static final Duration CONNECTION_TIMEOUT = Duration.ONE_MINUTE.times(2);

    private final String subscriptionId;
    private final String ownerName;
    private final Map<WindSource, LinkedList<Wind>> windsByWindSource;
    private final TimePoint createdAt;
    private volatile TimePoint lastAccess;
    private boolean stopped;
    private final List<WindLiveSubscriptionFeeder> feeders;

    public WindLiveSubscription(final String ownerName) {
        subscriptionId = UUID.randomUUID().toString();
        this.ownerName = ownerName;
        windsByWindSource = new HashMap<>();
        createdAt = TimePoint.now();
        lastAccess = createdAt;
        feeders = new ArrayList<>();
    }

    public void addWind(final WindSource windSource, final Wind wind) {
        synchronized (windsByWindSource) {
            final LinkedList<Wind> winds = windsByWindSource.computeIfAbsent(windSource, key -> new LinkedList<>());
            if (winds.size() >= MAX_WIND_FIXES_PER_DELIVERY) {
                winds.removeFirst();
            }
            winds.add(wind);
        }
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public Map<WindSource, List<Wind>> getAndClearWinds(final String ownerName) {
        checkOwnerAndTouch(ownerName);
        final Map<WindSource, List<Wind>> result = new HashMap<>();
        synchronized (windsByWindSource) {
            windsByWindSource.forEach((windSource, winds) -> result.put(windSource, new ArrayList<>(winds)));
            windsByWindSource.clear();
        }
        return result;
    }

    public void stop(final String ownerName) throws Exception {
        checkOwner(ownerName);
        stop();
    }

    /**
     * Returns whether the subscription has not been accessed for the configured idle timeout (currently two minutes).
     * Such subscriptions are eligible for background cleanup.
     */
    public boolean isIdle(final TimePoint currentTime) {
        return lastAccess.until(currentTime).compareTo(IDLE_TIMEOUT) >= 0;
    }

    /**
     * Returns whether the connection grace period (currently two minutes) has elapsed while at least one feeder has
     * still never connected successfully. This is independent of {@link #isIdle(TimePoint)} because a client may keep
     * polling an unusable subscription.
     */
    public boolean hasFailedToConnect(final TimePoint currentTime) {
        final boolean result;
        if (createdAt.until(currentTime).compareTo(CONNECTION_TIMEOUT) < 0) {
            result = false;
        } else {
            boolean anyNotConnected = false;
            for (final WindLiveSubscriptionFeeder feeder : feeders) {
                if (!feeder.hasConnected()) {
                    anyNotConnected = true;
                    break;
                }
            }
            result = anyNotConnected;
        }
        return result;
    }

    public synchronized void stop() throws Exception {
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

    protected void checkOwnerAndTouch(final String ownerName) {
        checkOwner(ownerName);
        lastAccess = TimePoint.now();
    }

    private void checkOwner(final String ownerName) {
        if (!Objects.equals(this.ownerName, ownerName)) {
            throw new SecurityException("Wind live subscription belongs to a different user");
        }
    }

    /**
     * Adds the connectivity component that feeds this subscription and that must be closed when the subscription stops.
     */
    public void addFeeder(final WindLiveSubscriptionFeeder feeder) {
        feeders.add(feeder);
    }
}
