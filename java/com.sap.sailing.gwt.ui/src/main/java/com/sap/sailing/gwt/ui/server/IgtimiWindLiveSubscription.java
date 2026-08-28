package com.sap.sailing.gwt.ui.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.sap.sailing.domain.common.Wind;
import com.sap.sailing.domain.igtimiadapter.IgtimiConnection;
import com.sap.sailing.domain.igtimiadapter.LiveDataConnection;
import com.sap.sailing.domain.igtimiadapter.shared.IgtimiWindReceiver;
import com.sap.sailing.gwt.ui.shared.SailingServiceConstants;

/**
 * Server-side live wind subscription for a set of Igtimi devices.
 *
 * <p>Created when a client selects devices in AdminConsole. Connects to the Igtimi Riot stream via
 * {@link LiveDataConnection}, feeds incoming protobuf messages through {@link IgtimiWindReceiver},
 * and buffers the resulting {@link com.sap.sailing.domain.common.Wind} fixes per device serial
 * number. The client drains that buffer every ~3 seconds via
 * {@code SailingService.getIgtimiWindLiveUpdates}.
 *
 * <p>Subscriptions are identified by a random UUID and are owned by the user who created them.
 * Every access touches {@code lastAccessInMilliseconds}; subscriptions idle for more than
 * {@value #IDLE_TIMEOUT_IN_MILLISECONDS} ms are eligible for server-side cleanup.
 */
public class IgtimiWindLiveSubscription {
    private static final long IDLE_TIMEOUT_IN_MILLISECONDS = 2*60*1000l; // two minutes
    private final String subscriptionId;
    private final String ownerName;
    private final LiveDataConnection liveDataConnection;
    private final IgtimiWindReceiver windReceiver;
    private final Map<String, LinkedList<Wind>> windsByDeviceSerialNumber;
    private volatile long lastAccessInMilliseconds;
    private boolean stopped;

    public IgtimiWindLiveSubscription(String ownerName, IgtimiConnection connection, Iterable<String> deviceSerialNumbers) throws Exception {
        subscriptionId = UUID.randomUUID().toString();
        this.ownerName = ownerName;
        windsByDeviceSerialNumber = new HashMap<>();
        windReceiver = new IgtimiWindReceiver(/* no declination correction */ null);
        windReceiver.addListener((wind, fixesUsed, deviceSerialNumber) -> {
            synchronized (windsByDeviceSerialNumber) {
                final LinkedList<Wind> winds = windsByDeviceSerialNumber.computeIfAbsent(deviceSerialNumber, key -> new LinkedList<>());
                if (winds.size() >= SailingServiceConstants.MAX_NUMBER_OF_WIND_FIXES_TO_DELIVER_IN_ONE_CALL) {
                    winds.removeFirst();
                }
                winds.add(wind);
            }
        });
        liveDataConnection = connection.getOrCreateLiveConnection(deviceSerialNumbers);
        if (liveDataConnection != null) {
            liveDataConnection.addListener(windReceiver);
        }
        lastAccessInMilliseconds = System.currentTimeMillis();
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public boolean waitForConnection(String ownerName, long timeoutInMillis) throws InterruptedException {
        checkOwnerAndTouch(ownerName);
        return liveDataConnection != null && liveDataConnection.waitForConnection(timeoutInMillis);
    }

    public Map<String, List<Wind>> getAndClearWinds(String ownerName) {
        checkOwnerAndTouch(ownerName);
        final Map<String, List<Wind>> result = new HashMap<>();
        synchronized (windsByDeviceSerialNumber) {
            windsByDeviceSerialNumber.forEach((deviceSerialNumber, winds) -> result.put(deviceSerialNumber, new ArrayList<>(winds)));
            windsByDeviceSerialNumber.clear();
        }
        return result;
    }

    public void stop(String ownerName) throws Exception {
        checkOwner(ownerName);
        stop();
    }

    boolean isIdle(long currentTimeInMilliseconds) {
        return currentTimeInMilliseconds - lastAccessInMilliseconds >= IDLE_TIMEOUT_IN_MILLISECONDS;
    }

    synchronized void stop() throws Exception {
        if (!stopped) {
            stopped = true;
            if (liveDataConnection != null) {
                try {
                    liveDataConnection.stop();
                } finally {
                    liveDataConnection.removeListener(windReceiver);
                }
            }
        }
    }

    private void checkOwnerAndTouch(String ownerName) {
        checkOwner(ownerName);
        lastAccessInMilliseconds = System.currentTimeMillis();
    }

    private void checkOwner(String ownerName) {
        if (!Objects.equals(this.ownerName, ownerName)) {
            throw new SecurityException("Igtimi wind live subscription belongs to a different user");
        }
    }
}