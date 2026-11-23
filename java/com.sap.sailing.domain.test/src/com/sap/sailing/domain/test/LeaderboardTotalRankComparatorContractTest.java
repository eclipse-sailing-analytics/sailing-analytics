package com.sap.sailing.domain.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import com.sap.sailing.domain.base.Competitor;
import com.sap.sailing.domain.base.Fleet;
import com.sap.sailing.domain.base.RaceColumn;
import com.sap.sailing.domain.leaderboard.FlexibleLeaderboard;
import com.sap.sailing.domain.leaderboard.Leaderboard;
import com.sap.sailing.domain.leaderboard.ScoringScheme;
import com.sap.sailing.domain.leaderboard.impl.FlexibleLeaderboardImpl;
import com.sap.sailing.domain.leaderboard.impl.LeaderboardTotalRankComparator;
import com.sap.sailing.domain.leaderboard.impl.LowPoint;
import com.sap.sailing.domain.leaderboard.impl.ThresholdBasedResultDiscardingRuleImpl;
import com.sap.sailing.domain.test.mock.MockedTrackedRaceWithStartTimeAndRanks;
import com.sap.sse.common.TimePoint;
import com.sap.sse.common.Util;
import com.sap.sse.common.impl.MillisecondsTimePoint;

/** Observable extension, cache-lifetime and short-circuit contracts of the public comparator. */
class LeaderboardTotalRankComparatorContractTest extends LeaderboardScoringAndRankingTestBase {
    private static final TimePoint START = new MillisecondsTimePoint(/* millis */ 1000);
    private static final TimePoint AFTER = new MillisecondsTimePoint(/* millis */ 2000);

    private static final class AccessorComparator extends LeaderboardTotalRankComparator {
        private final Leaderboard comparisonLeaderboard;

        AccessorComparator(final Leaderboard capturedLeaderboard, final Leaderboard comparisonLeaderboard,
                final BiFunction<Competitor, RaceColumn, Double> totalPointsSupplier) {
            super(capturedLeaderboard, AFTER, capturedLeaderboard.getScoringScheme(), /* nullScoresAreBetter */ false,
                    capturedLeaderboard.getRaceColumns(), totalPointsSupplier, /* cache */ null);
            this.comparisonLeaderboard = comparisonLeaderboard;
        }

        @Override
        protected Leaderboard getLeaderboard() {
            assertTrue(comparisonLeaderboard != null, "Constructor must not call the virtual accessor");
            return comparisonLeaderboard;
        }

        @Override
        protected Comparator<Double> getScoreComparator() {
            return Comparator.reverseOrder();
        }

        int compareUsingProtectedScoreHook(final double first, final double second) {
            return compareByScoreSum(first, second);
        }
    }

