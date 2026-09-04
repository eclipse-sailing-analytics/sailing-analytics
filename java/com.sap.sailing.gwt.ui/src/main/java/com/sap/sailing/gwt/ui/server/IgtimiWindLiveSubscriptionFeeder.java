package com.sap.sailing.gwt.ui.server;

import java.util.HashMap;
import java.util.Map;

import com.sap.sailing.domain.common.WindSource;
import com.sap.sailing.domain.igtimiadapter.IgtimiConnection;
import com.sap.sailing.domain.igtimiadapter.LiveDataConnection;
import com.sap.sailing.domain.igtimiadapter.shared.IgtimiWindReceiver;

/**
 * Feeds wind received through an Igtimi live connection into a provider-neutral
 * {@link WindLiveSubscription}.
 */
public class IgtimiWindLiveSubscriptionFeeder implements WindLiveSubscriptionFeeder {
    private final LiveDataConnection liveDataConnection;
    private final IgtimiWindReceiver windReceiver;
    private volatile boolean connected;

    IgtimiWindLiveSubscriptionFeeder(WindLiveSubscription subscription, IgtimiConnection connection,
            Map<String, WindSource> windSourcesByDeviceSerialNumber) throws Exception {
        final Map<String, WindSource> windSourcesByDeviceSerialNumberCopy =
                new HashMap<>(windSourcesByDeviceSerialNumber);
        windReceiver = new IgtimiWindReceiver(/* no declination correction */ null);
        windReceiver.addListener((wind, fixesUsed, deviceSerialNumber)->{
            final WindSource windSource = windSourcesByDeviceSerialNumberCopy.get(deviceSerialNumber);
            if (windSource != null) {
                subscription.addWind(windSource, wind);
            }
        });
        liveDataConnection = connection.getOrCreateLiveConnection(windSourcesByDeviceSerialNumberCopy.keySet());
        if (liveDataConnection != null) {
            liveDataConnection.addListener(windReceiver);
        }
        connected = liveDataConnection != null && liveDataConnection.isConnected();
    }
    
    @Override
    public boolean hasConnected() {
        if (!connected && liveDataConnection != null && liveDataConnection.isConnected()) {
            connected = true;
        }
        return connected;
    }

    @Override
    public void close() throws Exception {
        if (liveDataConnection != null) {
            try {
                liveDataConnection.stop();
            } finally {
                liveDataConnection.removeListener(windReceiver);
            }
        }
    }
}