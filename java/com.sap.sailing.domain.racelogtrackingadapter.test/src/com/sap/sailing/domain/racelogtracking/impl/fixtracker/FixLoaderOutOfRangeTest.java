package com.sap.sailing.domain.racelogtracking.impl.fixtracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sap.sailing.domain.abstractlog.AbstractLogEventAuthor;
import com.sap.sailing.domain.abstractlog.impl.LogEventAuthorImpl;
import com.sap.sailing.domain.abstractlog.regatta.RegattaLog;
import com.sap.sailing.domain.abstractlog.regatta.events.RegattaLogDeviceMarkMappingEvent;
import com.sap.sailing.domain.abstractlog.regatta.events.impl.RegattaLogDefineMarkEventImpl;
import com.sap.sailing.domain.abstractlog.regatta.events.impl.RegattaLogDeviceBoatMappingEventImpl;
import com.sap.sailing.domain.abstractlog.regatta.events.impl.RegattaLogDeviceMarkMappingEventImpl;
import com.sap.sailing.domain.abstractlog.regatta.events.impl.RegattaLogRevokeEventImpl;
import com.sap.sailing.domain.abstractlog.regatta.impl.RegattaLogImpl;
import com.sap.sailing.domain.base.Boat;
import com.sap.sailing.domain.base.BoatClass;
import com.sap.sailing.domain.base.Mark;
import com.sap.sailing.domain.base.RaceDefinition;
import com.sap.sailing.domain.base.impl.BoatClassImpl;
import com.sap.sailing.domain.base.impl.CourseImpl;
import com.sap.sailing.domain.base.impl.MarkImpl;
import com.sap.sailing.domain.base.impl.RaceDefinitionImpl;
import com.sap.sailing.domain.base.impl.RegattaImpl;
import com.sap.sailing.domain.base.impl.WaypointImpl;
import com.sap.sailing.domain.common.CompetitorRegistrationType;
import com.sap.sailing.domain.common.DeviceIdentifier;
import com.sap.sailing.domain.common.tracking.GPSFix;
import com.sap.sailing.domain.common.tracking.impl.GPSFixImpl;
import com.sap.sailing.domain.racelog.RaceLogAndTrackedRaceResolver;
import com.sap.sailing.domain.racelog.impl.EmptyRaceLogStore;
import com.sap.sailing.domain.racelog.tracking.FixReceivedListener;
import com.sap.sailing.domain.racelog.tracking.SensorFixStore;
import com.sap.sailing.domain.racelogsensortracking.SensorFixMapperFactory;
import com.sap.sailing.domain.racelogtracking.impl.SmartphoneImeiIdentifierImpl;
import com.sap.sailing.domain.ranking.OneDesignRankingMetric;
import com.sap.sailing.domain.regattalog.impl.EmptyRegattaLogStore;
import com.sap.sailing.domain.tracking.impl.DynamicTrackedRaceImpl;
import com.sap.sailing.domain.tracking.impl.DynamicTrackedRegattaImpl;
import com.sap.sailing.domain.tracking.impl.EmptyWindStore;
import com.sap.sse.common.TimePoint;
import com.sap.sse.common.Timed;
import com.sap.sse.common.Util;
import com.sap.sse.common.impl.DegreePosition;

/** Exercises the actual store listener and RegattaLog mapping lifecycle without a database. */
@Timeout(30)
public class FixLoaderOutOfRangeTest {
    private final DeviceIdentifier device = new SmartphoneImeiIdentifierImpl("Reused device");
    private final Mark mark = new MarkImpl("Mark");
    private final Mark secondMark = new MarkImpl("Second mark");
    private final AbstractLogEventAuthor author = new LogEventAuthorImpl("Test", /* priority */ 0);
    private final RegattaLog log = new RegattaLogImpl("Regatta log");
    private final SensorFixStore store = mock(SensorFixStore.class);
    private final Map<DeviceIdentifier, FixReceivedListener<Timed>> listeners = new ConcurrentHashMap<>();
    private DynamicTrackedRaceImpl race;
    private FixLoaderAndTracker loader;

