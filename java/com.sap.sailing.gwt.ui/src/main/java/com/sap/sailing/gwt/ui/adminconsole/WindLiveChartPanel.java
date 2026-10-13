package com.sap.sailing.gwt.ui.adminconsole;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.CheckBox;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RequiresResize;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.sap.sailing.domain.common.WindSource;
import com.sap.sailing.gwt.ui.client.SailingServiceWriteAsync;
import com.sap.sailing.gwt.ui.client.StringMessages;
import com.sap.sailing.gwt.ui.shared.WindInfoForRaceDTO;
import com.sap.sailing.gwt.ui.shared.WindTrackInfoDTO;
import com.sap.sse.common.Duration;
import com.sap.sse.common.impl.MillisecondsTimePoint;
import com.sap.sse.gwt.client.ErrorReporter;
import com.sap.sse.gwt.client.Notification;
import com.sap.sse.gwt.client.Notification.NotificationType;
import com.sap.sse.gwt.client.controls.IntegerBox;
import com.sap.sse.gwt.client.controls.datetime.DateAndTimeInput;
import com.sap.sse.gwt.client.controls.datetime.DateTimeInput.Accuracy;

public class WindLiveChartPanel extends VerticalPanel implements RequiresResize {
    @FunctionalInterface
    public interface WindHistoryLoader {
        void load(WindSource windSource, Date from, Date to, AsyncCallback<WindInfoForRaceDTO> callback);
    }

    private static final int DEFAULT_DURATION_MINUTES = 5;
    private static final int MAX_DURATION_MINUTES = 120;
    private static final Duration DEFAULT_WIND_UPDATE_INTERVAL = Duration.ONE_SECOND.times(3);

    private final SailingServiceWriteAsync sailingServiceWrite;
    private final WindHistoryLoader windHistoryLoader;
    private final ErrorReporter errorReporter;
    private final Duration windUpdateInterval;
    private final StringMessages stringMessages;
    private final SimplePanel chartContainer;
    private WindLiveChart windChart;
    private WindInfoForRaceDTO lastHistoryData;
    private final Set<WindSource> shownWindSources = new HashSet<>();
    private String windLiveSubscriptionId;
    private int windSelectionVersion;
    private String windLiveUpdateRequestPendingSubscriptionId;

    private final HorizontalPanel header;
    private final CheckBox liveCheckBox;
    private final IntegerBox durationBox;
    private final SimplePanel durationPanel;
    private final DateAndTimeInput fromInput;
    private final DateAndTimeInput toInput;
    private final SimplePanel staticPanel;

    private final Timer windLiveUpdateTimer = new Timer() {
        @Override
        public void run() {
            loadWindLiveUpdates();
        }
    };

    public WindLiveChartPanel(final SailingServiceWriteAsync sailingServiceWrite,
            final WindHistoryLoader windHistoryLoader, final StringMessages stringMessages,
            final ErrorReporter errorReporter) {
        this(sailingServiceWrite, windHistoryLoader, stringMessages, errorReporter,
                DEFAULT_WIND_UPDATE_INTERVAL);
    }

