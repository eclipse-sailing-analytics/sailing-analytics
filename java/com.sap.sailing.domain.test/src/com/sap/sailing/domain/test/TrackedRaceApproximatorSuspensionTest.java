package com.sap.sailing.domain.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sap.sailing.domain.base.CompetitorWithBoat;
import com.sap.sailing.domain.base.DomainFactory;
import com.sap.sailing.domain.common.TrackedRaceStatusEnum;
import com.sap.sailing.domain.common.tracking.impl.GPSFixMovingImpl;
import com.sap.sailing.domain.shared.tracking.AddResult;
import com.sap.sailing.domain.tracking.impl.CourseChangeBasedTrackApproximation;
import com.sap.sailing.domain.tracking.impl.DynamicTrackedRaceImpl;
import com.sap.sailing.domain.tracking.impl.TrackedRaceImpl;
import com.sap.sailing.domain.tracking.impl.TrackedRaceStatusImpl;
import com.sap.sse.common.TimePoint;
import com.sap.sse.common.impl.DegreeBearingImpl;
import com.sap.sse.common.impl.DegreePosition;
import com.sap.sse.common.impl.KnotSpeedWithBearingImpl;

/** Re-entering bulk loading must retire approximators created during earlier use of the race. */
public class TrackedRaceApproximatorSuspensionTest extends AbstractSerializationTest {
    private final TimePoint start = TimePoint.of(/* millis */ 1000000);
    private CompetitorWithBoat competitor;
    private DynamicTrackedRaceImpl race;

    @BeforeEach
    public void setUp() {
        competitor = TrackBasedTest.createCompetitorWithBoat("Bulk loading");
        race = TrackBasedTest.createTestTrackedRace("Regatta", "Race", "505",
                TrackBasedTest.createCompetitorAndBoatsMap(competitor), start, /* useMarkPassingCalculator */ false);
        for (int i = 0; i < 6; i++) {
            addFix(i);
        }
        race.approximate(competitor, start, start.plus(/* milliseconds */ 600000));
    }

    @Test
    public void existingApproximatorDoesNotProcessFixesWhileLoading() throws Exception {
        final CourseChangeBasedTrackApproximation previous = getApproximator();
        final int fixesProcessed = previous.getNumberOfFixesAdded();
        assertTrue(fixesProcessed > 0);
        setStatus(TrackedRaceStatusEnum.LOADING);
        addFix(/* minute */ 6);
        assertEquals(fixesProcessed, previous.getNumberOfFixesAdded(),
                "The old approximator must be unregistered before bulk fix ingestion");
    }

    @Test
    public void approximationRequestsWhileLoadingDoNotRegisterAnotherListener() throws Exception {
        setStatus(TrackedRaceStatusEnum.LOADING);
        race.approximate(competitor, start, start.plus(/* milliseconds */ 600000));
        assertNull(getApproximator(), "Approximation must remain deferred throughout loading");
    }

    @Test
    public void callbackAlreadyCapturedByTrackCannotReviveRetiredApproximator() throws Exception {
        final CourseChangeBasedTrackApproximation previous = getApproximator();
        final int fixesProcessed = previous.getNumberOfFixesAdded();
        setStatus(TrackedRaceStatusEnum.LOADING);
        previous.gpsFixReceived(race.getTrack(competitor).getLastFixAtOrBefore(start.plus(/* milliseconds */ 600000)),
                competitor, /* firstFixInTrack */ false, AddResult.REPLACED);
        assertEquals(fixesProcessed, previous.getNumberOfFixesAdded());
    }

    @Test
    public void resumingRebuildsFromFixesReceivedWhileLoading() throws Exception {
        final CourseChangeBasedTrackApproximation previous = getApproximator();
        final int previouslyProcessed = previous.getNumberOfFixesAdded();
        setStatus(TrackedRaceStatusEnum.LOADING);
        addFix(/* minute */ 6);
        addFix(/* minute */ 7);
        setStatus(TrackedRaceStatusEnum.TRACKING);
        race.approximate(competitor, start, start.plus(/* milliseconds */ 600000));
        final CourseChangeBasedTrackApproximation rebuilt = getApproximator();
        assertNotSame(previous, rebuilt, "Resumption must lazily reconstruct approximation state");
        final int processedBeforeNextFix = rebuilt.getNumberOfFixesAdded();
        assertEquals(previouslyProcessed + 2, processedBeforeNextFix,
                "Reconstruction must include both fixes received while loading");
        addFix(/* minute */ 8);
        assertTrue(rebuilt.getNumberOfFixesAdded() > processedBeforeNextFix,
                "The rebuilt approximator must continue receiving new fixes");
        assertEquals(previouslyProcessed, previous.getNumberOfFixesAdded(),
                "The retired approximator must stay detached after resumption");
    }

