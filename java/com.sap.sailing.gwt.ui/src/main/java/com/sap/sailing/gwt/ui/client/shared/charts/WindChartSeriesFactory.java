package com.sap.sailing.gwt.ui.client.shared.charts;

import org.moxieapps.gwt.highcharts.client.Chart;
import org.moxieapps.gwt.highcharts.client.PlotLine;
import org.moxieapps.gwt.highcharts.client.Series;
import org.moxieapps.gwt.highcharts.client.plotOptions.LinePlotOptions;

public final class WindChartSeriesFactory {
    private WindChartSeriesFactory() {
    }
    
    public static Series createDirectionSeries(final Chart chart, final String name) {
        return chart.createSeries().setName(name).setYAxis(0);
    }

    public static Series createDirectionSeries(final Chart chart, final String name, final String color,
            final int maxSeriesPoints) {
        return createDirectionSeries(chart, name)
                .setType(Series.Type.LINE)
                .setOption("turboThreshold", maxSeriesPoints)
                .setPlotOptions(new LinePlotOptions().setColor(color).setSelected(true));
    }
    
    public static Series createSpeedSeries(final Chart chart, final String name) {
        return chart.createSeries().setName(name).setYAxis(1);
    }

    public static Series createSpeedSeries(final Chart chart, final String name, final String color,
            final int maxSeriesPoints) {
        return createSpeedSeries(chart, name)
                .setType(Series.Type.LINE)
                .setOption("turboThreshold", maxSeriesPoints)
                .setPlotOptions(new LinePlotOptions()
                        .setDashStyle(PlotLine.DashStyle.SHORT_DOT)
                        .setLineWidth(3)
                        .setHoverStateLineWidth(3)
                        .setColor(color)
                        .setSelected(true));
    }
}