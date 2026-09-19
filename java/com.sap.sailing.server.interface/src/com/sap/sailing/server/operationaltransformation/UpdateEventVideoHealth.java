package com.sap.sailing.server.operationaltransformation;

import java.util.UUID;

import com.sap.sailing.server.interfaces.RacingEventService;
import com.sap.sailing.server.interfaces.RacingEventServiceOperation;

/**
 * Updates the persisted health state for a video URL attached to an event.
 */
public class UpdateEventVideoHealth extends AbstractEventOperation<Void> {
    private static final long serialVersionUID = 4187320561438792034L;

    private final String videoUrl;
    private final boolean missing;
    private final boolean missingMailNotificationSent;

    public UpdateEventVideoHealth(UUID eventId, String videoUrl, boolean missing, boolean missingMailNotificationSent) {
        super(eventId);
        this.videoUrl = videoUrl;
        this.missing = missing;
        this.missingMailNotificationSent = missingMailNotificationSent;
    }

    @Override
    public RacingEventServiceOperation<?> transformClientOp(RacingEventServiceOperation<?> serverOp) {
        return null;
    }

    @Override
    public RacingEventServiceOperation<?> transformServerOp(RacingEventServiceOperation<?> clientOp) {
        return null;
    }

    @Override
    public Void internalApplyTo(RacingEventService toState) {
        toState.updateEventVideoHealth(getId(), videoUrl, missing, missingMailNotificationSent);
        return null;
    }
}
