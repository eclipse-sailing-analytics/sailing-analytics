package com.sap.sailing.gwt.ui.adminconsole;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.moxieapps.gwt.highcharts.client.Axis;
import org.moxieapps.gwt.highcharts.client.AxisTitle;
import org.moxieapps.gwt.highcharts.client.BaseChart;
import org.moxieapps.gwt.highcharts.client.Chart;
import org.moxieapps.gwt.highcharts.client.Credits;
import org.moxieapps.gwt.highcharts.client.PlotLine;
import org.moxieapps.gwt.highcharts.client.Point;
import org.moxieapps.gwt.highcharts.client.Series;
import org.moxieapps.gwt.highcharts.client.labels.AxisLabelsData;
import org.moxieapps.gwt.highcharts.client.labels.AxisLabelsFormatter;
import org.moxieapps.gwt.highcharts.client.labels.XAxisLabels;
import org.moxieapps.gwt.highcharts.client.labels.YAxisLabels;
import org.moxieapps.gwt.highcharts.client.plotOptions.LinePlotOptions;
import org.moxieapps.gwt.highcharts.client.plotOptions.Marker;

import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.RequiresResize;
import com.sap.sailing.domain.common.WindSource;
import com.sap.sailing.gwt.ui.client.shared.charts.ChartPointRecalculator;
import com.sap.sailing.gwt.ui.shared.WindInfoForRaceDTO;
import com.sap.sailing.gwt.ui.shared.WindTrackInfoDTO;

public class IgtimiDeviceWindChart extends Composite implements RequiresResize {
    private final Chart chart;
    private final Map<String, Series> directionSeriesByDevice = new HashMap<>();
    private final Map<String, Series> speedSeriesByDevice = new HashMap<>();

    public IgtimiDeviceWindChart() {
        chart = new Chart()
                .setInverted(true)
                .setZoomType(BaseChart.ZoomType.X)
                .setCredits(new Credits().setEnabled(false))
                .setLinePlotOptions(new LinePlotOptions()
                        .setMarker(new Marker().setEnabled(false)));
        chart.getXAxis()
                .setType(Axis.Type.DATE_TIME)
                .setLabels(new XAxisLabels().setFormatter((AxisLabelsData data) ->
                        DateTimeFormat.getFormat("MM-dd HH:mm").format(new Date(data.getValueAsLong()))));
        chart.getYAxis(0)
                .setStartOnTick(false)
                .setAxisTitle(new AxisTitle().setText("Dir (°)"))
                .setLabels(new YAxisLabels()
                        .setFormatter((AxisLabelsData data) -> {
                            final double v = data.getValueAsDouble() % 360;
                            final double normalized = v < 0 ? v + 360 : v;
                            return String.valueOf((long) normalized);
                        }));
        chart.getYAxis(1)
                .setOpposite(true)
                .setMin(0)
                .setStartOnTick(false)
                .setAxisTitle(new AxisTitle().setText("Speed (kt)"));
        initWidget(chart);
    }

    public void showData(final WindInfoForRaceDTO result, final String serialNumber) {
        removeDevice(serialNumber);
        if (result == null || result.windTrackInfoByWindSource == null) {
            return;
        }
        for (final Map.Entry<WindSource, WindTrackInfoDTO> entry : result.windTrackInfoByWindSource.entrySet()) {
            final WindSource source = entry.getKey();
            final Object sourceId = source.getId();
            if (sourceId == null || !serialNumber.equals(sourceId.toString())) {
                continue;
            }
            final WindTrackInfoDTO track = entry.getValue();
            if (track == null || track.windFixes == null || track.windFixes.isEmpty()) {
                continue;
            }
            final Point[] dirPoints = new Point[track.windFixes.size()];
            final Point[] spdPoints = new Point[track.windFixes.size()];
            Point previousDirPoint = null;
            for (int i = 0; i < track.windFixes.size(); i++) {
                final Long t = track.windFixes.get(i).requestTimepoint;
                if (t == null) {
                    continue;
                }
                Point dirPoint = new Point(t, track.windFixes.get(i).dampenedTrueWindFromDeg);
                if (previousDirPoint != null) {
                    dirPoint = ChartPointRecalculator.stayClosestToPreviousPoint(previousDirPoint, dirPoint);
                }
                previousDirPoint = dirPoint;
                dirPoints[i] = dirPoint;
                spdPoints[i] = new Point(t, track.windFixes.get(i).dampenedTrueWindSpeedInKnots);
            }
            final Series dirSeries = chart.createSeries()
                    .setName(serialNumber + " dir")
                    .setYAxis(0)
                    .setPoints(dirPoints);
            chart.addSeries(dirSeries, true, false);
            directionSeriesByDevice.put(serialNumber, dirSeries);
            if (source.getType().useSpeed()) {
                final Series spdSeries = chart.createSeries()
                        .setName(serialNumber + " spd")
                        .setYAxis(1)
                        .setPlotOptions(new LinePlotOptions().setDashStyle(PlotLine.DashStyle.DASH))
                        .setPoints(spdPoints);
                chart.addSeries(spdSeries, true, false);
                speedSeriesByDevice.put(serialNumber, spdSeries);
            }
        }
        chart.redraw();
    }

    public void removeDevice(final String serialNumber) {
        final Series dir = directionSeriesByDevice.remove(serialNumber);
        if (dir != null) {
            dir.remove();
        }
        final Series spd = speedSeriesByDevice.remove(serialNumber);
        if (spd != null) {
            spd.remove();
        }
        chart.redraw();
    }

    @Override
    public void onResize() {
        chart.setSizeToMatchContainer();
        chart.redraw();
    }
}
