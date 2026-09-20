package com.sap.sailing.domain.common;

public interface WindImportConstants {
    String WIND_IMPORT_PARAMETER_RACES = "races";
    String EXPEDITON_IMPORT_PARAMETER_BOAT_ID = "boatId";
    String WIND_IMPORT_PARAMETER_RACE_NAME = "race";
    String WIND_IMPORT_PARAMETER_REGATTA_NAME = "regatta";
    /** Maximum number of wind fixes buffered per delivery in a live wind subscription. */
    int WIND_LIVE_MAX_FIXES_PER_DELIVERY = 10000;
}
