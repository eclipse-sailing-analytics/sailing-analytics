package com.sap.sailing.domain.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.sap.sailing.domain.abstractlog.impl.LogEventAuthorImpl;
import com.sap.sailing.domain.abstractlog.race.RaceLog;
import com.sap.sailing.domain.abstractlog.race.scoring.AdditionalScoringInformationType;
import com.sap.sailing.domain.abstractlog.race.scoring.impl.RaceLogAdditionalScoringInformationEventImpl;
import com.sap.sailing.domain.base.Competitor;
import com.sap.sailing.domain.base.CompetitorWithBoat;
import com.sap.sailing.domain.base.DomainFactory;
import com.sap.sailing.domain.base.Fleet;
import com.sap.sailing.domain.base.RaceColumn;
import com.sap.sailing.domain.base.RaceColumnInSeries;
import com.sap.sailing.domain.base.Regatta;
import com.sap.sailing.domain.base.Series;
import com.sap.sailing.domain.base.impl.RaceColumnInSeriesImpl;
import com.sap.sailing.domain.common.MaxPointsReason;
import com.sap.sailing.domain.common.ScoringSchemeType;
import com.sap.sailing.domain.leaderboard.FlexibleLeaderboard;
import com.sap.sailing.domain.leaderboard.Leaderboard;
import com.sap.sailing.domain.leaderboard.RegattaLeaderboard;
import com.sap.sailing.domain.leaderboard.impl.FlexibleLeaderboardImpl;
import com.sap.sailing.domain.leaderboard.impl.LeaderboardTotalRankComparator;
import com.sap.sailing.domain.leaderboard.impl.RegattaLeaderboardWithOtherTieBreakingLeaderboardImpl;
import com.sap.sailing.domain.leaderboard.impl.ThresholdBasedResultDiscardingRuleImpl;
import com.sap.sailing.domain.leaderboard.meta.FlexibleMetaLeaderboard;
import com.sap.sailing.domain.test.mock.MockedTrackedRaceWithStartTimeAndRanks;
import com.sap.sse.common.TimePoint;
import com.sap.sse.common.Util;
import com.sap.sse.common.Util.Pair;
import com.sap.sse.common.impl.MillisecondsTimePoint;

/** Rule-derived boundaries and legacy characterizations, separate from corrected production replay. */
class ScoringSchemeInteractionTest extends LeaderboardScoringAndRankingTestBase {
    private static final TimePoint START = new MillisecondsTimePoint(/* millis */ 1000);
    private static final TimePoint AFTER = new MillisecondsTimePoint(/* millis */ 2000);
    private static final double TOLERANCE = 0.00000001;

    @BeforeAll
    static void requireEnabledJavaAssertions() {
        assertTrue(RaceColumnInSeriesImpl.class.desiredAssertionStatus(),
                "Run scoring regression tests with JVM assertions enabled (-ea)");
    }

    @TestFactory
    Stream<DynamicTest> fixedEightDoesNotInterpolateIncompleteFleets() {
        return Stream.of(/* sizes */ 3, /* sizes */ 8, /* sizes */ 10).map((final Integer size) -> DynamicTest.dynamicTest("fixed eight / " + size, () -> {
            final List<Competitor> competitors = createCompetitors(size);
            final FlexibleLeaderboard board = flexible("Fixed eight", ScoringSchemeType.HIGH_POINT_WINNER_GETS_EIGHT);
            final RaceColumn column = addRace(board, "R1", competitors);
            assertEquals(/* expected */ 8.0, board.getTotalPoints(competitors.get(/* index */ 0), column, AFTER), TOLERANCE);
            assertEquals(size == 3 ? 6.0 : 1.0, board.getTotalPoints(competitors.get(size - 1), column, AFTER), TOLERANCE);
            final Competitor first = competitors.get(/* index */ 0);
            board.getScoreCorrection().setMaxPointsReason(first, column, MaxPointsReason.DNF);
            assertEquals(/* expected */ 0.0, board.getTotalPoints(first, column, AFTER), TOLERANCE);
            board.getScoreCorrection().correctScore(first, column, /* correctedScore */ 2.5);
            assertEquals(/* expected */ 2.5, board.getTotalPoints(first, column, AFTER), TOLERANCE);
            assertEquals(MaxPointsReason.DNF, board.getMaxPointsReason(first, column, AFTER));
        }));
    }

