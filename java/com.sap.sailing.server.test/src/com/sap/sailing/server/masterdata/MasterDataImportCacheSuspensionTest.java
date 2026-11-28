package com.sap.sailing.server.masterdata;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sap.sailing.domain.base.Fleet;
import com.sap.sailing.domain.base.RaceColumn;
import com.sap.sailing.domain.common.RegattaNameAndRaceName;
import com.sap.sailing.domain.leaderboard.Leaderboard;
import com.sap.sailing.domain.leaderboard.LeaderboardGroup;
import com.sap.sailing.domain.masterdataimport.TopLevelMasterData;
import com.sap.sailing.domain.tracking.DynamicTrackedRace;
import com.sap.sailing.server.interfaces.RacingEventService;

public class MasterDataImportCacheSuspensionTest {
    private final TopLevelMasterData masterData = mock(TopLevelMasterData.class);
    private final RacingEventService service = mock(RacingEventService.class);
    private final DynamicTrackedRace target = mock(DynamicTrackedRace.class);
    private final DynamicTrackedRace other = mock(DynamicTrackedRace.class);
    private final RaceColumn secondColumn = mock(RaceColumn.class);
    private final Fleet fleet = mock(Fleet.class);
    private final RegattaNameAndRaceName targetId = new RegattaNameAndRaceName("2026", "Race");
    private final RegattaNameAndRaceName otherId = new RegattaNameAndRaceName("2025", "Race");

    @BeforeEach
    public void setUp() {
        final LeaderboardGroup group = mock(LeaderboardGroup.class);
        final Leaderboard leaderboard = mock(Leaderboard.class);
        final RaceColumn firstColumn = mock(RaceColumn.class);
        when(masterData.getLeaderboardGroups()).thenReturn(Collections.singleton(group));
        when(group.getLeaderboards()).thenReturn(Collections.singletonList(leaderboard));
        when(leaderboard.getRaceColumns()).thenReturn(Arrays.asList(firstColumn, secondColumn));
        when(firstColumn.getFleets()).thenAnswer(invocation -> Collections.singleton(fleet));
        when(secondColumn.getFleets()).thenAnswer(invocation -> Collections.singleton(fleet));
        when(firstColumn.getRaceIdentifier(fleet)).thenReturn(targetId);
        when(secondColumn.getRaceIdentifier(fleet)).thenReturn(targetId);
        when(service.getTrackedRace(targetId)).thenReturn(target);
        when(service.getTrackedRace(otherId)).thenReturn(other);
    }

    @Test
    public void onlyTargetedRacesAreSuspendedAndDuplicateReferencesAreDeduplicated() {
        try (final MasterDataImportCacheSuspension suspension = new MasterDataImportCacheSuspension(masterData, service)) {
            verify(target).suspendAllCachesNotUpdatingWhileLoading();
            verify(target, never()).resumeAllCachesNotUpdatingWhileLoading();
        }
        verify(target).resumeAllCachesNotUpdatingWhileLoading();
        verifyNoMoreInteractions(target, other);
        verify(service, never()).getTrackedRace(otherId);
    }

    @Test
    public void importFailureStillResumesTargetedRaces() {
        assertThrows(IllegalStateException.class, () -> {
            try (final MasterDataImportCacheSuspension suspension = new MasterDataImportCacheSuspension(masterData, service)) {
                throw new IllegalStateException("Simulated stream failure");
            }
        });
        verify(target).resumeAllCachesNotUpdatingWhileLoading();
    }

    @Test
    public void failureWhileAcquiringSuspensionsReleasesPreviouslyAcquiredOnes() {
        when(secondColumn.getRaceIdentifier(fleet)).thenReturn(otherId);
        doThrow(new IllegalStateException("Cannot suspend")).when(other).suspendAllCachesNotUpdatingWhileLoading();
        assertThrows(IllegalStateException.class, () -> new MasterDataImportCacheSuspension(masterData, service));
        verify(target).resumeAllCachesNotUpdatingWhileLoading();
        verify(other, never()).resumeAllCachesNotUpdatingWhileLoading();
    }
}
