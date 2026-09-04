package com.sap.sailing.gwt.ui.client.shared.charts;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.moxieapps.gwt.highcharts.client.Chart;
import org.moxieapps.gwt.highcharts.client.PlotLine;
import org.moxieapps.gwt.highcharts.client.Point;
import org.moxieapps.gwt.highcharts.client.Series;
import org.moxieapps.gwt.highcharts.client.plotOptions.LinePlotOptions;

import com.google.gwt.i18n.client.NumberFormat;
import com.sap.sailing.domain.common.WindSource;
import com.sap.sailing.domain.common.impl.ColorMapImpl;
import com.sap.sailing.gwt.ui.client.StringMessages;
import com.sap.sailing.gwt.ui.client.WindSourceTypeFormatter;
import com.sap.sailing.gwt.ui.shared.WindDTO;
import com.sap.sailing.gwt.ui.shared.WindInfoForRaceDTO;
import com.sap.sailing.gwt.ui.shared.WindTrackInfoDTO;

/**
 * Owns the connectivity-independent series and point state shared by wind charts and updates it from
 * {@link WindInfoForRaceDTO} data.
 */
public class WindChartDataRenderer {
    public interface SeriesPointsUpdater {
        void setSeriesPoints(Series series, Point[] points);
    }

    public interface PointsAddedHandler {
        void onPointsAdded(WindSource windSource, boolean append, Point[] directionPoints, Point[] speedPoints);
    }

    private final Chart chart;
    private final StringMessages stringMessages;
    private final Integer maxSeriesPoints;
    private final boolean addSeriesToChartOnCreation;
    private final SeriesPointsUpdater seriesPointsUpdater;
    private final PointsAddedHandler pointsAddedHandler;
    private final Map<WindSource, Series> windSourceDirectionSeries = new HashMap<WindSource, Series>();
    private final Map<WindSource, Series> windSourceSpeedSeries = new HashMap<WindSource, Series>();
    private final Map<WindSource, Point[]> windSourceDirectionPoints = new HashMap<WindSource, Point[]>();
    private final Map<WindSource, Point[]> windSourceSpeedPoints = new HashMap<WindSource, Point[]>();
    private final ColorMapImpl<WindSource> colorMap = new ColorMapImpl<WindSource>();
    private Point firstPointOfFirstSeries;
    private Long timeOfEarliestRequestInMillis;
    private Long timeOfLatestRequestInMillis;

    public WindChartDataRenderer(final Chart chart, final StringMessages stringMessages,
            final boolean addSeriesToChartOnCreation, final SeriesPointsUpdater seriesPointsUpdater) {
        this(chart, stringMessages, null, addSeriesToChartOnCreation, seriesPointsUpdater, null);
    }

    public WindChartDataRenderer(final Chart chart, final StringMessages stringMessages, final int maxSeriesPoints,
            final boolean addSeriesToChartOnCreation, final SeriesPointsUpdater seriesPointsUpdater,
            final PointsAddedHandler pointsAddedHandler) {
        this(chart, stringMessages, Integer.valueOf(maxSeriesPoints), addSeriesToChartOnCreation, seriesPointsUpdater,
                pointsAddedHandler);
    }

    private WindChartDataRenderer(final Chart chart, final StringMessages stringMessages, final Integer maxSeriesPoints,
            final boolean addSeriesToChartOnCreation, final SeriesPointsUpdater seriesPointsUpdater,
            final PointsAddedHandler pointsAddedHandler) {
        this.chart = chart;
        this.stringMessages = stringMessages;
        this.maxSeriesPoints = maxSeriesPoints;
        this.addSeriesToChartOnCreation = addSeriesToChartOnCreation;
        this.seriesPointsUpdater = seriesPointsUpdater;
        this.pointsAddedHandler = pointsAddedHandler;
    }

