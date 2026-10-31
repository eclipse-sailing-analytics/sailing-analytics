package com.sap.sailing.gwt.managementconsole.places.event.edit;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.google.gwt.event.shared.EventBus;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.AcceptsOneWidget;
import com.sap.sailing.domain.common.dto.CourseAreaDTO;
import com.sap.sailing.gwt.managementconsole.app.ManagementConsoleClientFactory;
import com.sap.sailing.gwt.managementconsole.places.AbstractManagementConsoleActivity;
import com.sap.sailing.gwt.managementconsole.places.event.overview.EventOverviewPlace;
import com.sap.sailing.gwt.managementconsole.places.regatta.overview.RegattaOverviewPlace;
import com.sap.sailing.gwt.ui.shared.EventDTO;
import com.sap.sailing.gwt.ui.shared.VenueDTO;
import com.sap.sse.gwt.client.Notification;
import com.sap.sse.gwt.client.Notification.NotificationType;

public class EditEventActivity extends AbstractManagementConsoleActivity<EditEventPlace> {

    private EditEventView editEventView;
    private EventDTO currentEvent;

    public EditEventActivity(final ManagementConsoleClientFactory clientFactory, final EditEventPlace place) {
        super(clientFactory, place);
    }

    @Override
    public void start(final AcceptsOneWidget container, final EventBus eventBus) {
        editEventView = new EditEventViewImpl();
        new EditEventViewPresenter(editEventView);
        container.setWidget(editEventView);

        // Load the event data
        loadEvent(getPlace().getEventId());
    }

    private void loadEvent(final UUID eventId) {
        getClientFactory().getEventService().getEvent(eventId, new AsyncCallback<EventDTO>() {
            @Override
            public void onFailure(Throwable caught) {
                Notification.notify("Failed to load event", NotificationType.ERROR);
                getClientFactory().getPlaceController().goTo(new EventOverviewPlace());
            }

            @Override
            public void onSuccess(EventDTO result) {
                currentEvent = result;
                editEventView.populateForm(result);
            }
        });
    }

    private class EditEventViewPresenter implements EditEventView.Presenter {

        public EditEventViewPresenter(EditEventView editEventView) {
            editEventView.setPresenter(this);
        }

        @Override
        public void saveEvent(final String name, final String description, final String venue, final Date startDate,
                final Date endDate, final List<CourseAreaDTO> courseAreas, final boolean isPublic) {
            // Update the event DTO with new values
            currentEvent.setName(name);
            currentEvent.setDescription(description);
            currentEvent.startDate = startDate;
            currentEvent.endDate = endDate;
            currentEvent.isPublic = isPublic;
            // Update venue
            VenueDTO venueDTO = currentEvent.getVenue();
            final List<CourseAreaDTO> oldCourseAreas = venueDTO != null && venueDTO.getCourseAreas() != null
                    ? new ArrayList<>(venueDTO.getCourseAreas()) : new ArrayList<CourseAreaDTO>();
            if (venueDTO == null) {
                venueDTO = new VenueDTO(venue);
                currentEvent.setVenue(venueDTO);
            } else {
                venueDTO.setName(venue);
            }
            // The course areas already carry their identity and geometry; persist them as-is. The server reconciles
            // added and removed course areas separately from the event update, so hand over the previous set for diffing.
            venueDTO.setCourseAreas(courseAreas);
            getClientFactory().getEventService().updateEvent(currentEvent, oldCourseAreas, getUpdateEventCallback());
        }

        @Override
        public void loadLocalEvents(final AsyncCallback<List<EventDTO>> callback) {
            getClientFactory().getEventService().getAllEvents(callback);
        }

        @Override
        public void loadRemoteEvents(final String baseUrl, final String bearerTokenOrNull,
                final AsyncCallback<List<EventDTO>> callback) {
            getClientFactory().getEventService().getRemoteEvents(baseUrl, bearerTokenOrNull, callback);
        }

        private AsyncCallback<EventDTO> getUpdateEventCallback() {
            return new AsyncCallback<EventDTO>() {

                @Override
                public void onFailure(Throwable caught) {
                    Notification.notify("Failed to save event: " + caught.getMessage(), NotificationType.ERROR);
                }

                @Override
                public void onSuccess(EventDTO result) {
                    Notification.notify("Event saved successfully", NotificationType.SUCCESS);
                    getClientFactory().getPlaceController().goTo(new RegattaOverviewPlace(result.getId()));
                }
            };
        }

        @Override
        public void cancelEdit() {
            getClientFactory().getPlaceController().goTo(new EventOverviewPlace());
        }

    }

}
