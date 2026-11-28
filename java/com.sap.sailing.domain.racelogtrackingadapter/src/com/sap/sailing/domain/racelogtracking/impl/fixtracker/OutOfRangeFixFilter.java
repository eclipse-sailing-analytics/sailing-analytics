package com.sap.sailing.domain.racelogtracking.impl.fixtracker;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.sap.sailing.domain.base.Mark;
import com.sap.sailing.domain.common.DeviceIdentifier;
import com.sap.sailing.domain.common.tracking.GPSFix;
import com.sap.sailing.domain.tracking.DynamicTrackedRace;
import com.sap.sailing.domain.tracking.GPSFixTrack;
import com.sap.sse.common.TimePoint;

/**
 * Rejects irrelevant historical-race fixes before mapping lookup and track locking. A mark can still need an
 * out-of-range ping, or replacement of a previously accepted fix. Therefore rejection requires usable in-range
 * data in every potentially mapped mark track and a timestamp outside every track's extended raw bounds.
 */
final class OutOfRangeFixFilter {
    private final DynamicTrackedRace race;
    private final Map<Mark, MarkTrackSummary> summaries = new ConcurrentHashMap<>();

    OutOfRangeFixFilter(final DynamicTrackedRace race) {
        this.race = race;
    }

    boolean canReject(final DeviceIdentifier device, final TimePoint timePoint,
            final RegattaLogDeviceMappings<?> mappings) {
        final TimePoint start = race.getStartOfTracking();
        final TimePoint end = race.getEndOfTracking();
        boolean result = start != null && (timePoint.before(start) || end != null && timePoint.after(end));
        if (result) {
            for (final Mark mark : mappings.getMarksForDevice(device)) {
                // Missing tracks are not cached: creation by any connector becomes visible on the next lookup.
                final GPSFixTrack<Mark, GPSFix> track = race.getTrack(mark);
                if (track == null) {
                    result = false;
                } else {
                    MarkTrackSummary summary = summaries.get(mark);
                    if (summary == null || !summary.isCurrent(track, start, end)) {
                        summary = new MarkTrackSummary(track, start, end);
                        summaries.put(mark, summary);
                    }
                    // A concurrent write or validity-cache resumption conservatively takes the ordinary path.
                    result = summary.isCurrent(track, start, end) && summary.canReject(timePoint);
                }
                if (!result) {
                    break;
                }
            }
        }
        return result;
    }

    private static final class MarkTrackSummary {
        private final GPSFixTrack<Mark, GPSFix> track;
        private final long version;
        private final long speedAveragingMillis;
        private final TimePoint start;
        private final TimePoint end;
        private final boolean hasInRangeFix;
        private final TimePoint firstRawFix;
        private final TimePoint lastRawFix;

        private MarkTrackSummary(final GPSFixTrack<Mark, GPSFix> track, final TimePoint start, final TimePoint end) {
            this.track = track;
            this.start = start;
            this.end = end;
            track.lockForRead();
            try {
                version = track.getFixesAndValidityVersion();
                speedAveragingMillis = track.getMillisecondsOverWhichToAverageSpeed();
                final GPSFix firstInRange = track.getFirstFixAtOrAfter(start);
                hasInRangeFix = firstInRange != null && (end == null || !firstInRange.getTimePoint().after(end));
                final GPSFix first = track.getFirstRawFixAtOrAfter(TimePoint.BeginningOfTime);
                final GPSFix last = track.getLastRawFix();
                firstRawFix = first == null ? null : first.getTimePoint();
                lastRawFix = last == null ? null : last.getTimePoint();
            } finally {
                track.unlockAfterRead();
            }
        }

        private boolean isCurrent(final GPSFixTrack<Mark, GPSFix> currentTrack, final TimePoint currentStart,
                final TimePoint currentEnd) {
            return track == currentTrack && version == track.getFixesAndValidityVersion()
                    && speedAveragingMillis == track.getMillisecondsOverWhichToAverageSpeed()
                    && Objects.equals(start, currentStart) && Objects.equals(end, currentEnd);
        }

        private boolean canReject(final TimePoint timePoint) {
            return hasInRangeFix && firstRawFix != null && lastRawFix != null
                    && (timePoint.before(firstRawFix) || timePoint.after(lastRawFix));
        }
    }
}