    /**
     * Supplied race points must be read during construction and reused by every comparison without calling the supplier
     * again. The protected compareByScoreSum() hook must honor the overridden getScoreComparator(), while competitor
     * comparisons continue to use the scoring scheme's ordering. The constructor without a supplied-points function must
     * obtain the actual leaderboard points.
     */
    @Test
    void suppliedPointsAreEagerAndCachedWhileProtectedScoreHookRemainsSeparate() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 2);
        final FlexibleLeaderboard leaderboard = flexible(competitors);
        final RaceColumn column = leaderboard.getRaceColumns().iterator().next();
        final List<Competitor> calls = new ArrayList<>();
        final AccessorComparator comparator = new AccessorComparator(leaderboard, leaderboard,
                (final Competitor competitor, final RaceColumn raceColumn) -> {
                    assertSame(column, raceColumn);
                    calls.add(competitor);
                    return competitor == competitors.get(/* index */ 0) ? 9.0 : 1.0;
                });
        assertEquals(Util.asList(leaderboard.getCompetitors()), calls);
        assertTrue(comparator.compareUsingProtectedScoreHook(/* first */ 9.0, /* second */ 1.0) < 0);
        for (int repetition = 0; repetition < 2; repetition++) {
            assertTrue(comparator.compare(competitors.get(/* index */ 0), competitors.get(/* index */ 1)) > 0);
            assertTrue(comparator.compare(competitors.get(/* index */ 1), competitors.get(/* index */ 0)) < 0);
        }
        assertEquals(/* expected */ 2, calls.size(), "Comparison must reuse the supplied, eagerly captured points");
        // The other constructor obtains actual points, rather than the supplied reversed scores.
        final LeaderboardTotalRankComparator ordinary = new LeaderboardTotalRankComparator(leaderboard, AFTER,
                leaderboard.getScoringScheme(), /* nullScoresAreBetter */ false, leaderboard.getRaceColumns(), /* cache */ null);
        assertTrue(ordinary.compare(competitors.get(/* index */ 0), competitors.get(/* index */ 1)) < 0);
    }

    /**
     * Construction must prepare race scores and discards from the supplied leaderboard without calling the overridden
     * getLeaderboard() accessor. Comparisons must read carried points through that accessor, which this test redirects
     * to a different leaderboard. Changing those carried points between comparisons must reverse the result while
     * retaining the prepared race scores.
     */
    @Test
    void comparisonUsesVirtualCarriedPointsWithoutReplacingCapturedScorePreparation() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 2);
        final FlexibleLeaderboard captured = flexible(competitors);
        final Leaderboard comparison = mock(Leaderboard.class);
        when(comparison.getScoringScheme()).thenReturn(captured.getScoringScheme());
        when(comparison.getCarriedPoints(competitors.get(/* index */ 0))).thenReturn(/* value */ 100.0);
        final AccessorComparator comparator = new AccessorComparator(captured, comparison,
                (final Competitor competitor, final RaceColumn column) -> captured.getTotalPoints(competitor, column, AFTER));
        assertTrue(comparator.compare(competitors.get(/* index */ 0), competitors.get(/* index */ 1)) > 0);
        when(comparison.getCarriedPoints(competitors.get(/* index */ 0))).thenReturn(/* value */ 0.0);
        assertTrue(comparator.compare(competitors.get(/* index */ 0), competitors.get(/* index */ 1)) < 0);
    }

    /**
     * Opening Series ranks must be calculated only when a scoring-scheme callback requests them, rather than during
     * construction or every comparison. Repeated requests through one comparator must reuse the same rank map; a new
     * comparator must perform its own calculation.
     */
    @Test
    void openingRanksAreLazyAndReusedOnlyWithinOneComparator() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 2);
        final FlexibleLeaderboard leaderboard = flexible(competitors);
        final AtomicBoolean requestOpening = new AtomicBoolean(/* initialValue */ false);
        final AtomicInteger openingComparators = new AtomicInteger();
        final AtomicReference<Map<Competitor, Integer>> opening = new AtomicReference<>();
        final ScoringScheme delegate = leaderboard.getScoringScheme();
        final ScoringScheme callbacks = mock(ScoringScheme.class, (final InvocationOnMock invocation) -> {
            if (invocation.getMethod().getName().equals("compareByScoreSum") && requestOpening.get()) {
                final Supplier<Map<Competitor, Integer>> supplier = invocation.getArgument(/* index */ 8);
                final Map<Competitor, Integer> ranks = supplier.get();
                final Map<Competitor, Integer> previous = opening.getAndSet(ranks);
                if (previous != null) {
                    assertSame(previous, ranks);
                }
                assertEquals(Integer.valueOf(/* i */ 1), ranks.get(competitors.get(/* index */ 0)));
                assertEquals(Integer.valueOf(/* i */ 2), ranks.get(competitors.get(/* index */ 1)));
            }
            if (invocation.getMethod().getName().equals("getOpeningSeriesRankComparator")) {
                assertSame(leaderboard, invocation.getArgument(/* index */ 3));
                openingComparators.incrementAndGet();
            }
            return invocation.getMethod().invoke(delegate, invocation.getArguments());
        });
        final LeaderboardTotalRankComparator comparator = new LeaderboardTotalRankComparator(leaderboard, AFTER,
                callbacks, /* nullScoresAreBetter */ false, leaderboard.getRaceColumns(), /* cache */ null);
        assertEquals(/* expected */ 0, openingComparators.get());
        assertTrue(comparator.compare(competitors.get(/* index */ 0), competitors.get(/* index */ 1)) < 0);
        assertEquals(/* expected */ 0, openingComparators.get());
        requestOpening.set(true);
        for (int repetition = 0; repetition < 2; repetition++) {
            assertTrue(comparator.compare(competitors.get(/* index */ 0), competitors.get(/* index */ 1)) < 0);
            assertTrue(comparator.compare(competitors.get(/* index */ 1), competitors.get(/* index */ 0)) > 0);
        }
        assertEquals(/* expected */ 1, openingComparators.get());
        opening.set(null);
        final LeaderboardTotalRankComparator fresh = new LeaderboardTotalRankComparator(leaderboard, AFTER,
                callbacks, /* nullScoresAreBetter */ false, leaderboard.getRaceColumns(), /* cache */ null);
        assertEquals(/* expected */ 1, openingComparators.get());
        assertTrue(fresh.compare(competitors.get(/* index */ 0), competitors.get(/* index */ 1)) < 0);
        assertEquals(/* expected */ 2, openingComparators.get());
    }

    /**
     * When a column defines an authoritative order between the competitors' fleets, that order must decide the comparison
     * before later columns or scoring-scheme comparison callbacks are consulted. Fleet membership must be read afresh for
     * each pair comparison, so reassignment is reflected even when the same comparator is reused.
     */
    @Test
    void authoritativeFleetOrderStopsColumnTraversalAndLaterSchemeCallbacks() {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 2);
        final Competitor first = competitors.get(/* index */ 0);
        final Competitor second = competitors.get(/* index */ 1);
        final Leaderboard leaderboard = mock(Leaderboard.class);
        final RaceColumn column = mock(RaceColumn.class);
        final RaceColumn later = mock(RaceColumn.class, (final InvocationOnMock invocation) -> {
            throw new AssertionError("Authoritative fleet order must stop before the later column");
        });
        final Fleet gold = mock(Fleet.class);
        final Fleet silver = mock(Fleet.class);
        when(gold.compareTo(silver)).thenReturn(/* value */ -1);
        when(silver.compareTo(gold)).thenReturn(/* value */ 1);
        when(column.isTotalOrderDefinedByFleet()).thenReturn(/* value */ true);
        when(column.getFleets()).thenAnswer((final InvocationOnMock invocation) -> Arrays.asList(gold, silver));
        when(leaderboard.getCompetitors()).thenReturn(competitors);
        when(leaderboard.getScoringScheme()).thenReturn(new LowPoint());
        when(leaderboard.getResultDiscardingRule()).thenReturn(new ThresholdBasedResultDiscardingRuleImpl(new int[0]));
        when(leaderboard.getAllCompetitors(column, gold)).thenReturn(Collections.singletonList(first));
        when(leaderboard.getAllCompetitors(column, silver)).thenReturn(Collections.singletonList(second));
        final ScoringScheme callbacks = mock(ScoringScheme.class, (final InvocationOnMock invocation) -> {
            throw new AssertionError("Authoritative fleet order must precede scheme tie callbacks");
        });
        final LeaderboardTotalRankComparator comparator = new LeaderboardTotalRankComparator(leaderboard, AFTER,
                callbacks, /* nullScoresAreBetter */ false, Arrays.asList(column, later),
                (final Competitor competitor, final RaceColumn raceColumn) -> 1.0, /* cache */ null);
        assertEquals(/* expected */ -1, comparator.compare(first, second));
        assertEquals(/* expected */ 1, comparator.compare(second, first));
        // Fleet membership must be re-read for a new pair comparison, rather than cached for the comparator lifetime.
        when(leaderboard.getAllCompetitors(column, gold)).thenReturn(Collections.singletonList(second));
        when(leaderboard.getAllCompetitors(column, silver)).thenReturn(Collections.singletonList(first));
        assertEquals(/* expected */ 1, comparator.compare(first, second));
    }

    private FlexibleLeaderboard flexible(final List<Competitor> competitors) throws Exception {
        final FlexibleLeaderboard result = new FlexibleLeaderboardImpl("Comparator contract",
                new ThresholdBasedResultDiscardingRuleImpl(new int[0]), new LowPoint(), /* courseArea */ null);
        result.addRace(new MockedTrackedRaceWithStartTimeAndRanks(START, competitors), "R1", /* medalRace */ false);
        return result;
    }
}