    @BeforeEach
    public void setUp() throws Exception {
        doAnswer(invocation -> {
            listeners.put(invocation.getArgument(/* index */ 1), invocation.getArgument(/* index */ 0));
            return null;
        }).when(store).addListener(any(), any(DeviceIdentifier.class));
        final BoatClass boatClass = new BoatClassImpl("505", /* typicallyStartsUpwind */ true);
        final RegattaImpl regatta = new RegattaImpl(EmptyRaceLogStore.INSTANCE, EmptyRegattaLogStore.INSTANCE,
                "Historical regatta", boatClass, /* canBoatsOfCompetitorsChangePerRace */ true,
                CompetitorRegistrationType.CLOSED, /* startDate */ null, /* endDate */ null,
                /* trackedRegattaRegistry */ null, /* scoringScheme */ null, "ID", /* courseArea */ null,
                UUID.randomUUID().toString());
        final RaceDefinition definition = new RaceDefinitionImpl("Race", new CourseImpl("Course",
                Arrays.asList(new WaypointImpl(mark), new WaypointImpl(secondMark))), boatClass, Collections.emptyMap());
        regatta.addRace(definition);
        race = new DynamicTrackedRaceImpl(new DynamicTrackedRegattaImpl(regatta), definition, Collections.emptyList(),
                EmptyWindStore.INSTANCE, /* delayToLiveInMillis */ 0, /* millisecondsOverWhichToAverageWind */ 0,
                /* millisecondsOverWhichToAverageSpeed */ 0, /* useMarkPassingCalculator */ false,
                OneDesignRankingMetric::new, mock(RaceLogAndTrackedRaceResolver.class), /* trackingConnectorInfo */ null,
                /* markPassingRaceFingerprintRegistry */ null, /* maneuverRaceFingerprintRegistry */ null);
        race.setStartOfTrackingReceived(time(/* millis */ 100000));
        race.setEndOfTrackingReceived(time(/* millis */ 200000));
        loader = new FixLoaderAndTracker(race, store, mock(SensorFixMapperFactory.class),
                /* removeOutliersFromCompetitorTracks */ false);
        log.add(new RegattaLogDefineMarkEventImpl(time(/* millis */ 1), author, time(/* millis */ 1), /* passId */ 0, mark));
        log.add(new RegattaLogDefineMarkEventImpl(time(/* millis */ 2), author, time(/* millis */ 2), /* passId */ 0, secondMark));
        race.attachRegattaLog(log);
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        if (loader != null) {
            race.waitForLoadingToFinish();
            loader.stop(/* preemptive */ true, /* willBeRemoved */ false);
        }
    }

    @Test
    public void populatedHistoricalMarksSkipMappingLookupForWholeBatch() throws Exception {
        map(mark);
        race.getOrCreateTrack(mark).addGPSFix(fix(/* millis */ 150000, /* latitude */ 54));
        final int lookups = mappingLookupCount();
        listeners.get(device).fixesReceived(device, Collections.nCopies(/* n */ 5000, fix(/* millis */ 900000, /* latitude */ 54)),
                /* returnManeuverChanges */ false, /* returnLiveDelay */ false);
        assertEquals(lookups, mappingLookupCount());
        assertNull(race.getTrack(mark).getFirstRawFixAtOrAfter(time(/* millis */ 900000)));
    }

    @Test
    public void boatOnlyDevicesSkipMappingsAndAddingMarkToSameDeviceTakesEffect() throws Exception {
        log.add(new RegattaLogDeviceBoatMappingEventImpl(TimePoint.now(), author, mock(Boat.class), device,
                TimePoint.BeginningOfTime, TimePoint.EndOfTime));
        race.waitForLoadingToFinish();
        final int lookups = mappingLookupCount();
        receive(/* millis */ 50000, /* latitude */ 54);
        assertEquals(lookups, mappingLookupCount());
        assertTrue(mappings().getMarksForDevice(device).isEmpty());
        map(mark);
        assertEquals(Collections.singleton(mark), mappings().getMarksForDevice(device));
        receive(/* millis */ 50000, /* latitude */ 54);
        assertNotNull(race.getTrack(mark).getLastRawFixAtOrBefore(time(/* millis */ 50000)));
    }