    public WindLiveChartPanel(final SailingServiceWriteAsync sailingServiceWrite,
            final WindHistoryLoader windHistoryLoader, final StringMessages stringMessages,
            final ErrorReporter errorReporter, final Duration windUpdateInterval) {
        this.sailingServiceWrite = sailingServiceWrite;
        this.windHistoryLoader = windHistoryLoader;
        this.errorReporter = errorReporter;
        this.windUpdateInterval = windUpdateInterval;
        this.stringMessages = stringMessages;
        // controls
        header = new HorizontalPanel();
        header.setSpacing(4);
        liveCheckBox = new CheckBox(stringMessages.windChartLive());
        liveCheckBox.setValue(true);
        header.add(liveCheckBox);
        // live mode: duration input
        durationBox = new IntegerBox();
        durationBox.setVisibleLength(3);
        durationBox.setValue(DEFAULT_DURATION_MINUTES);
        final HorizontalPanel durationInner = new HorizontalPanel();
        durationInner.setSpacing(2);
        durationInner.add(durationBox);
        durationInner.add(new Label(stringMessages.windChartDurationMinutes()));
        durationPanel = new SimplePanel(durationInner);
        header.add(durationPanel);
        // static mode: from/to pickers + show button
        fromInput = new DateAndTimeInput(Accuracy.MINUTES);
        toInput = new DateAndTimeInput(Accuracy.MINUTES);
        final Button showButton = new Button(stringMessages.windChartShow());
        final HorizontalPanel staticInner = new HorizontalPanel();
        staticInner.setSpacing(2);
        staticInner.add(new Label(stringMessages.windChartFrom()));
        staticInner.add(fromInput);
        staticInner.add(new Label(stringMessages.windChartTo()));
        staticInner.add(toInput);
        staticInner.add(showButton);
        staticPanel = new SimplePanel(staticInner);
        staticPanel.setVisible(false);
        header.add(staticPanel);
        // orientation toggle
        final CheckBox orientationToggle = new CheckBox(stringMessages.windChartVertical());
        orientationToggle.addValueChangeHandler(event -> toggleOrientation());
        header.add(orientationToggle);
        add(header);
        // chart
        windChart = new WindLiveChart(stringMessages);
        chartContainer = new SimplePanel(windChart);
        chartContainer.setWidth("100%");
        chartContainer.setHeight("100%");
        add(chartContainer);
        // wire controls
        liveCheckBox.addValueChangeHandler(this::onLiveToggled);
        durationBox.addValueChangeHandler(event -> onSettingsChanged());
        showButton.addClickHandler(event -> onShowClicked());
    }

    /**
     * Handles the Live checkbox toggle.
     * <p>
     * Live → static: stops the subscription, freezes the current [now-duration, now] window into
     * the from/to pickers and locks the x-axis there. The already-displayed data stays visible —
     * no re-fetch.
     * <p>
     * Static → live: resets duration to the default and restarts live mode.
     */
    private void onLiveToggled(final ValueChangeEvent<Boolean> event) {
        final boolean live = Boolean.TRUE.equals(event.getValue());
        durationPanel.setVisible(live);
        staticPanel.setVisible(!live);
        if (live) {
            durationBox.setValue(DEFAULT_DURATION_MINUTES);
            onSettingsChanged();
        } else {
            // freeze at current live window(stop timer, populate pickers, keep displayed data as-is)
            stopWindLiveSubscription();
            final int durationMinutes = clampDuration(durationBox.getValue());
            final Date to = new Date();
            final Date from = new MillisecondsTimePoint(to).minus(Duration.ONE_MINUTE.times(durationMinutes)).asDate();
            fromInput.setValue(from);
            toInput.setValue(to);
            windChart.setXAxisExtremes(from.getTime(), to.getTime());
        }
    }

    /**
     * Validates the from/to range and triggers a static history load on success.
     * Shows a WARNING notification if to ≤ from or if the range exceeds 2 hours.
     */
    private void onShowClicked() {
        final Date from = fromInput.getValue();
        final Date to = toInput.getValue();
        if (from == null || to == null) {
            return;
        }
        if (!to.after(from)) {
            Notification.notify(stringMessages.windChartToBeforeFrom(), NotificationType.WARNING);
            return;
        }
        final long rangeMillis = to.getTime() - from.getTime();
        final long twoHoursMillis = Duration.ONE_MINUTE.times(MAX_DURATION_MINUTES).asMillis();
        if (rangeMillis > twoHoursMillis) {
            Notification.notify(stringMessages.windChartRangeExceeded(), NotificationType.WARNING);
            return;
        }
        if (!shownWindSources.isEmpty()) {
            loadStaticHistory(new HashSet<>(shownWindSources), from, to);
        }
    }

