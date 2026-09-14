package com.sap.sailing.gwt.ui.server;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.sap.sailing.domain.common.WindSource;
import com.sap.sailing.domain.common.WindSourceType;
import com.sap.sailing.domain.igtimiadapter.Device;
import com.sap.sailing.domain.igtimiadapter.IgtimiConnection;
import com.sap.sailing.server.interfaces.WindLiveSubscription;
import com.sap.sailing.server.interfaces.WindLiveSubscriptionFeeder;

/**
 * Creates {@link IgtimiWindLiveSubscriptionFeeder} instances for wind sources of type
 * {@link WindSourceType#EXPEDITION}.
 */
class IgtimiWindLiveSubscriptionFeederFactory implements WindLiveSubscriptionFeederFactory {
    private final Supplier<IgtimiConnection> connectionSupplier;
    private final Function<String, Device> deviceLookup;
    private final Consumer<Device> readPermissionChecker;
    private final boolean correctByDeclination;

    IgtimiWindLiveSubscriptionFeederFactory(final Supplier<IgtimiConnection> connectionSupplier,
            final Function<String, Device> deviceLookup,
            final Consumer<Device> readPermissionChecker,
            final boolean correctByDeclination) {
        this.connectionSupplier = connectionSupplier;
        this.deviceLookup = deviceLookup;
        this.readPermissionChecker = readPermissionChecker;
        this.correctByDeclination = correctByDeclination;
    }

    @Override
    public WindLiveSubscriptionFeeder createFeeder(final WindLiveSubscription subscription,
            final Collection<WindSource> windSources) throws Exception {
        final Map<String, WindSource> igtimiWindSourcesBySerialNumber = new HashMap<>();
        boolean allSupported = true;
        for (final WindSource windSource : windSources) {
            if (windSource == null || windSource.getType() != WindSourceType.EXPEDITION
                    || !(windSource.getId() instanceof String)) {
                allSupported = false;
            } else {
                final String serialNumber = (String) windSource.getId();
                final Device device = deviceLookup.apply(serialNumber);
                if (device == null) {
                    allSupported = false;
                } else {
                    readPermissionChecker.accept(device);
                    if (igtimiWindSourcesBySerialNumber.put(serialNumber, windSource) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate live wind source for Igtimi device " + serialNumber);
                    }
                }
            }
        }
        final WindLiveSubscriptionFeeder result;
        if (allSupported) {
            result = new IgtimiWindLiveSubscriptionFeeder(subscription, connectionSupplier.get(),
                    igtimiWindSourcesBySerialNumber, correctByDeclination);
        } else {
            result = null;
        }
        return result;
    }

    static IgtimiWindLiveSubscriptionFeederFactory forIgtimiConnection(
            final Supplier<IgtimiConnection> connectionSupplier,
            final Function<String, Device> deviceLookup,
            final Consumer<Device> readPermissionChecker) {
        return new IgtimiWindLiveSubscriptionFeederFactory(connectionSupplier, deviceLookup,
                readPermissionChecker, /* correctByDeclination */ true);
    }
}
