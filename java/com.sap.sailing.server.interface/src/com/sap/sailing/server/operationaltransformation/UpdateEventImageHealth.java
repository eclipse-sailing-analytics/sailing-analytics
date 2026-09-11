package com.sap.sailing.server.operationaltransformation;

import java.util.UUID;

import com.sap.sailing.server.interfaces.RacingEventService;
import com.sap.sailing.server.interfaces.RacingEventServiceOperation;

/**
 * Updates the persisted health state for an image URL attached to an event.
 */
public class UpdateEventImageHealth extends AbstractEventOperation<Void> {
    private static final long serialVersionUID = 6923230063351853605L;

    private final String imageUrl;
    private final boolean missing;
    private final boolean missingMailNotificationSent;

    public UpdateEventImageHealth(UUID eventId, String imageUrl, boolean missing, boolean missingMailNotificationSent) {
        super(eventId);
        this.imageUrl = imageUrl;
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
        toState.updateEventImageHealth(getId(), imageUrl, missing, missingMailNotificationSent);
        return null;
    }
}
