package com.sap.sailing.domain.tracking;

import com.sap.sailing.domain.abstractlog.race.CompetitorResult;
import com.sap.sailing.domain.abstractlog.race.RaceLog;
import com.sap.sailing.domain.abstractlog.race.RaceLogFinishPositioningConfirmedEvent;
import com.sap.sailing.domain.base.Competitor;
import com.sap.sailing.domain.base.CourseBase;
import com.sap.sailing.domain.base.Mark;
import com.sap.sailing.domain.common.TrackedRaceStatusEnum;
import com.sap.sailing.domain.common.Wind;
import com.sap.sailing.domain.common.WindSource;
import com.sap.sailing.domain.common.racelog.Flags;
import com.sap.sailing.domain.common.tracking.GPSFix;
import com.sap.sailing.domain.common.tracking.GPSFixMoving;
import com.sap.sailing.domain.common.tracking.SensorFix;
import com.sap.sailing.domain.tracking.impl.DynamicTrackedRaceLogListener;
import com.sap.sse.common.TimePoint;

public interface DynamicTrackedRace extends TrackedRace {
    /**
     * Suspends derived caches for bulk fix ingestion. Calls may be nested with ordinary race loading;
     * every successful call must be paired with {@link #resumeAllCachesNotUpdatingWhileLoading()}.
     */
    void suspendAllCachesNotUpdatingWhileLoading();

    void resumeAllCachesNotUpdatingWhileLoading();

    /**
     * Records a position and speed and course over ground fix for a competitor, but only if the fix's {@link GPSFixMoving#getTimePoint()}
     * is within this race's {@link #getStartOfTracking() start} and {@link #getEndOfTracking() end} of tracking time interval.
     * 
     * @return {@code true} if and only if the fix was actually added to the competitor's track
     */
    default boolean recordFix(Competitor competitor, GPSFixMoving fix) {
        return recordFix(competitor, fix, /* onlyWhenInTrackingTimeInterval */ true);
    }

    /**
     * Records a position and speed and course over ground fix for a competitor. If
     * {@code onlyWhenInTrackingTimeInterval} is {@code true}, the fix is recorded only if the fix's
     * {@link GPSFixMoving#getTimePoint()} is within this race's {@link #getStartOfTracking() start} and
     * {@link #getEndOfTracking() end} of tracking time interval. If {@code onlyWhenInTrackingTimeInterval} is
     * {@code false}, the fix is recorded regardless of this race's tracking times interval.
     * 
     * @return {@code true} if and only if the fix was actually added to the competitor's track
     */
    boolean recordFix(Competitor competitor, GPSFixMoving fix, boolean onlyWhenInTrackingTimeInterval);
    
    /**
     * Records a position fix for a mark, but only if the fix's {@link GPSFixMoving#getTimePoint()} is within this
     * race's {@link #getStartOfTracking() start} and {@link #getEndOfTracking() end} of tracking time interval.
     */
    default void recordFix(Mark mark, GPSFix fix) {
        recordFix(mark, fix, /* onlyWhenInTrackingTimeInterval */ true);
    }
    
    /**
     * Records a position fix for a mark. If {@code onlyWhenInTrackingTimeInterval} is {@code true}, the fix is recorded
     * only if the fix's {@link GPSFixMoving#getTimePoint()} is within this race's {@link #getStartOfTracking() start}
     * and {@link #getEndOfTracking() end} of tracking time interval. If {@code onlyWhenInTrackingTimeInterval} is
     * {@code false}, the fix is recorded regardless of this race's tracking times interval.
     */
    void recordFix(Mark mark, GPSFix fix, boolean onlyWhenInTrackingTimeInterval);

    /**
     * Inserts a <code>wind</code> fix into a {@link WindTrack} for the <code>windSource</code> if the current filtering
     * rules accept the wind fix. Filtering applies based upon timing considerations, assuming that wind fixes are not
     * relevant if they are outside of the tracking interval. There may be exceptions for races acting as default wind
     * acceptors in case no other race in the regatta would accept the wind fix.
     * 
     * @return True if the specified wind has been accepted and added to this race's wind track and database, else false.
     */
    default boolean recordWind(Wind wind, WindSource windSource) {
        return recordWind(wind, windSource, /* applyFilter */ true);
    }
    