    /** Called when the duration box changes; restarts live mode with the new window size. */
    private void onSettingsChanged() {
        final Integer raw = durationBox.getValue();
        if (raw != null && raw > MAX_DURATION_MINUTES) {
            Notification.notify(stringMessages.windChartRangeExceeded(), NotificationType.WARNING);
            return;
        }
        if (!shownWindSources.isEmpty()) {
            reloadForCurrentSettings();
        }
    }

    /**
     * Sets the wind sources to display. Hides the panel when the set is empty.
     * Switches to live mode unconditionally: any existing subscription is stopped,
     * history is loaded for [now-duration, now], and live updates begin.
     */
    public void setSelectedWindSources(final Set<WindSource> selectedWindSources) {
        final Set<WindSource> windSources = new HashSet<>(selectedWindSources);
        stopWindLiveSubscription();
        lastHistoryData = null;
        for (final WindSource windSource : shownWindSources) {
            windChart.removeWindSource(windSource);
        }
        shownWindSources.clear();
        setVisible(!windSources.isEmpty());
        if (!windSources.isEmpty()) {
            shownWindSources.addAll(windSources);
            Scheduler.get().scheduleDeferred(windChart::onResize);
            reloadForCurrentSettings();
        }
    }

    /** Stops any current subscription and restarts live mode for the current duration. */
    private void reloadForCurrentSettings() {
        stopWindLiveSubscription();
        lastHistoryData = null;
        final int durationMinutes = clampDuration(durationBox.getValue());
        final Duration duration = Duration.ONE_MINUTE.times(durationMinutes);
        final Date to = new Date();
        final Date from = new MillisecondsTimePoint(to).minus(duration).asDate();
        startLiveMode(new HashSet<>(shownWindSources), from, to, duration);
    }

    /** Returns the duration clamped to [1, MAX_DURATION_MINUTES], falling back to the default if null or invalid. */
    private int clampDuration(final Integer raw) {
        if (raw == null || raw < 1) {
            return DEFAULT_DURATION_MINUTES;
        }
        return Math.min(raw, MAX_DURATION_MINUTES);
    }
    
    /**
     * Opens a server-side live subscription, loads history for [from, to] across all wind sources
     * in parallel, then — once all responses arrive — shows the combined data, applies the initial
     * x-axis window, and starts the periodic update timer.
     * <p>
     * Uses {@code windSelectionVersion} to discard stale callbacks: if the version has advanced by
     * the time a response arrives, the result is dropped and any orphaned subscription is stopped.
     */
    private void startLiveMode(final Set<WindSource> windSources, final Date from, final Date to,
            final Duration duration) {
        final int selectionVersion = ++windSelectionVersion;
        sailingServiceWrite.startWindLiveSubscription(
                windSources, /* correctByDeclination */ true,
                new AsyncCallback<String>() {
                    @Override
                    public void onSuccess(final String subscriptionId) {
                        if (selectionVersion != windSelectionVersion) {
                            stopWindLiveSubscription(subscriptionId);
                        } else {
                            windLiveSubscriptionId = subscriptionId;
                            loadHistory(windSources, from, to, selectionVersion, () -> {
                                applyLiveExtremes(duration);
                                loadWindLiveUpdates();
                                windLiveUpdateTimer.scheduleRepeating((int) windUpdateInterval.asMillis());
                            });
                        }
                    }

                    @Override
                    public void onFailure(final Throwable caught) {
                        if (selectionVersion == windSelectionVersion) {
                            errorReporter.reportError(caught.getMessage());
                        }
                    }
                });
    }

    /** Sets the x-axis to [now-duration, now]. Called on each live tick (unless user is zoomed). */
    private void applyLiveExtremes(final Duration duration) {
        final long toMillis = new Date().getTime();
        final long fromMillis = toMillis - duration.asMillis();
        windChart.setXAxisExtremes(fromMillis, toMillis);
    }

