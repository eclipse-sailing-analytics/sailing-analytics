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
import com.sap.sailing.server.interfaces.WindLiveSubscriptionFeederFactory;

/**
 * Creates {@link IgtimiWindLiveSubscriptionFeeder} instances for wind sources of type
 * {@link WindSourceType#EXPEDITION}, connecting to the Igtimi live data service.
 * <p>
 * An instance of this factory is registered as an OSGi service of type
 * {@link WindLiveSubscriptionFeederFactory} from {@link SailingServiceImpl}'s constructor,
 * giving {@code RacingEventServiceImpl} access to it through a {@code ServiceTracker}.
 * <p>
 * The {@code correctByDeclination} flag is passed per-call via
 * {@link #createFeeder(WindLiveSubscription, Collection, boolean)}, allowing each subscription
 * request to opt in or out of declination correction independently.
 */
public class IgtimiWindLiveSubscriptionFeederFactory implements WindLiveSubscriptionFeederFactory {
    private final Supplier<IgtimiConnection> connectionSupplier;
    private final Function<String, Device> deviceLookup;
    private final Consumer<Device> readPermissionChecker;

    public IgtimiWindLiveSubscriptionFeederFactory(final Supplier<IgtimiConnection> connectionSupplier,
            final Function<String, Device> deviceLookup,
            final Consumer<Device> readPermissionChecker) {
        this.connectionSupplier = connectionSupplier;
        this.deviceLookup = deviceLookup;
        this.readPermissionChecker = readPermissionChecker;
    }

    @Override
    public WindLiveSubscriptionFeeder createFeeder(final WindLiveSubscription subscription,
            final Collection<WindSource> windSources, final boolean correctByDeclination) throws Exception {
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
}
