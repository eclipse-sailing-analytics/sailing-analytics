package com.sap.sailing.gwt.ui.adminconsole;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.CaptionPanel;
import com.google.gwt.user.client.ui.RequiresResize;
import com.sap.sailing.domain.common.WindSource;
import com.sap.sailing.gwt.ui.client.SailingServiceWriteAsync;
import com.sap.sailing.gwt.ui.client.StringMessages;
import com.sap.sailing.gwt.ui.shared.WindInfoForRaceDTO;
import com.sap.sse.common.Duration;
import com.sap.sse.common.impl.MillisecondsTimePoint;
import com.sap.sse.gwt.client.ErrorReporter;

public class WindLiveChartPanel extends CaptionPanel implements RequiresResize {
    @FunctionalInterface
    public interface WindHistoryLoader {
        void load(WindSource windSource, Date from, Date to, AsyncCallback<WindInfoForRaceDTO> callback);
    }

    private static final Duration DEFAULT_WIND_HISTORY_DURATION = Duration.ONE_MINUTE.times(10);
    private static final Duration DEFAULT_WIND_UPDATE_INTERVAL = Duration.ONE_SECOND.times(3);

    private final SailingServiceWriteAsync sailingServiceWrite;
    private final WindHistoryLoader windHistoryLoader;
    private final ErrorReporter errorReporter;
    private final Duration windHistoryDuration;
    private final Duration windUpdateInterval;
    private final WindLiveChart windChart;
    private final Set<WindSource> shownWindSources = new HashSet<>();
    private String windLiveSubscriptionId;
    private int windSelectionVersion;
    private String windLiveUpdateRequestPendingSubscriptionId;

    private final Timer windLiveUpdateTimer = new Timer() {
        @Override
        public void run() {
            loadWindLiveUpdates();
        }
    };

    /**
     * Creates a live wind chart using ten minutes of initial history and a three-second live update interval.
     */
    public WindLiveChartPanel(final SailingServiceWriteAsync sailingServiceWrite,
            final WindHistoryLoader windHistoryLoader, final StringMessages stringMessages,
            final ErrorReporter errorReporter) {
        this(sailingServiceWrite, windHistoryLoader, stringMessages, errorReporter,
                DEFAULT_WIND_HISTORY_DURATION, DEFAULT_WIND_UPDATE_INTERVAL);
    }

    /**
     * Creates a live wind chart with caller-defined initial history and live update intervals.
     */
    public WindLiveChartPanel(final SailingServiceWriteAsync sailingServiceWrite,
            final WindHistoryLoader windHistoryLoader, final StringMessages stringMessages,
            final ErrorReporter errorReporter, final Duration windHistoryDuration,
            final Duration windUpdateInterval) {
        super(stringMessages.windChart());
        this.sailingServiceWrite = sailingServiceWrite;
        this.windHistoryLoader = windHistoryLoader;
        this.errorReporter = errorReporter;
        this.windHistoryDuration = windHistoryDuration;
        this.windUpdateInterval = windUpdateInterval;
        windChart = new WindLiveChart(stringMessages);
        add(windChart);
        setVisible(false);
    }

    public void setSelectedWindSources(final Set<WindSource> selectedWindSources) {
        final Set<WindSource> windSources = new HashSet<>(selectedWindSources);
        final int selectionVersion = ++windSelectionVersion;
        stopWindLiveSubscription();
        for (final WindSource windSource : shownWindSources) {
            windChart.removeWindSource(windSource);
        }
        shownWindSources.clear();
        setVisible(!windSources.isEmpty());
        if (!windSources.isEmpty()) {
            Scheduler.get().scheduleDeferred(windChart::onResize);
            sailingServiceWrite.startWindLiveSubscription(
                    windSources,
                    new AsyncCallback<String>() {
                        @Override
                        public void onSuccess(final String subscriptionId) {
                            if (selectionVersion != windSelectionVersion) {
                                stopWindLiveSubscription(subscriptionId);
                            } else {
                                windLiveSubscriptionId = subscriptionId;
                                shownWindSources.addAll(windSources);
                                loadWindHistory(windSources, new Date(), selectionVersion);
                            }
                        }

                        @Override
                        public void onFailure(final Throwable caught) {
                            if (selectionVersion == windSelectionVersion) {
                                setVisible(false);
                                errorReporter.reportError(caught.getMessage());
                            }
                        }
                    });
        }
    }

    private void loadWindHistory(final Set<WindSource> windSources, final Date to, final int selectionVersion) {
        final Date from = new MillisecondsTimePoint(to).minus(windHistoryDuration).asDate();
        final Set<WindSource> windSourcesPending = new HashSet<>(windSources);
        final WindInfoForRaceDTO combinedHistory = new WindInfoForRaceDTO();
        combinedHistory.windTrackInfoByWindSource = new HashMap<>();
        for (final WindSource windSource : windSources) {
            windHistoryLoader.load(
                    windSource,
                    from,
                    to,
                    new AsyncCallback<WindInfoForRaceDTO>() {
                        @Override
                        public void onSuccess(final WindInfoForRaceDTO result) {
                            if (selectionVersion == windSelectionVersion) {
                                if (result != null && result.windTrackInfoByWindSource != null) {
                                    combinedHistory.windTrackInfoByWindSource.putAll(result.windTrackInfoByWindSource);
                                }
                                onWindHistoryRequestCompleted(windSource, windSourcesPending, combinedHistory,
                                        selectionVersion);
                            }
                        }

                        @Override
                        public void onFailure(final Throwable caught) {
                            if (selectionVersion == windSelectionVersion) {
                                errorReporter.reportError(caught.getMessage());
                                onWindHistoryRequestCompleted(windSource, windSourcesPending, combinedHistory,
                                        selectionVersion);
                            }
                        }
                    });
        }
    }

    private void onWindHistoryRequestCompleted(
            final WindSource windSource,
            final Set<WindSource> windSourcesPending,
            final WindInfoForRaceDTO combinedHistory,
            final int selectionVersion) {
        windSourcesPending.remove(windSource);
        if (selectionVersion == windSelectionVersion
                && windSourcesPending.isEmpty()
                && windLiveSubscriptionId != null) {
            windChart.showData(combinedHistory);
            loadWindLiveUpdates();
            windLiveUpdateTimer.scheduleRepeating((int) windUpdateInterval.asMillis());
        }
    }

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
                                windChart.appendData(result);
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
        sailingServiceWrite.stopWindLiveSubscription(
                subscriptionId,
                new AsyncCallback<Void>() {
                    @Override
                    public void onSuccess(final Void result) {
                    }

                    @Override
                    public void onFailure(final Throwable caught) {
                    }
                });
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