    /**
     * Fetches the latest live data points for the current subscription and appends them to the chart.
     * Guards against concurrent requests: a second call while one is in flight is a no-op.
     * After appending, advances the x-axis sliding window unless the user has dragged to zoom.
     */
    private void loadWindLiveUpdates() {
        final String subscriptionId = windLiveSubscriptionId;
        if (subscriptionId != null
                && !subscriptionId.equals(windLiveUpdateRequestPendingSubscriptionId)) {
            windLiveUpdateRequestPendingSubscriptionId = subscriptionId;
            sailingServiceWrite.getWindLiveUpdates(
                    subscriptionId,
                    new AsyncCallback<WindInfoForRaceDTO>() {
                        @Override
                        public void onSuccess(final WindInfoForRaceDTO result) {
                            if (subscriptionId.equals(windLiveUpdateRequestPendingSubscriptionId)) {
                                windLiveUpdateRequestPendingSubscriptionId = null;
                            }
                            if (subscriptionId.equals(windLiveSubscriptionId)) {
                                mergeIntoLastHistoryData(result);
                                windChart.appendData(result);
                                if (!windChart.isUserZoomed()) {
                                    final int durationMinutes = clampDuration(durationBox.getValue());
                                    applyLiveExtremes(Duration.ONE_MINUTE.times(durationMinutes));
                                }
                            }
                        }

                        @Override
                        public void onFailure(final Throwable caught) {
                            if (subscriptionId.equals(windLiveUpdateRequestPendingSubscriptionId)) {
                                windLiveUpdateRequestPendingSubscriptionId = null;
                            }
                            if (subscriptionId.equals(windLiveSubscriptionId)) {
                                errorReporter.reportError(caught.getMessage());
                            }
                        }
                    });
        }
    }

    /**
     * Loads history for the given [from, to] range once and locks the x-axis to that window.
     * No timer is started; the chart is static until the user clicks Show again.
     */
    private void loadStaticHistory(final Set<WindSource> windSources, final Date from, final Date to) {
        final int selectionVersion = ++windSelectionVersion;
        loadHistory(windSources, from, to, selectionVersion, () -> {
            windChart.setXAxisExtremes(from.getTime(), to.getTime());
        });
    }

    /**
     * Fires one history request per wind source in parallel and collects the results into a single
     * {@link WindInfoForRaceDTO}. Calls {@code onComplete} once all requests have returned (success
     * or failure). Stale responses (version mismatch) are silently discarded.
     */
    private void loadHistory(final Set<WindSource> windSources, final Date from, final Date to,
            final int selectionVersion, final Runnable onComplete) {
        final Set<WindSource> pending = new HashSet<>(windSources);
        final WindInfoForRaceDTO combined = new WindInfoForRaceDTO();
        combined.windTrackInfoByWindSource = new HashMap<>();
        for (final WindSource windSource : windSources) {
            windHistoryLoader.load(windSource, from, to, new AsyncCallback<WindInfoForRaceDTO>() {
                @Override
                public void onSuccess(final WindInfoForRaceDTO result) {
                    if (selectionVersion == windSelectionVersion) {
                        if (result != null && result.windTrackInfoByWindSource != null) {
                            combined.windTrackInfoByWindSource.putAll(result.windTrackInfoByWindSource);
                        }
                        onHistoryRequestCompleted(windSource, pending, combined, selectionVersion, onComplete);
                    }
                }
                @Override
                public void onFailure(final Throwable caught) {
                    if (selectionVersion == windSelectionVersion) {
                        errorReporter.reportError(caught.getMessage());
                        onHistoryRequestCompleted(windSource, pending, combined, selectionVersion, onComplete);
                    }
                }
            });
        }
    }

    /**
     * Called after each individual history request completes. When the last pending source is
     * done, renders the combined data and runs {@code onComplete} (e.g. apply x-axis extremes,
     * start live timer).
     */
    private void onHistoryRequestCompleted(final WindSource windSource, final Set<WindSource> pending,
            final WindInfoForRaceDTO combined, final int selectionVersion, final Runnable onComplete) {
        pending.remove(windSource);
        if (selectionVersion == windSelectionVersion && pending.isEmpty()) {
            lastHistoryData = combined;
            windChart.showData(combined);
            onComplete.run();
        }
    }