    /**
     * Updates all wind sources contained in {@code result}. Existing points are retained if {@code append} is
     * {@code true}.
     */
    public void updateChartSeries(final WindInfoForRaceDTO result, final boolean append) {
        if (result == null || result.windTrackInfoByWindSource == null) {
            return;
        }
        Long newMinTimepoint = timeOfEarliestRequestInMillis;
        Long newMaxTimepoint = timeOfLatestRequestInMillis;
        for (final WindSource windSource : result.windTrackInfoByWindSource.keySet()) {
            final WindTrackInfoDTO windTrackInfo = result.windTrackInfoByWindSource.get(windSource);
            if (windTrackInfo == null || windTrackInfo.windFixes == null) {
                continue;
            }
            final Series directionSeries = getOrCreateDirectionSeries(windSource);
            final boolean useSpeed = windSource.getType().useSpeed();
            final Series speedSeries = useSpeed ? getOrCreateSpeedSeries(windSource) : null;
            final Point[] oldDirectionPoints = windSourceDirectionPoints.get(windSource);
            final Point[] oldSpeedPoints = windSourceSpeedPoints.get(windSource);
            Point previousDirectionPoint = null;
            if (append && oldDirectionPoints != null && oldDirectionPoints.length != 0) {
                previousDirectionPoint = oldDirectionPoints[oldDirectionPoints.length - 1];
            }
            final Point[] directionPoints = new Point[windTrackInfo.windFixes.size()];
            final Point[] speedPoints = useSpeed ? new Point[windTrackInfo.windFixes.size()] : null;
            final NumberFormat numberFormat = NumberFormat.getFormat("0");
            int currentPointIndex = 0;
            Long earliestRequestTimepoint = null;
            Long latestRequestTimepoint = null;
            for (final WindDTO wind : windTrackInfo.windFixes) {
                if (wind.requestTimepoint == null) {
                    continue;
                }
                if (earliestRequestTimepoint == null || wind.requestTimepoint < earliestRequestTimepoint) {
                    earliestRequestTimepoint = wind.requestTimepoint;
                }
                if (latestRequestTimepoint == null || wind.requestTimepoint > latestRequestTimepoint) {
                    latestRequestTimepoint = wind.requestTimepoint;
                }
                // if we are in non appending mode, the data is the truth, use all of it without filtering
                if (!append || ((timeOfEarliestRequestInMillis == null
                        || wind.requestTimepoint < timeOfEarliestRequestInMillis)
                        || timeOfLatestRequestInMillis == null || wind.requestTimepoint > timeOfLatestRequestInMillis)) {
                    Point newDirectionPoint = new Point(wind.requestTimepoint, wind.dampenedTrueWindFromDeg);
                    if (wind.dampenedTrueWindSpeedInKnots != null) {
                        final String name = numberFormat.format(wind.dampenedTrueWindSpeedInKnots)
                                + stringMessages.knotsUnit();
                        newDirectionPoint.setName(name);
                    }
                    if (previousDirectionPoint != null) {
                        newDirectionPoint = ChartPointRecalculator.stayClosestToPreviousPoint(previousDirectionPoint,
                                newDirectionPoint);
                    } else if (firstPointOfFirstSeries != null && oldDirectionPoints == null) {
                        // This point is the first point of a new series
                        newDirectionPoint = ChartPointRecalculator.stayClosestToPreviousPoint(firstPointOfFirstSeries,
                                newDirectionPoint);
                    }
                    directionPoints[currentPointIndex] = newDirectionPoint;
                    previousDirectionPoint = newDirectionPoint;
                    if (useSpeed) {
                        speedPoints[currentPointIndex] = new Point(wind.requestTimepoint,
                                wind.dampenedTrueWindSpeedInKnots);
                    }
                    currentPointIndex++;
                }
            }
            final Point[] addedDirectionPoints = copy(directionPoints, currentPointIndex);
            final Point[] addedSpeedPoints = useSpeed ? copy(speedPoints, currentPointIndex) : null;
            final Point[] newDirectionPoints = append ? concatenate(oldDirectionPoints, addedDirectionPoints)
                    : addedDirectionPoints;
            final Point[] newSpeedPoints = useSpeed
                    ? (append ? concatenate(oldSpeedPoints, addedSpeedPoints) : addedSpeedPoints)
                    : null;
            if (earliestRequestTimepoint != null
                    && (newMinTimepoint == null || earliestRequestTimepoint < newMinTimepoint)) {
                newMinTimepoint = earliestRequestTimepoint;
            }
            if (latestRequestTimepoint != null && (newMaxTimepoint == null || latestRequestTimepoint > newMaxTimepoint)) {
                newMaxTimepoint = latestRequestTimepoint;
            }
            if (pointsAddedHandler != null) {
                pointsAddedHandler.onPointsAdded(windSource, append, addedDirectionPoints, addedSpeedPoints);
            }
            seriesPointsUpdater.setSeriesPoints(directionSeries, newDirectionPoints);
            windSourceDirectionPoints.put(windSource, newDirectionPoints);
            if (useSpeed) {
                seriesPointsUpdater.setSeriesPoints(speedSeries, newSpeedPoints);
                windSourceSpeedPoints.put(windSource, newSpeedPoints);
            }
            if (firstPointOfFirstSeries == null && newDirectionPoints.length != 0) {
                firstPointOfFirstSeries = newDirectionPoints[0];
            }
        }
        timeOfEarliestRequestInMillis = newMinTimepoint;
        timeOfLatestRequestInMillis = newMaxTimepoint;
    }

    public Map<WindSource, Series> getWindSourceDirectionSeries() {
        return windSourceDirectionSeries;
    }

