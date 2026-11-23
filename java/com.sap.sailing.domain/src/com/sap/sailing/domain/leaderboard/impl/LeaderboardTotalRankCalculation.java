package com.sap.sailing.domain.leaderboard.impl;

import java.util.Comparator;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import com.sap.sailing.domain.base.Competitor;
import com.sap.sailing.domain.base.RaceColumn;
import com.sap.sailing.domain.leaderboard.Leaderboard;
import com.sap.sailing.domain.leaderboard.ScoringScheme;
import com.sap.sailing.domain.tracking.WindLegTypeAndLegBearingAndORCPerformanceCurveCache;
import com.sap.sse.common.TimePoint;

/** Internal selection boundary shared by all total-rank comparator construction paths. */
interface LeaderboardTotalRankCalculation extends Comparator<Competitor> {
    /**
     * Creates calculation-local caches eagerly; the virtual leaderboard accessor is used only when comparing.
     * Scheme callbacks, supplied points and authoritative leaderboard settings retain their existing roles.
     */
    static LeaderboardTotalRankCalculation create(final Leaderboard leaderboard,
            final Supplier<Leaderboard> leaderboardAccessor, final TimePoint timePoint, final ScoringScheme scoringScheme,
            final boolean nullScoresAreBetter, final Iterable<RaceColumn> raceColumnsToConsider,
            final BiFunction<Competitor, RaceColumn, Double> totalPointsSupplier,
            final WindLegTypeAndLegBearingAndORCPerformanceCurveCache cache) {
        return new LegacyLeaderboardTotalRankCalculation(leaderboard, leaderboardAccessor, timePoint, scoringScheme,
                nullScoresAreBetter, raceColumnsToConsider, totalPointsSupplier, cache);
    }
}