    /**
     * Appends new wind fixes from a live update into {@code lastHistoryData} so that orientation
     * toggles and re-renders always have the full accumulated data set available.
     */
    private void mergeIntoLastHistoryData(final WindInfoForRaceDTO appendedData) {
        if (appendedData == null || appendedData.windTrackInfoByWindSource == null) {
            return;
        }
        if (lastHistoryData == null) {
            lastHistoryData = appendedData;
            return;
        }
        if (lastHistoryData.windTrackInfoByWindSource == null) {
            lastHistoryData.windTrackInfoByWindSource = new HashMap<>();
        }
        for (final WindSource windSource : appendedData.windTrackInfoByWindSource.keySet()) {
            final WindTrackInfoDTO newTrack = appendedData.windTrackInfoByWindSource.get(windSource);
            if (newTrack == null || newTrack.windFixes == null || newTrack.windFixes.isEmpty()) {
                continue;
            }
            final WindTrackInfoDTO existing = lastHistoryData.windTrackInfoByWindSource.get(windSource);
            if (existing == null) {
                lastHistoryData.windTrackInfoByWindSource.put(windSource, newTrack);
            } else {
                if (existing.windFixes == null) {
                    existing.windFixes = new ArrayList<>(newTrack.windFixes);
                } else {
                    existing.windFixes.addAll(newTrack.windFixes);
                }
            }
        }
    }

    /** Cancels the update timer and fires a server-side stop for the current subscription, if any. */
    private void stopWindLiveSubscription() {
        windLiveUpdateTimer.cancel();
        windLiveUpdateRequestPendingSubscriptionId = null;
        final String subscriptionId = windLiveSubscriptionId;
        windLiveSubscriptionId = null;
        if (subscriptionId != null) {
            stopWindLiveSubscription(subscriptionId);
        }
    }

    private void stopWindLiveSubscription(final String subscriptionId) {
        sailingServiceWrite.stopWindLiveSubscription(subscriptionId, new AsyncCallback<Void>() {
            @Override
            public void onSuccess(final Void result) {
            }

            @Override
            public void onFailure(final Throwable caught) {
            }
        });
    }

    /**
     * Replaces the current chart with a new one with the opposite orientation, preserving the
     * color map so series keep their assigned colors. Re-renders {@code lastHistoryData} and
     * restores the x-axis window (live or static). Resumes the live update timer if a subscription
     * is active.
     */
    private void toggleOrientation() {
        windLiveUpdateTimer.cancel();
        windLiveUpdateRequestPendingSubscriptionId = null;
        final WindLiveChart newChart = new WindLiveChart(stringMessages, !windChart.isInverted(),
                windChart.getColorMap());
        windChart = newChart;
        chartContainer.setWidget(newChart);
        if (lastHistoryData != null) {
            newChart.showData(lastHistoryData);
        }
        if (windLiveSubscriptionId != null) {
            final int durationMinutes = clampDuration(durationBox.getValue());
            applyLiveExtremes(Duration.ONE_MINUTE.times(durationMinutes));
            loadWindLiveUpdates();
            windLiveUpdateTimer.scheduleRepeating((int) windUpdateInterval.asMillis());
        } else {
            final Date from = fromInput.getValue();
            final Date to = toInput.getValue();
            if (from != null && to != null) {
                newChart.setXAxisExtremes(from.getTime(), to.getTime());
            }
        }
    }

    /** Resizes the chart, subtracting the header row height so the legend is not clipped. */
    public void resizeChart(final int containerWidthPx, final int containerHeightPx) {
        windChart.resize(containerWidthPx, containerHeightPx - header.getOffsetHeight());
    }

    @Override
    public void onResize() {
        windChart.onResize();
    }

    @Override
    protected void onUnload() {
        super.onUnload();
        ++windSelectionVersion;
        stopWindLiveSubscription();
    }
}