    /**
     * The raw, updating feed of a single competitor participating in this race
     */
    DynamicGPSFixTrack<Competitor, GPSFixMoving> getTrack(Competitor competitor);
    
    /**
     * Yields the track describing <code>mark</code>'s movement over time; never <code>null</code> because a
     * new track will be created in case no track was present for <code>mark</code> so far.
     */
    DynamicGPSFixTrack<Mark, GPSFix> getOrCreateTrack(Mark mark);
    
    /**
     * Gets an existing {@link DynamicSensorFixTrack} or creates and returns a new one if there is none yet. If a
     * Competitor is given who is not part of this race, no track is created.
     * 
     * @see #getDynamicSensorTrack(Competitor, String)
     * 
     * @param competitor
     *            the competitor to get the track for
     * @param trackName
     *            the name of the track to get
     * @param newTrackFactory
     *            factory to create a new track instance if there isn't one yet for the competitor and the given track
     *            name.
     * @return the track for the competitor and track name of null if the given {@link Competitor} isn't part of this
     *         race
     */
    <FixT extends SensorFix, TrackT extends DynamicSensorFixTrack<Competitor, FixT>> TrackT getOrCreateSensorTrack(
            Competitor competitor, String trackName, TrackFactory<TrackT> newTrackFactory);
    
    /**
     * @see TrackedRace#getSensorTrack(Competitor, String)
     * 
     * @param competitor the competitor to get the track for
     * @param trackName the name of the track to get
     * @return the track associated to the given Competitor and name or <code>null</code> if there is none.
     */
    <FixT extends SensorFix, TrackT extends DynamicSensorFixTrack<Competitor, FixT>> TrackT getDynamicSensorTrack(
            Competitor competitor, String trackName);

    void recordSensorFix(Competitor competitor, String trackName, SensorFix fix, boolean onlyWhenInTrackingTimeInterval);

    /**
     * Updates all mark passings for <code>competitor</code> for this race. The mark passings must be provided in the
     * order of the race's course and in increasing time stamps. Calling this method replaces all previous mark passings
     * for this race for <code>competitor</code> and ensures that the "leaderboard" and all other derived information
     * are updated accordingly.
     * <p>
     * 
     * When an attached {@link RaceLog} has a {@link RaceLogFinishPositioningConfirmedEvent} that sets a
     * {@link CompetitorResult#getFinishingTime() finishing time} for a competitor, it will be used to override the
     * {@link MarkPassing#getTimePoint() time point} of the finishing waypoint's mark passing or, if no mark passing for
     * the finishing waypoint exists yet for that competitor, create one. This can, in particular, be helpful when
     * determining the time sailed for the {@code competitor} in order to determine the calculated time after applying
     * any handicap rules and metrics.<p>
     */
    void updateMarkPassings(Competitor competitor, Iterable<MarkPassing> markPassings);
    
    /**
     * When there is a significant change in the race logs attached to this race, such as adding another race log or
     * removing a race log or switching to another pass in one of the race logs attached, the effects on the
     * valid {@link RaceLogFinishPositioningConfirmedEvent} are analyzed, and if relevant, the mark passings
     * for the finish line that are affected will be {@link #updateMarkPassings(Competitor, Iterable) updated}.
     */
    void updateMarkPassingsAfterRaceLogChanges();

    /**
     * Sets the start time as received from the tracking infrastructure. This isn't necessarily
     * what {@link #getStart()} will deliver which assumes that the time announced here may be
     * significantly off.
     */
    void setStartTimeReceived(TimePoint start);

    /**
     * A new finishing time has been received by the {@link DynamicTrackedRaceLogListener} and is announced to this race
     * by calling this method  and the result of {@link #getFinishingTime()} will return
     * the {@code newFinishedTime} after this call returns.
     */
    void setFinishingTime(final TimePoint newFinishingTime);

    /**
     * A new finished time has been received by the {@link DynamicTrackedRaceLogListener} and is announced to this race
     * by calling this method. The {@link RaceChangeListener}s will be
     * {@link RaceChangeListener#finishedTimeChanged(TimePoint, TimePoint) notified} about this change, and the result
     * of {@link #getFinishedTime()} will return the {@code newFinishedTime} after this call returns.
     */
    void setFinishedTime(final TimePoint newFinishedTime);
    
