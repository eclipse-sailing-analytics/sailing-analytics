package com.sap.sailing.gwt.ui.adminconsole;

import java.util.Date;

import org.moxieapps.gwt.highcharts.client.Axis;
import org.moxieapps.gwt.highcharts.client.AxisTitle;
import org.moxieapps.gwt.highcharts.client.BaseChart;
import org.moxieapps.gwt.highcharts.client.Chart;
import org.moxieapps.gwt.highcharts.client.ChartTitle;
import org.moxieapps.gwt.highcharts.client.Credits;
import org.moxieapps.gwt.highcharts.client.Point;
import org.moxieapps.gwt.highcharts.client.Series;
import org.moxieapps.gwt.highcharts.client.labels.AxisLabelsData;
import org.moxieapps.gwt.highcharts.client.labels.XAxisLabels;
import org.moxieapps.gwt.highcharts.client.labels.YAxisLabels;
import org.moxieapps.gwt.highcharts.client.plotOptions.LinePlotOptions;
import org.moxieapps.gwt.highcharts.client.plotOptions.Marker;

import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.RequiresResize;
import com.sap.sailing.domain.common.WindSource;
import com.sap.sailing.gwt.ui.client.StringMessages;
import com.sap.sailing.gwt.ui.client.shared.charts.ChartUtil;
import com.sap.sailing.gwt.ui.client.shared.charts.WindChartDataRenderer;
import com.sap.sailing.gwt.ui.shared.WindInfoForRaceDTO;

public class WindLiveChart extends Composite implements RequiresResize {
    private final Chart chart;
    private final WindChartDataRenderer windChartDataRenderer;

    public WindLiveChart(final StringMessages stringMessages) {
        chart = new Chart()
                .setWidth100()
                .setHeight100()
                //.setInverted(true)
                .setZoomType(BaseChart.ZoomType.X)
                .setChartTitle(new ChartTitle().setText(""))
                .setCredits(new Credits().setEnabled(false))
                .setLinePlotOptions(new LinePlotOptions()
                        .setMarker(new Marker().setEnabled(false)));
        chart.getXAxis()
                .setType(Axis.Type.DATE_TIME)
                .setLabels(new XAxisLabels().setFormatter((AxisLabelsData data) ->
                        DateTimeFormat.getFormat("MM-dd HH:mm").format(new Date(data.getValueAsLong()))));
        chart.getYAxis(0)
                .setStartOnTick(false)
                .setAxisTitle(new AxisTitle().setText(stringMessages.fromDeg()))
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
                .setAxisTitle(new AxisTitle().setText(stringMessages.speed()+" ("+stringMessages.knotsUnit()+")"));
        ChartUtil.useCheckboxesToShowAndHide(chart, () -> {});
        windChartDataRenderer = new WindChartDataRenderer(
                chart,
                stringMessages,
                /* addSeriesToChartOnCreation */ true,
                new WindChartDataRenderer.SeriesPointsUpdater() {
                    @Override
                    public void setSeriesPoints(final Series series, final Point[] points) {
                        series.setPoints(points, /* redraw */ false);
                    }
                });
        initWidget(chart);
        ensureDebugId("WindLiveChart");
    }

    public void showData(final WindInfoForRaceDTO result) {
        windChartDataRenderer.updateChartSeries(result, /* append */ false);
        chart.redraw();
    }

    public void appendData(final WindInfoForRaceDTO result) {
        windChartDataRenderer.updateChartSeries(result, /* append */ true);
        chart.redraw();
    }

    public void removeWindSource(final WindSource windSource) {
        windChartDataRenderer.removeWindSource(windSource);
        chart.redraw();
    }

    @Override
    public void onResize() {
        chart.setSizeToMatchContainer();
        chart.redraw();
    }
}