    @Test
    public void pingImprovementAndPreviouslyAcceptedReplacementStillWork() throws Exception {
        map(mark);
        receive(/* millis */ 40000, /* latitude */ 54);
        receive(/* millis */ 50000, /* latitude */ 54);
        assertEquals(time(/* millis */ 50000), race.getTrack(mark).getLastRawFix().getTimePoint());
        receive(/* millis */ 150000, /* latitude */ 54);
        receive(/* millis */ 50000, /* latitude */ 53);
        assertEquals(/* expected */ 53, race.getTrack(mark).getLastRawFixAtOrBefore(time(/* millis */ 50000)).getPosition().getLatDeg(),
                /* delta */ 0.00001);
        assertNull(race.getTrack(mark).getFirstRawFixAtOrAfter(time(/* millis */ 250000)));
        receive(/* millis */ 250000, /* latitude */ 54);
        assertNull(race.getTrack(mark).getFirstRawFixAtOrAfter(time(/* millis */ 250000)));
    }

    @Test
    public void anotherWriterCanExtendBoundsAfterCacheInitialization() throws Exception {
        map(mark);
        receive(/* millis */ 150000, /* latitude */ 54);
        receive(/* millis */ 900000, /* latitude */ 54);
        race.getTrack(mark).addGPSFix(fix(/* millis */ 900000, /* latitude */ 54));
        receive(/* millis */ 900000, /* latitude */ 53);
        assertEquals(/* expected */ 53, race.getTrack(mark).getLastRawFix().getPosition().getLatDeg(), /* delta */ 0.00001);
    }

    @Test
    public void newMarkMappingAndRevocationRefreshSameDeviceClassification() throws Exception {
        final RegattaLogDeviceMarkMappingEvent firstMapping = map(mark);
        receive(/* millis */ 150000, /* latitude */ 54);
        receive(/* millis */ 900000, /* latitude */ 54);
        map(secondMark);
        receive(/* millis */ 900000, /* latitude */ 54);
        assertNotNull(race.getTrack(secondMark).getLastRawFix());
        log.add(new RegattaLogRevokeEventImpl(author, firstMapping, "Remapped"));
        assertEquals(Collections.singleton(secondMark), mappings().getMarksForDevice(device));
    }

    @Test
    public void liveDelayResponseIsPreservedForRejectedMarkFixes() throws Exception {
        map(mark);
        receive(/* millis */ 150000, /* latitude */ 54);
        assertFalse(Util.isEmpty(listeners.get(device).fixesReceived(device,
                Collections.singleton(fix(/* millis */ 900000, /* latitude */ 54)),
                /* returnManeuverChanges */ false, /* returnLiveDelay */ true)));
    }

    private RegattaLogDeviceMarkMappingEvent map(final Mark target) throws InterruptedException {
        final RegattaLogDeviceMarkMappingEvent event = new RegattaLogDeviceMarkMappingEventImpl(TimePoint.now(), author,
                target, device, TimePoint.BeginningOfTime, TimePoint.EndOfTime);
        log.add(event);
        race.waitForLoadingToFinish();
        return event;
    }

    private void receive(final long millis, final double latitude) {
        listeners.get(device).fixesReceived(device, Collections.singleton(fix(millis, latitude)),
                /* returnManeuverChanges */ false, /* returnLiveDelay */ false);
    }

    private GPSFix fix(final long millis, final double latitude) {
        return new GPSFixImpl(new DegreePosition(latitude, /* lngDeg */ 10), time(millis));
    }

    private TimePoint time(final long millis) {
        return TimePoint.of(millis);
    }

    private RegattaLogDeviceMappings<?> mappings() throws Exception {
        final Field field = FixLoaderAndTracker.class.getDeclaredField("deviceMappings");
        field.setAccessible(true);
        return (RegattaLogDeviceMappings<?>) field.get(loader);
    }

    private int mappingLookupCount() throws Exception {
        final Field hits = RegattaLogDeviceMappings.class.getDeclaredField("cacheHits");
        final Field misses = RegattaLogDeviceMappings.class.getDeclaredField("cacheMisses");
        hits.setAccessible(true);
        misses.setAccessible(true);
        return hits.getInt(mappings()) + misses.getInt(mappings());
    }
}
