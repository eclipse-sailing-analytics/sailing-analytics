package com.sap.sailing.server.masterdata;

import java.util.HashSet;
import java.util.Set;

import com.sap.sailing.domain.base.Fleet;
import com.sap.sailing.domain.base.RaceColumn;
import com.sap.sailing.domain.common.RaceIdentifier;
import com.sap.sailing.domain.common.RegattaNameAndRaceName;
import com.sap.sailing.domain.leaderboard.Leaderboard;
import com.sap.sailing.domain.leaderboard.LeaderboardGroup;
import com.sap.sailing.domain.masterdataimport.TopLevelMasterData;
import com.sap.sailing.domain.tracking.DynamicTrackedRace;
import com.sap.sailing.server.interfaces.RacingEventService;

/** Keeps already-loaded destination races in bulk-loading mode while their imported fixes arrive. */
final class MasterDataImportCacheSuspension implements AutoCloseable {
    private final Set<DynamicTrackedRace> suspendedRaces = new HashSet<>();

    MasterDataImportCacheSuspension(final TopLevelMasterData masterData, final RacingEventService service) {
        try {
            for (final LeaderboardGroup group : masterData.getLeaderboardGroups()) {
                for (final Leaderboard leaderboard : group.getLeaderboards()) {
                    for (final RaceColumn column : leaderboard.getRaceColumns()) {
                        for (final Fleet fleet : column.getFleets()) {
                            final RaceIdentifier identifier = column.getRaceIdentifier(fleet);
                            final DynamicTrackedRace race = identifier == null ? null : service.getTrackedRace(
                                    new RegattaNameAndRaceName(identifier.getRegattaName(), identifier.getRaceName()));
                            if (race != null && !suspendedRaces.contains(race)) {
                                race.suspendAllCachesNotUpdatingWhileLoading();
                                suspendedRaces.add(race);
                            }
                        }
                    }
                }
            }
        } catch (final RuntimeException | Error failure) {
            try {
                close();
            } catch (final RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (final DynamicTrackedRace race : suspendedRaces) {
            try {
                race.resumeAllCachesNotUpdatingWhileLoading();
            } catch (final RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        suspendedRaces.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