    @Test
    void leagueOverallUsesChildPointSumsBeforeLastActRank() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 2);
        final Competitor a = competitors.get(/* index */ 0);
        final Competitor b = competitors.get(/* index */ 1);
        final FlexibleLeaderboard first = flexible("First", ScoringSchemeType.LOW_POINT);
        final RaceColumn firstRace = addRace(first, "R1", competitors);
        first.getScoreCorrection().correctScore(b, firstRace, /* correctedScore */ 4.0);
        final FlexibleLeaderboard last = flexible("Last", ScoringSchemeType.LOW_POINT);
        final RaceColumn lastRace = addRace(last, "R1", Arrays.asList(b, a));
        last.getScoreCorrection().correctScore(a, lastRace, /* correctedScore */ 10.0);
        last.getScoreCorrection().correctScore(b, lastRace, /* correctedScore */ 2.0);
        final FlexibleMetaLeaderboard overall = overall(ScoringSchemeType.LOW_POINT_LEAGUE_OVERALL, first, last);
        assertEquals(/* expected */ 3.0, overall.getNetPoints(a, AFTER), TOLERANCE);
        assertEquals(/* expected */ 3.0, overall.getNetPoints(b, AFTER), TOLERANCE);
        assertOrder(overall, b, a); // Child totals 6 versus 11.
        last.getScoreCorrection().correctScore(a, lastRace, /* correctedScore */ 5.0);
        assertOrder(overall, b, a); // Child totals both 6; B wins the last act.
        last.getScoreCorrection().correctScore(a, lastRace, /* correctedScore */ 4.0);
        assertOrder(overall, a, b); // Child totals 5 versus 6, despite B winning the last act.
    }

    @Test
    void nestedNetPointsOverallUsesWeightedChildNetPointsAndReactsToCorrections() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 2);
        final Competitor a = competitors.get(/* index */ 0);
        final Competitor b = competitors.get(/* index */ 1);
        final FlexibleLeaderboard first = flexible("First", ScoringSchemeType.LOW_POINT);
        final RaceColumn firstRace = addRace(first, "R1", competitors);
        first.getScoreCorrection().correctScore(b, firstRace, /* correctedScore */ 4.0);
        final FlexibleLeaderboard last = flexible("Last", ScoringSchemeType.LOW_POINT);
        final RaceColumn lastRace = addRace(last, "R1", Arrays.asList(b, a));
        last.getScoreCorrection().correctScore(a, lastRace, /* correctedScore */ 10.0);
        last.getScoreCorrection().correctScore(b, lastRace, /* correctedScore */ 2.0);
        final FlexibleMetaLeaderboard overall = overall(ScoringSchemeType.LOW_POINT_OVERALL_USING_NET_POINTS, first, last);
        overall.getRaceColumnByName(last.getName()).setFactor(/* factor */ 2.0);
        final FlexibleMetaLeaderboard nested = overall(ScoringSchemeType.LOW_POINT_OVERALL_USING_NET_POINTS, overall);
        assertEquals(/* expected */ 21.0, nested.getNetPoints(a, AFTER), TOLERANCE);
        assertEquals(/* expected */ 8.0, nested.getNetPoints(b, AFTER), TOLERANCE);
        assertOrder(nested, b, a);
        last.getScoreCorrection().correctScore(a, lastRace, /* correctedScore */ 2.5);
        assertEquals(/* expected */ 6.0, nested.getNetPoints(a, AFTER), TOLERANCE);
        assertOrder(nested, a, b);
    }

    @TestFactory
    Stream<DynamicTest> essOverallFiveActEligibilityWithWeightedColumns() {
        return Stream.of(ScoringSchemeType.HIGH_POINT_ESS_OVERALL, ScoringSchemeType.HIGH_POINT_ESS_OVERALL_12)
                .map((final ScoringSchemeType type) -> DynamicTest.dynamicTest(type.name(), () -> {
                    final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 2);
                    final Competitor a = competitors.get(/* index */ 0);
                    final Competitor b = competitors.get(/* index */ 1);
                    final List<Leaderboard> acts = new ArrayList<>();
                    for (int i = 0; i < 5; i++) {
                        final FlexibleLeaderboard act = flexible("Act " + i, ScoringSchemeType.HIGH_POINT);
                        addRace(act, "R1", i < 4 ? competitors : Collections.singletonList(b));
                        acts.add(act);
                    }
                    final FlexibleMetaLeaderboard overall = overall(type, acts.toArray(new Leaderboard[0]));
                    for (int i = 0; i < acts.size(); i++) {
                        overall.getRaceColumnByName(acts.get(i).getName()).setFactor(i < 4 ? 2.0 : 0.5);
                    }
                    assertTrue(overall.getNetPoints(a, AFTER) > overall.getNetPoints(b, AFTER));
                    assertOrder(overall, b, a); // Four acts are insufficient despite the larger points sum.
                    final RaceColumn last = acts.get(/* index */ 4).getRaceColumnByName("R1");
                    last.setTrackedRace(last.getFleets().iterator().next(), new MockedTrackedRaceWithStartTimeAndRanks(START, competitors));
                    assertOrder(overall, a, b);
                    final double maximum = type == ScoringSchemeType.HIGH_POINT_ESS_OVERALL ? 10 : 12;
                    assertEquals(maximum * 8.5, overall.getNetPoints(a, AFTER), TOLERANCE);
                }));
    }

    @Test
    void externalTieBreakerFollowsTrackedLastFinishThenQualification() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 3);
        final Competitor a = competitors.get(/* index */ 0);
        final Competitor b = competitors.get(/* index */ 1);
        final Competitor c = competitors.get(/* index */ 2);
        final Regatta qualificationRegatta = simpleRegatta("Qualification", ScoringSchemeType.LOW_POINT);
        final RegattaLeaderboard qualification = createLeaderboard(qualificationRegatta, new int[0]);
        attach(qualification, competitors);
        final Regatta finals = simpleRegatta("Final", ScoringSchemeType.HIGH_POINT_BY_WINS_TIES_LASTLY_BROKEN_BY_OTHER_LEADERBOARD);
        final RegattaLeaderboard board = new RegattaLeaderboardWithOtherTieBreakingLeaderboardImpl(finals,
                new ThresholdBasedResultDiscardingRuleImpl(new int[0]), () -> qualification);
        attach(board, Arrays.asList(c, b, a));
        assertOrder(board, c, b, a); // A and B both have zero wins; tracked rank beats Qualification rank.
        final RaceColumn column = board.getRaceColumnByName("R1");
        column.enableCompetitorRegistrationOnRaceLog(column.getFleets().iterator().next());
        for (final Competitor competitor : competitors) {
            column.registerCompetitor(competitor, ((CompetitorWithBoat) competitor).getBoat(), column.getFleets().iterator().next());
            board.getScoreCorrection().correctScore(competitor, column, competitor == c ? 1.0 : 0.0);
        }
        column.releaseTrackedRace(column.getFleets().iterator().next());
        assertOrder(board, c, a, b); // No tracked ranks; use the actual other leaderboard.
        qualification.getScoreCorrection().correctScore(b, qualification.getRaceColumnByName("R1"), /* correctedScore */ 0.5);
        assertOrder(board, c, b, a);
    }

    @Test
    void automaticRedressIncludesDiscardedScoresButScaStopsAtItsColumn() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 3);
        final Competitor a = competitors.get(/* index */ 0);
        final FlexibleLeaderboard board = new FlexibleLeaderboardImpl("Redress",
                new ThresholdBasedResultDiscardingRuleImpl(new int[] { 3 }),
                DomainFactory.INSTANCE.createScoringScheme(ScoringSchemeType.LOW_POINT_WITH_AUTOMATIC_RDG), /* courseArea */ null);
        addRace(board, "R1", competitors);
        final RaceColumn redress = addRace(board, "R2", competitors);
        final RaceColumn worst = addRace(board, "R3", Arrays.asList(competitors.get(/* index */ 1), competitors.get(/* index */ 2), a));
        board.getScoreCorrection().setMaxPointsReason(a, redress, MaxPointsReason.RDG);
        assertEquals(/* expected */ 2.0, board.getTotalPoints(a, redress, AFTER), TOLERANCE);
        assertTrue(board.getResultDiscardingRule().getDiscardedRaceColumns(a, board, board.getRaceColumns(), AFTER,
                board.getScoringScheme()).contains(worst));
        board.getScoreCorrection().setMaxPointsReason(a, redress, MaxPointsReason.SCA);
        assertEquals(/* expected */ 1.0, board.getTotalPoints(a, redress, AFTER), TOLERANCE);
        board.getScoreCorrection().correctScore(a, redress, /* correctedScore */ 1.625);
        assertEquals(/* expected */ 1.625, board.getTotalPoints(a, redress, AFTER), TOLERANCE);
        assertEquals(MaxPointsReason.SCA, board.getMaxPointsReason(a, redress, AFTER));
    }

    @Test
    void ess2017EightPointOverrideRetainsLegacyTwelvePointPenaltyBase() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 8);
        final FlexibleLeaderboard board = flexible("ESS 2017", ScoringSchemeType.HIGH_POINT_FIRST_GETS_TWELVE_OR_EIGHT_2017);
        final RaceColumn column = addRace(board, "R1", competitors);
        final RaceLog log = column.getRaceLog(column.getFleets().iterator().next());
        log.add(new RaceLogAdditionalScoringInformationEventImpl(START, AFTER,
                new LogEventAuthorImpl("Fixture", /* priority */ 1), "eight", /* passId */ 0,
                AdditionalScoringInformationType.MAX_POINTS_DECREASE_MAX_SCORE));
        board.getScoreCorrection().setMaxPointsReason(competitors.get(/* index */ 6), column, MaxPointsReason.DNF);
        board.getScoreCorrection().setMaxPointsReason(competitors.get(/* index */ 7), column, MaxPointsReason.DSQ);
        assertEquals(/* expected */ 8.0, board.getTotalPoints(competitors.get(/* index */ 0), column, AFTER), TOLERANCE);
        assertEquals(/* expected */ 3.0, board.getTotalPoints(competitors.get(/* index */ 5), column, AFTER), TOLERANCE);
        // Characterization of the existing literal-12 penalty base, not a claim of compliance with the NoR.
        assertEquals(/* expected */ 6.0, board.getTotalPoints(competitors.get(/* index */ 6), column, AFTER), TOLERANCE);
        assertEquals(/* expected */ 5.0, board.getTotalPoints(competitors.get(/* index */ 7), column, AFTER), TOLERANCE);
    }

    @Test
    void thirdCompetitorChangesTheTiedCohortGraphAndCorrectionsRebuildIt() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 3);
        final Competitor a = competitors.get(/* index */ 0);
        final Competitor b = competitors.get(/* index */ 1);
        final Competitor c = competitors.get(/* index */ 2);
        final FlexibleLeaderboard board = flexible("Cohort", ScoringSchemeType.HIGH_POINT_FIRST_GETS_ONE);
        addRace(board, "AB", Arrays.asList(a, b));
        addRace(board, "BC", Arrays.asList(b, c));
        final RaceColumn ca = addRace(board, "CA", Arrays.asList(c, a));
        for (final Competitor first : competitors) {
            for (final Competitor second : competitors) {
                assertEquals(/* expected */ 0, compareBetter(board, first, second, board.getRaceColumns(), Collections.emptyMap()));
            }
        }
        // A beats B directly, but C closes the cycle. Every input permutation must yield the same display order.
        for (int offset = 0; offset < competitors.size(); offset++) {
            for (final boolean reverse : Arrays.asList(/* values */ false, /* values */ true)) {
                final List<Competitor> input = new ArrayList<>(competitors);
                Collections.rotate(input, offset);
                if (reverse) {
                    Collections.reverse(input);
                }
                input.sort(new LeaderboardTotalRankComparator(board, AFTER, board.getScoringScheme(),
                        /* nullScoresAreBetter */ false, board.getRaceColumns(), /* cache */ null));
                assertEquals(competitors, input);
            }
        }
        board.getScoreCorrection().correctScore(c, ca, /* correctedScore */ 2.0);
        assertTrue(compareBetter(board, a, b, board.getRaceColumns(), Collections.emptyMap()) < 0);
        assertOrder(board, c, a, b);
        board.getScoreCorrection().correctScore(c, ca, /* correctedScore */ 1.0);
        assertEquals(/* expected */ 0, compareBetter(board, a, b, board.getRaceColumns(), Collections.emptyMap()));
        assertOrder(board, a, b, c);
    }

    @Test
    void graphRespectsColumnAndTimeScopesAndPenaltyChanges() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 3);
        final Competitor a = competitors.get(/* index */ 0);
        final Competitor b = competitors.get(/* index */ 1);
        final Competitor c = competitors.get(/* index */ 2);
        final FlexibleLeaderboard board = flexible("Scoped graph", ScoringSchemeType.HIGH_POINT_FIRST_GETS_ONE);
        final RaceColumn ab = addRace(board, "AB", Arrays.asList(a, b));
        final RaceColumn bc = addRace(board, "BC", Arrays.asList(b, c));
        final RaceColumn ca = board.addRace(new MockedTrackedRaceWithStartTimeAndRanks(
                new MillisecondsTimePoint(/* millis */ 3000), Arrays.asList(c, a)), "CA", /* medalRace */ false);
        final TimePoint later = new MillisecondsTimePoint(/* millis */ 4000);
        assertNull(board.getTotalPoints(c, ca, AFTER));
        assertTrue(compareBetter(board, a, b, board.getRaceColumns(), Collections.emptyMap(), AFTER) < 0);
        assertEquals(/* expected */ 0, compareBetter(board, a, b, board.getRaceColumns(), Collections.emptyMap(), later));
        assertTrue(compareBetter(board, a, b, Arrays.asList(ab, bc), Collections.emptyMap(), later) < 0);
        board.getScoreCorrection().setMaxPointsReason(c, ca, MaxPointsReason.DSQ);
        assertEquals(/* expected */ -1.0, board.getTotalPoints(c, ca, later), TOLERANCE);
        assertEquals(competitors, Util.asList(board.getCompetitorsFromBestToWorst(later)));
        board.getScoreCorrection().correctScore(c, ca, /* correctedScore */ 0.0);
        assertEquals(/* expected */ 0.0, board.getTotalPoints(c, ca, later), TOLERANCE);
        assertEquals(MaxPointsReason.DSQ, board.getMaxPointsReason(c, ca, later));
        // The DSQ also promotes A's raw finish rank; restore it before comparing an equal-total cohort again.
        assertEquals(/* expected */ 1.0, board.getTotalPoints(a, ca, later), TOLERANCE);
        board.getScoreCorrection().setMaxPointsReason(c, ca, MaxPointsReason.NONE);
        board.getScoreCorrection().correctScore(c, ca, /* correctedScore */ 1.0);
        assertEquals(/* expected */ 0.0, board.getTotalPoints(a, ca, later), TOLERANCE);
        assertEquals(/* expected */ 0, compareBetter(board, a, b, board.getRaceColumns(), Collections.emptyMap(), later));
    }

    @Test
    void scopedA8UsesExistingDiscardsWithoutComparingSubsetSums() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 2);
        final Competitor a = competitors.get(/* index */ 0);
        final Competitor b = competitors.get(/* index */ 1);
        final FlexibleLeaderboard board = new FlexibleLeaderboardImpl("A8 scope",
                new ThresholdBasedResultDiscardingRuleImpl(new int[] { 3 }),
                DomainFactory.INSTANCE.createScoringScheme(ScoringSchemeType.LOW_POINT), /* courseArea */ null);
        final RaceColumn first = addRace(board, "F1", competitors);
        final RaceColumn last = addRace(board, "F2", competitors);
        final RaceColumn outside = addRace(board, "Q", competitors);
        board.getScoreCorrection().correctScore(a, first, /* correctedScore */ 1.0);
        board.getScoreCorrection().correctScore(a, last, /* correctedScore */ 8.0);
        board.getScoreCorrection().correctScore(a, outside, /* correctedScore */ 9.0);
        board.getScoreCorrection().correctScore(b, first, /* correctedScore */ 2.0);
        board.getScoreCorrection().correctScore(b, last, /* correctedScore */ 3.0);
        board.getScoreCorrection().correctScore(b, outside, /* correctedScore */ 10.0);
        final Map<Competitor, Set<RaceColumn>> discards = new HashMap<>();
        for (final Competitor competitor : competitors) {
            discards.put(competitor, board.getResultDiscardingRule().getDiscardedRaceColumns(competitor, board,
                    board.getRaceColumns(), AFTER, board.getScoringScheme()));
            assertEquals(Collections.singleton(outside), discards.get(competitor));
        }
        final List<RaceColumn> scope = Arrays.asList(first, last);
        // 1,8 wins A8.1 against 2,3 despite having the larger subset sum.
        assertTrue(compareBetter(board, a, b, scope, discards) < 0);
        board.getScoreCorrection().correctScore(a, first, /* correctedScore */ 8.0);
        board.getScoreCorrection().correctScore(a, last, /* correctedScore */ 1.0);
        board.getScoreCorrection().correctScore(a, outside, /* correctedScore */ 2.0);
        board.getScoreCorrection().correctScore(b, first, /* correctedScore */ 9.0);
        discards.put(a, board.getResultDiscardingRule().getDiscardedRaceColumns(a, board, board.getRaceColumns(), AFTER,
                board.getScoringScheme()));
        assertEquals(Collections.singleton(first), discards.get(a));
        assertTrue(compareBetter(board, a, b, Collections.singletonList(first), discards) > 0);
        // Recomputing discards for that single-column scope would wrongly reintroduce A's excluded 8.
        final Map<Competitor, Set<RaceColumn>> recomputed = new HashMap<>();
        for (final Competitor competitor : competitors) {
            recomputed.put(competitor, Collections.emptySet());
        }
        assertTrue(compareBetter(board, a, b, Collections.singletonList(first), recomputed) < 0);
        assertEquals(Collections.singleton(first), discards.get(a));
    }

    @TestFactory
    Stream<DynamicTest> firstToTwoCarryWinDoesNotDependOnCarryFlag() {
        return Stream.of(/* carryFlags */ false, /* carryFlags */ true).map((final Boolean carryFlag) -> DynamicTest.dynamicTest("carry flag " + carryFlag, () -> {
            final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 4);
            final Competitor a = competitors.get(/* index */ 0);
            final Competitor b = competitors.get(/* index */ 1);
            final Competitor c = competitors.get(/* index */ 2);
            final Competitor d = competitors.get(/* index */ 3);
            final Regatta regatta = createRegatta(/* qualifying */ 1, new String[] { "Default" }, /* final */ 0,
                    new String[] { "Default" }, /* medal */ true, /* numberOfMedalRaces */ 4, "First to two",
                    DomainFactory.INSTANCE.getOrCreateBoatClass("Fixture", /* typicallyStartsUpwind */ true),
                    DomainFactory.INSTANCE.createScoringScheme(ScoringSchemeType.LOW_POINT_FIRST_TO_WIN_TWO_RACES));
            final RegattaLeaderboard board = createLeaderboard(regatta, new int[0]);
            final Series medals = ((RaceColumnInSeries) board.getRaceColumnByName("M1")).getSeries();
            medals.setStartsWithZeroScore(true);
            medals.setFirstColumnIsNonDiscardableCarryForward(carryFlag);
            final RaceColumn qualification = board.getRaceColumnByName("Q1");
            qualification.setTrackedRace(qualification.getFleets().iterator().next(),
                    new MockedTrackedRaceWithStartTimeAndRanks(START, competitors));
            final RaceColumn carry = board.getRaceColumnByName("M1");
            final Fleet fleet = carry.getFleets().iterator().next();
            carry.enableCompetitorRegistrationOnRaceLog(fleet);
            for (int i = 0; i < competitors.size(); i++) {
                final Competitor competitor = competitors.get(i);
                carry.registerCompetitor(competitor, ((CompetitorWithBoat) competitor).getBoat(), fleet);
                board.getScoreCorrection().correctScore(competitor, carry, (double) i + 1);
            }
            final RaceColumn first = board.getRaceColumnByName("M2");
            first.setTrackedRace(fleet, new MockedTrackedRaceWithStartTimeAndRanks(START, Arrays.asList(d, a, b, c)));
            final RaceColumn second = board.getRaceColumnByName("M3");
            second.setTrackedRace(fleet, new MockedTrackedRaceWithStartTimeAndRanks(START, Arrays.asList(b, a, c, d)));
            assertOrder(board, a, b, d, c); // No two-win champion: Q plus Final points, with Qualification reset.
            assertEquals(/* expected */ 5.0, board.getNetPoints(a, AFTER), TOLERANCE);
            final RaceColumn last = board.getRaceColumnByName("M4");
            last.setTrackedRace(fleet, new MockedTrackedRaceWithStartTimeAndRanks(START, Arrays.asList(d, a, b, c)));
            assertOrder(board, d, a, b, c); // D's two wins beat A's lower points sum.
            assertEquals(/* expected */ 7.0, board.getNetPoints(a, AFTER), TOLERANCE);
            assertEquals(/* expected */ 10.0, board.getNetPoints(d, AFTER), TOLERANCE);
            first.setTrackedRace(fleet, new MockedTrackedRaceWithStartTimeAndRanks(START, Arrays.asList(b, c, d, a)));
            second.setTrackedRace(fleet, new MockedTrackedRaceWithStartTimeAndRanks(START, Arrays.asList(c, b, d, a)));
            last.setTrackedRace(fleet, new MockedTrackedRaceWithStartTimeAndRanks(START, Arrays.asList(a, b, c, d)));
            assertOrder(board, a, b, c, d); // A's Q win plus one sailed win suffice with either flag value.
            assertEquals(/* expected */ 10.0, board.getNetPoints(a, AFTER), TOLERANCE);
            assertEquals(/* expected */ 7.0, board.getNetPoints(b, AFTER), TOLERANCE);
        }));
    }

    private int compareBetter(final Leaderboard board, final Competitor first, final Competitor second,
            final Iterable<RaceColumn> scope, final Map<Competitor, Set<RaceColumn>> discards) {
        return compareBetter(board, first, second, scope, discards, AFTER);
    }

    private int compareBetter(final Leaderboard board, final Competitor first, final Competitor second,
            final Iterable<RaceColumn> scope, final Map<Competitor, Set<RaceColumn>> discards, final TimePoint timePoint) {
        return board.getScoringScheme().compareByBetterScore(first, scores(board, first, scope, timePoint), second,
                scores(board, second, scope, timePoint), scope, /* nullScoresAreBetter */ false, timePoint, board, discards,
                (final Competitor competitor, final RaceColumn column) -> board.getTotalPoints(competitor, column, timePoint), /* cache */ null);
    }

    private List<Pair<RaceColumn, Double>> scores(final Leaderboard board, final Competitor competitor,
            final Iterable<RaceColumn> scope, final TimePoint timePoint) {
        final List<Pair<RaceColumn, Double>> result = new ArrayList<>();
        for (final RaceColumn column : scope) {
            final Double points = board.getTotalPoints(competitor, column, timePoint);
            if (points != null) {
                result.add(new Pair<>(column, points));
            }
        }
        return result;
    }

    @Test
    void unstartedRawRaceHasNoScoreAndCannotTriggerDiscardThreshold() throws Exception {
        final List<Competitor> competitors = createCompetitors(/* numberOfCompetitors */ 2);
        final FlexibleLeaderboard board = new FlexibleLeaderboardImpl("Threshold",
                new ThresholdBasedResultDiscardingRuleImpl(new int[] { 2 }),
                DomainFactory.INSTANCE.createScoringScheme(ScoringSchemeType.LOW_POINT), /* courseArea */ null);
        final RaceColumn first = addRace(board, "R1", competitors);
        final RaceColumn future = board.addRace(new MockedTrackedRaceWithStartTimeAndRanks(new MillisecondsTimePoint(/* millis */ 3000),
                Arrays.asList(competitors.get(/* index */ 1), competitors.get(/* index */ 0))), "R2", /* medalRace */ false);
        final Competitor a = competitors.get(/* index */ 0);
        assertNull(board.getTotalPoints(a, future, AFTER));
        assertTrue(board.getResultDiscardingRule().getDiscardedRaceColumns(a, board, board.getRaceColumns(), AFTER,
                board.getScoringScheme()).isEmpty());
        final TimePoint later = new MillisecondsTimePoint(/* millis */ 4000);
        assertEquals(Collections.singleton(future), board.getResultDiscardingRule().getDiscardedRaceColumns(a, board,
                board.getRaceColumns(), later, board.getScoringScheme()));
        assertEquals(/* expected */ 1.0, board.getTotalPoints(a, first, later), TOLERANCE);
    }

    private FlexibleLeaderboard flexible(final String name, final ScoringSchemeType type) {
        return new FlexibleLeaderboardImpl(name, new ThresholdBasedResultDiscardingRuleImpl(new int[0]),
                DomainFactory.INSTANCE.createScoringScheme(type), /* courseArea */ null);
    }

    private RaceColumn addRace(final FlexibleLeaderboard board, final String name, final List<Competitor> finishOrder) throws Exception {
        return board.addRace(new MockedTrackedRaceWithStartTimeAndRanks(START, finishOrder), name, /* medalRace */ false);
    }

    private FlexibleMetaLeaderboard overall(final ScoringSchemeType type, final Leaderboard... children) {
        final FlexibleMetaLeaderboard result = new FlexibleMetaLeaderboard("Overall " + children[0].getName(),
                DomainFactory.INSTANCE.createScoringScheme(type), new ThresholdBasedResultDiscardingRuleImpl(new int[0]));
        for (final Leaderboard child : children) {
            result.addLeaderboard(child);
        }
        return result;
    }

    private Regatta simpleRegatta(final String name, final ScoringSchemeType type) {
        return createSimpleRegatta(/* numberOfRaces */ 1, name,
                DomainFactory.INSTANCE.getOrCreateBoatClass("Fixture", /* typicallyStartsUpwind */ true),
                DomainFactory.INSTANCE.createScoringScheme(type));
    }

    private void attach(final RegattaLeaderboard board, final List<Competitor> finishOrder) {
        final RaceColumn column = board.getRaceColumnByName("R1");
        column.setTrackedRace(column.getFleets().iterator().next(), new MockedTrackedRaceWithStartTimeAndRanks(START, finishOrder));
    }

    private void assertOrder(final Leaderboard board, final Competitor... expected) {
        assertEquals(Arrays.asList(expected), Util.asList(board.getCompetitorsFromBestToWorst(AFTER)));
    }
}