    public Map<WindSource, Series> getWindSourceSpeedSeries() {
        return windSourceSpeedSeries;
    }

    public Map<WindSource, Point[]> getWindSourceDirectionPoints() {
        return windSourceDirectionPoints;
    }

    public Map<WindSource, Point[]> getWindSourceSpeedPoints() {
        return windSourceSpeedPoints;
    }

    public ColorMapImpl<WindSource> getColorMap() {
        return colorMap;
    }

    public Long getTimeOfEarliestRequestInMillis() {
        return timeOfEarliestRequestInMillis;
    }

    public Long getTimeOfLatestRequestInMillis() {
        return timeOfLatestRequestInMillis;
    }

    public Set<WindSource> getWindSources() {
        final Set<WindSource> result = new HashSet<WindSource>();
        result.addAll(windSourceDirectionSeries.keySet());
        result.addAll(windSourceSpeedSeries.keySet());
        return result;
    }

    /** Clears cached point and time-range state while keeping the already-created series. */
    public void clearPointState() {
        timeOfEarliestRequestInMillis = null;
        timeOfLatestRequestInMillis = null;
        windSourceDirectionPoints.clear();
        windSourceSpeedPoints.clear();
        firstPointOfFirstSeries = null;
    }

    /** Removes all chart state for one wind source. */
    public void removeWindSource(final WindSource windSource) {
        final Series directionSeries = windSourceDirectionSeries.remove(windSource);
        if (directionSeries != null) {
            directionSeries.remove();
        }
        final Series speedSeries = windSourceSpeedSeries.remove(windSource);
        if (speedSeries != null) {
            speedSeries.remove();
        }
        windSourceDirectionPoints.remove(windSource);
        windSourceSpeedPoints.remove(windSource);
        if (windSourceDirectionSeries.isEmpty() && windSourceSpeedSeries.isEmpty()) {
            clearPointState();
        }
    }

    private Series getOrCreateDirectionSeries(final WindSource windSource) {
        Series result = windSourceDirectionSeries.get(windSource);
        if (result == null) {
            result = createDirectionSeries(windSource);
            windSourceDirectionSeries.put(windSource, result);
            if (addSeriesToChartOnCreation) {
                chart.addSeries(result, false, false);
            }
        }
        return result;
    }

    private Series getOrCreateSpeedSeries(final WindSource windSource) {
        Series result = windSourceSpeedSeries.get(windSource);
        if (result == null) {
            result = createSpeedSeries(windSource);
            windSourceSpeedSeries.put(windSource, result);
            if (addSeriesToChartOnCreation) {
                chart.addSeries(result, false, false);
            }
        }
        return result;
    }

    private Series createDirectionSeries(final WindSource windSource) {
        final String name = stringMessages.fromDeg() + " " + WindSourceTypeFormatter.format(windSource, stringMessages);
        final String color = colorMap.getColorByID(windSource).getAsHtml();
        if (maxSeriesPoints == null) {
            return WindChartSeriesFactory.createDirectionSeries(chart, name)
                    .setType(Series.Type.LINE)
                    .setPlotOptions(new LinePlotOptions().setColor(color).setSelected(true));
        }
        return WindChartSeriesFactory.createDirectionSeries(chart, name, color, maxSeriesPoints);
    }

    private Series createSpeedSeries(final WindSource windSource) {
        final String name = stringMessages.windSpeed() + " " + WindSourceTypeFormatter.format(windSource, stringMessages);
        final String color = colorMap.getColorByID(windSource).getAsHtml();
        if (maxSeriesPoints == null) {
            return WindChartSeriesFactory.createSpeedSeries(chart, name)
                    .setType(Series.Type.LINE)
                    .setPlotOptions(new LinePlotOptions()
                            .setDashStyle(PlotLine.DashStyle.SHORT_DOT)
                            .setLineWidth(3)
                            .setHoverStateLineWidth(3)
                            .setColor(color)
                            .setSelected(true));
        }
        return WindChartSeriesFactory.createSpeedSeries(chart, name, color, maxSeriesPoints);
    }

    private static Point[] copy(final Point[] points, final int length) {
        final Point[] result = new Point[length];
        System.arraycopy(points, 0, result, 0, length);
        return result;
    }

    private static Point[] concatenate(final Point[] oldPoints, final Point[] addedPoints) {
        final Point[] safeOldPoints = oldPoints == null ? new Point[0] : oldPoints;
        final Point[] result = new Point[safeOldPoints.length + addedPoints.length];
        System.arraycopy(safeOldPoints, 0, result, 0, safeOldPoints.length);
        System.arraycopy(addedPoints, 0, result, safeOldPoints.length, addedPoints.length);
        return result;
    }
}