    /** Sets the start of tracking as received from the tracking infrastructure.
     * This isn't necessarily what {@link #getStartOfTracking()} will deliver because we might consider other values to
     * calculate the start of tracking.
     */
    void setStartOfTrackingReceived(TimePoint startOfTrackingReceived);

    /** Sets the end of tracking as received from the tracking infrastructure.
     * This isn't necessarily what {@link #getEndOfTracking()} will deliver because we might consider other values to
     * calculate the end of tracking.
     */
    void setEndOfTrackingReceived(TimePoint endOfTrackingReceived);
    
    /**
     * A time point is considered "in" if it is (inclusively) between {@link #getStartOfTracking()} and {@link #getEndOfTracking()}.
     * A <code>null</code> value for one of the two interval demarcations means an open-ended interval.
     */
    boolean isWithinStartAndEndOfTracking(TimePoint timePoint);

    void setMillisecondsOverWhichToAverageSpeed(long millisecondsOverWhichToAverageSpeed);

    void setMillisecondsOverWhichToAverageWind(long millisecondsOverWhichToAverageWind);
    
    /**
     * Same as {@link #setDelayToLiveInMillis(long)}, except that afterwards, a {@link #setDelayToLiveInMillis(long)} will no longer
     * take effect.
     */
    void setAndFixDelayToLiveInMillis(long delayToLiveInMillis);
    
    /**
     * Updates the value returned by {@link #getDelayToLiveInMillis()}, except that {@link #setAndFixDelayToLiveInMillis(long)} was called
     * on this object before, in which case this call takes no effect.
     */
    void setDelayToLiveInMillis(long delayToLiveInMillis);
    
    DynamicTrackedRegatta getTrackedRegatta();

    /**
     * If and only if <code>raceIsKnownToStartUpwind</code> is <code>true</code>, this tracked race is allowed to use
     * the start leg's direction as a fallback for estimating the wind direction.
     */
    void setRaceIsKnownToStartUpwind(boolean raceIsKnownToStartUpwind);
    
    void setStatus(TrackedRaceStatus newStatus);
    
    /**
     * Updates the status of one {@link TrackingDataLoader}. This influences the overall status of the TrackedRace with the following rules:
     * <ul>
     * <li>The {@link TrackedRace} is initially in the state PREPARED</li>
     * <li>If the {@code status} of the {@code source} loader is set to {@link TrackedRaceStatusEnum#REMOVED}, the {@link TrackedRace}
     * will to to status {@link TrackedRaceStatusEnum#REMOVED}.</li>
     * <li>Otherwise, if there are no loaders left, the {@link TrackedRace}'s status goes to {@link TrackedRaceStatusEnum#FINISHED}.</li>
     * <li>If any loader's state is ERROR, this will also be the case for the {@link TrackedRace}</li>
     * <li>Otherwise: If any loader's state is LOADING, the {@link TrackedRace} will also be in loading state with the progress being the average progress of all loaders (including those not being in loading state)</li>
     * <li>Otherwise: If all loaders are in PREPARED state, this will also be the case for the {@link TrackedRace}</li>
     * <li>Otherwise: The {@link TrackedRace} is in TRACKING state</li>
     * </ul>
     * Loaders in status {@link TrackedRaceStatusEnum#FINISHED} or {@link TrackedRaceStatusEnum#REMOVED} will be removed.
     * 
     * @see TrackedRace#getStatus()
     */
    void onStatusChanged(TrackingDataLoader source, TrackedRaceStatus status);

    /**
     * whenever a new course design is published by the race committee and the appropriate event occurs in the race log,
     * this method is called to propagate the course design to the tracking provider.
     * 
     * @param courseDesign
     *            the new course design to be published
     */
    void onCourseDesignChangedByRaceCommittee(CourseBase courseDesign);
    
    void onStartTimeChangedByRaceCommittee(TimePoint newStartTime);
    
    void onAbortedByRaceCommittee(Flags flag);

    void invalidateStartTime();
    
    void invalidateEndTime();

    /**
     * Adds a {@link DynamicSensorFixTrack} for the given Competitor and track name.
     * @see #getDynamicSensorTrack(Competitor, String)
     */
    void addSensorTrack(Competitor trackedItem, String trackName, DynamicSensorFixTrack<Competitor, ?> track);
}