    @Test
    public void approximationNormallyProcessesNewFixes() throws Exception {
        final CourseChangeBasedTrackApproximation approximation = getApproximator();
        final int fixesProcessed = approximation.getNumberOfFixesAdded();
        addFix(/* minute */ 6);
        assertTrue(approximation.getNumberOfFixesAdded() > fixesProcessed);
    }

    @Test
    public void importSuspensionOutlivesOrdinaryLoading() throws Exception {
        race.suspendAllCachesNotUpdatingWhileLoading();
        setStatus(TrackedRaceStatusEnum.LOADING);
        setStatus(TrackedRaceStatusEnum.TRACKING);
        addFix(/* minute */ 6);
        race.approximate(competitor, start, start.plus(/* milliseconds */ 600000));
        assertNull(getApproximator());
        race.resumeAllCachesNotUpdatingWhileLoading();
        race.approximate(competitor, start, start.plus(/* milliseconds */ 600000));
        assertTrue(getApproximator().getNumberOfFixesAdded() > 0);
    }

    @Test
    public void ordinaryLoadingOutlivesImportSuspension() throws Exception {
        setStatus(TrackedRaceStatusEnum.LOADING);
        race.suspendAllCachesNotUpdatingWhileLoading();
        race.resumeAllCachesNotUpdatingWhileLoading();
        race.approximate(competitor, start, start.plus(/* milliseconds */ 600000));
        assertNull(getApproximator());
        setStatus(TrackedRaceStatusEnum.TRACKING);
        race.approximate(competitor, start, start.plus(/* milliseconds */ 600000));
        assertTrue(getApproximator().getNumberOfFixesAdded() > 0);
    }

    private void addFix(final int minute) {
        race.recordFix(competitor, new GPSFixMovingImpl(
                new DegreePosition(/* latDeg */ 54.47 + minute * 0.0001, /* lngDeg */ 10.24),
                start.plus(minute * 60000L),
                new KnotSpeedWithBearingImpl(/* speedInKnots */ 1, new DegreeBearingImpl(/* bearingDeg */ 0)),
                /* optionalTrueHeading */ null));
    }

    @Test
    public void serializationDoesNotTransferImportSuspensionsToReplica() throws Exception {
        race.suspendAllCachesNotUpdatingWhileLoading();
        race.suspendAllCachesNotUpdatingWhileLoading();
        final DynamicTrackedRaceImpl copy = cloneBySerialization(race, DomainFactory.INSTANCE);
        race.resumeAllCachesNotUpdatingWhileLoading();
        race.resumeAllCachesNotUpdatingWhileLoading();
        race = copy;
        competitor = (CompetitorWithBoat) race.getRace().getCompetitors().iterator().next();
        race.initializeAfterDeserialization();
        race.approximate(competitor, start, start.plus(/* milliseconds */ 600000));
        assertTrue(getApproximator().getNumberOfFixesAdded() > 0);
    }

    @Test
    public void serializedLoadingResumesWithoutWaitingForSourceImportScope() throws Exception {
        setStatus(TrackedRaceStatusEnum.LOADING);
        race.suspendAllCachesNotUpdatingWhileLoading();
        final DynamicTrackedRaceImpl copy = cloneBySerialization(race, DomainFactory.INSTANCE);
        race.resumeAllCachesNotUpdatingWhileLoading();
        setStatus(TrackedRaceStatusEnum.TRACKING);
        race = copy;
        competitor = (CompetitorWithBoat) race.getRace().getCompetitors().iterator().next();
        race.initializeAfterDeserialization();
        race.approximate(competitor, start, start.plus(/* milliseconds */ 600000));
        assertNull(getApproximator());
        setStatus(TrackedRaceStatusEnum.TRACKING);
        race.approximate(competitor, start, start.plus(/* milliseconds */ 600000));
        assertTrue(getApproximator().getNumberOfFixesAdded() > 0);
    }

    private void setStatus(final TrackedRaceStatusEnum status) {
        race.setStatus(new TrackedRaceStatusImpl(status, /* progress */ 0));
    }

    private CourseChangeBasedTrackApproximation getApproximator() throws Exception {
        final Field field = TrackedRaceImpl.class.getDeclaredField("maneuverApproximators");
        field.setAccessible(true);
        return (CourseChangeBasedTrackApproximation) ((Map<?, ?>) field.get(race)).get(competitor);
    }
}
