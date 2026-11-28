package com.sap.sailing.domain.racelogtracking.impl.fixtracker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sap.sailing.domain.base.Mark;
import com.sap.sailing.domain.base.impl.MarkImpl;
import com.sap.sailing.domain.common.DeviceIdentifier;
import com.sap.sailing.domain.common.tracking.GPSFix;
import com.sap.sailing.domain.common.tracking.impl.GPSFixImpl;
import com.sap.sailing.domain.racelogtracking.impl.SmartphoneImeiIdentifierImpl;
import com.sap.sailing.domain.tracking.DynamicTrackedRace;
import com.sap.sailing.domain.tracking.impl.DynamicGPSFixTrackImpl;
import com.sap.sse.common.TimePoint;
import com.sap.sse.common.impl.DegreePosition;

public class OutOfRangeFixFilterTest {
    private final DynamicTrackedRace race = mock(DynamicTrackedRace.class);
    private final RegattaLogDeviceMappings<?> mappings = mock(RegattaLogDeviceMappings.class);
    private final DeviceIdentifier device = new SmartphoneImeiIdentifierImpl("Historical device");
    private final Mark mark = new MarkImpl("Mark");
    private final DynamicGPSFixTrackImpl<Mark> track = spy(new DynamicGPSFixTrackImpl<>(mark,
            /* millisecondsOverWhichToAverage */ 10000));
    private final OutOfRangeFixFilter filter = new OutOfRangeFixFilter(race);

    @BeforeEach
    public void setUp() {
        when(race.getStartOfTracking()).thenReturn(time(/* millis */ 100000));
        when(race.getEndOfTracking()).thenReturn(time(/* millis */ 200000));
        when(race.getTrack(mark)).thenReturn(track);
        when(mappings.getMarksForDevice(device)).thenReturn(Collections.singleton(mark));
    }

    @Test
    public void boatOnlyDevicesRejectBeforeAndAfterRace() {
        when(mappings.getMarksForDevice(device)).thenReturn(Collections.emptySet());
        assertTrue(rejects(/* millis */ 99999));
        assertTrue(rejects(/* millis */ 200001));
        verify(race, never()).getTrack(mark);
    }

    @Test
    public void trackingBoundsAreInclusiveAndOpenEndedRacesStayOpen() {
        assertFalse(rejects(/* millis */ 100000));
        assertFalse(rejects(/* millis */ 200000));
        verify(mappings, never()).getMarksForDevice(device);
        when(race.getEndOfTracking()).thenReturn(/* value */ null);
        assertFalse(rejects(/* millis */ 900000));
    }

    @Test
    public void absentStartIsConservative() {
        when(race.getStartOfTracking()).thenReturn(/* value */ null);
        assertFalse(rejects(/* millis */ 900000));
    }

    @Test
    public void prepopulatedTrackRejectsWithoutLockingOnCacheHits() {
        track.addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        assertTrue(rejects(/* millis */ 900000));
        clearInvocations((Object) track);
        for (int i = 0; i < 5000; i++) {
            assertTrue(rejects(900001 + i));
        }
        verify(track, never()).lockForRead();
    }

    @Test
    public void extendedRawBoundsPreservePreviouslyAcceptedReplacements() {
        track.addGPSFix(fix(/* millis */ 50000, /* latitude */ 54));
        track.addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        track.addGPSFix(fix(/* millis */ 250000, /* latitude */ 54));
        assertFalse(rejects(/* millis */ 50000));
        assertFalse(rejects(/* millis */ 250000));
        assertFalse(rejects(/* millis */ 75000));
        assertTrue(rejects(/* millis */ 49999));
        assertTrue(rejects(/* millis */ 250001));
    }

    @Test
    public void pingOnlyAndEmptyTracksUseOrdinaryBoundarySelection() {
        assertFalse(rejects(/* millis */ 50000));
        track.addGPSFix(fix(/* millis */ 40000, /* latitude */ 54));
        assertFalse(rejects(/* millis */ 50000));
        assertFalse(rejects(/* millis */ 250000));
    }

    @Test
    public void newTracksCreatedByOtherWritersBecomeVisible() {
        when(race.getTrack(mark)).thenReturn(/* value */ null);
        assertFalse(rejects(/* millis */ 900000));
        track.addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        when(race.getTrack(mark)).thenReturn(track);
        assertTrue(rejects(/* millis */ 900000));
    }

    @Test
    public void directWritesExtendCachedBoundsWithoutLoaderNotifications() {
        track.addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        assertTrue(rejects(/* millis */ 900000));
        track.addGPSFix(fix(/* millis */ 900000, /* latitude */ 54));
        assertFalse(rejects(/* millis */ 900000));
        assertTrue(rejects(/* millis */ 900001));
    }

    @Test
    public void inRangeOutliersDoNotCountAsUsableData() {
        track.addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        track.addGPSFix(fix(/* millis */ 150001, /* latitude */ -54));
        assertNull(track.getFirstFixAtOrAfter(time(/* millis */ 100000)));
        assertFalse(rejects(/* millis */ 900000));
    }

    @Test
    public void replacementsInvalidateCachedUsableData() {
        track.addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        track.addGPSFix(fix(/* millis */ 150001, /* latitude */ 54));
        assertTrue(rejects(/* millis */ 900000));
        track.addGPSFix(fix(/* millis */ 150001, /* latitude */ -54));
        assertFalse(rejects(/* millis */ 900000));
    }

    @Test
    public void validityResumptionInvalidatesSummaryBuiltWhileLoading() {
        track.addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        assertTrue(rejects(/* millis */ 900000));
        track.suspendValidityAndMaxSpeedCaching();
        track.addGPSFix(fix(/* millis */ 150001, /* latitude */ -54));
        // The existing fix still has its pre-loading validity cached until resumption.
        assertTrue(rejects(/* millis */ 900000));
        track.resumeValidityAndMaxSpeedCaching();
        assertFalse(rejects(/* millis */ 900000));
    }

    @Test
    public void trackingBoundChangesRecheckUsableData() {
        track.addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        assertTrue(rejects(/* millis */ 900000));
        when(race.getStartOfTracking()).thenReturn(time(/* millis */ 160000));
        assertFalse(rejects(/* millis */ 900000));
        when(race.getStartOfTracking()).thenReturn(time(/* millis */ 100000));
        assertTrue(rejects(/* millis */ 900000));
        when(race.getEndOfTracking()).thenReturn(time(/* millis */ 140000));
        assertFalse(rejects(/* millis */ 900000));
    }

    @Test
    public void everyPotentialMarkMustHaveUsableData() {
        track.addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        assertTrue(rejects(/* millis */ 900000));
        final Mark otherMark = new MarkImpl("Other mark");
        when(mappings.getMarksForDevice(device)).thenReturn(new HashSet<>(Arrays.asList(mark, otherMark)));
        assertFalse(rejects(/* millis */ 900000));
        final DynamicGPSFixTrackImpl<Mark> otherTrack = new DynamicGPSFixTrackImpl<>(otherMark,
                /* millisecondsOverWhichToAverage */ 10000);
        when(race.getTrack(otherMark)).thenReturn(otherTrack);
        otherTrack.addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        assertTrue(rejects(/* millis */ 900000));
    }

    private boolean rejects(final long millis) {
        return filter.canReject(device, time(millis), mappings);
    }

    private GPSFix fix(final long millis, final double latitude) {
        return new GPSFixImpl(new DegreePosition(latitude, /* lngDeg */ 10), time(millis));
    }

    private TimePoint time(final long millis) {
        return TimePoint.of(millis);
    }
}
