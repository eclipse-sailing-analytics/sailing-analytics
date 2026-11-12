package com.sap.sailing.domain.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import org.json.simple.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.sap.sailing.domain.base.Competitor;
import com.sap.sailing.domain.base.Fleet;
import com.sap.sailing.domain.base.RaceColumn;
import com.sap.sailing.domain.base.RaceColumnInSeries;
import com.sap.sailing.domain.base.Series;
import com.sap.sailing.domain.base.impl.RaceColumnInSeriesImpl;
import com.sap.sailing.domain.common.LeaderboardType;
import com.sap.sailing.domain.leaderboard.Leaderboard;
import com.sap.sailing.domain.leaderboard.RegattaLeaderboard;
import com.sap.sailing.domain.leaderboard.impl.FlexibleLeaderboardImpl;
import com.sap.sse.common.Util;
import com.sap.sse.common.Util.Pair;

class ProductionScoringReplayTest {
    private static final double TOLERANCE = 0.00000001;

    @BeforeAll
    static void requireEnabledJavaAssertions() {
        assertTrue(RaceColumnInSeriesImpl.class.desiredAssertionStatus(),
                "Run scoring regression tests with JVM assertions enabled (-ea)");
    }

    @TestFactory
    Stream<DynamicTest> productionReplays() {
        return Arrays.asList("kw2014-49er", "scl2021-porto-cervo", "scl2022-vilamoura", "scl2022-travemuende",
                "team-racing-2026-final", "munich-2k", "kite-2024-qn-women", "windsurf-2024-qn-men",
                "polish-2025-49erfx", "marstrand-2016-finals", "49erfx-2017", "automatic-rdg", "kite-2023-a82",
                "high_point", "high_point_first_gets_ten", "high_point_first_gets_ten_or_eight",
                "high_point_first_gets_twelve_or_eight", "high_point_first_gets_twelve_or_eight_2017",
                "high_point_winner_gets_eight_and_interpolation", "high_point_winner_gets_five", "high_point_winner_gets_six",
                "low_point_a82_only", "low_point_tie_break_based_on_last_series_only",
                "low_point_with_eliminations_and_rounds_winner_gets_07", "high_point_last_breaks_tie",
                "high_point_ess_overall", "high_point_ess_overall_12", "low_point_league_overall", "low_point_overall_using_net_points",
                "flexible-qingdao-2011", "flexible-kiel-2012-470w", "flexible-505-worlds-2011", "youth2025-windsurf-women")
                .stream().map((final String resource) -> DynamicTest.dynamicTest(resource, () -> {
                    final ProductionScoringFixture fixture = new ProductionScoringFixture(resource, /* reverseInsertionOrder */ false);
                    assertReplay(fixture);
                    final ProductionScoringFixture reversed = new ProductionScoringFixture(resource, /* reverseInsertionOrder */ true);
                    assertReplay(reversed);
                    assertEquals(rankedIds(fixture), rankedIds(reversed), "Competitor insertion order must not affect ranking");
                }));
    }

    private void assertReplay(final ProductionScoringFixture fixture) {
        for (final ProductionScoringFixture child : fixture.children) {
            assertReplay(child);
        }
        final Leaderboard leaderboard = fixture.leaderboard;
        if (LeaderboardType.FlexibleLeaderboard.name().equals(fixture.data.get("type"))) {
            assertFlexibleConfiguration(fixture);
        }
        final List<Competitor> ranked = Util.asList(leaderboard.getCompetitorsFromBestToWorst(fixture.timePoint));
        assertEquals(fixture.competitors.size(), ranked.size());
        for (final JSONObject row : ProductionScoringFixture.objects(fixture.data, "competitors")) {
            final Competitor competitor = fixture.competitors.get(row.get("id"));
            final String context = fixture.data.get("name") + ": " + row.get("name");
            assertEquals(((Number) row.get("expectedTotal")).doubleValue(), leaderboard.getNetPoints(competitor, fixture.timePoint),
                    TOLERANCE, context);
            final Set<RaceColumn> discarded = leaderboard.getResultDiscardingRule().getDiscardedRaceColumns(competitor,
                    leaderboard, leaderboard.getRaceColumns(), fixture.timePoint, leaderboard.getScoringScheme());
            for (final JSONObject cell : ProductionScoringFixture.objects(row, "cells")) {
                final RaceColumn column = leaderboard.getRaceColumnByName((String) cell.get("column"));
                assertEquals(cell.get("expectedDiscard"), discarded.contains(column), context + " / " + column.getName());
                final Number net = (Number) cell.get("expectedNet");
                if (net != null) {
                    assertEquals(net.doubleValue(), leaderboard.getNetPoints(competitor, column, fixture.timePoint, discarded),
                            TOLERANCE, context + " / " + column.getName());
                } else {
                    assertNull(leaderboard.getNetPoints(competitor, column, fixture.timePoint, discarded),
                            context + " / " + column.getName());
                }
            }
            final int actualRank = ranked.indexOf(competitor) + 1;
            if (row.containsKey("sportingRank")) {
                assertTrue(actualRank >= 66 && actualRank <= 68, context);
            } else {
                assertEquals(((Number) row.get("expectedRank")).intValue(), actualRank, context);
            }
        }
    }

    private void assertFlexibleConfiguration(final ProductionScoringFixture fixture) {
        final Leaderboard leaderboard = fixture.leaderboard;
        assertEquals(FlexibleLeaderboardImpl.class, leaderboard.getClass());
        final List<String> expectedColumns = new ArrayList<>();
        final List<String> actualColumns = new ArrayList<>();
        for (final RaceColumn column : leaderboard.getRaceColumns()) {
            actualColumns.add(column.getName());
        }
        for (final JSONObject definition : ProductionScoringFixture.objects(fixture.data, "columns")) {
            final String name = (String) definition.get("name");
            expectedColumns.add(name);
            final RaceColumn column = leaderboard.getRaceColumnByName(name);
            assertNotNull(column, name);
            assertEquals(definition.get("medal"), column.isMedalRace(), name);
            assertEquals(definition.get("oneAlwaysStaysOne"), column.isOneAlwaysStaysOne(), name);
            assertEquals(((Number) definition.get("factor")).doubleValue(),
                    leaderboard.getScoringScheme().getScoreFactor(column), TOLERANCE, name);
            final List<JSONObject> fleets = ProductionScoringFixture.objects(definition, "fleets");
            assertEquals(fleets.size(), Util.size(column.getFleets()), name);
            for (final JSONObject fleetDefinition : fleets) {
                final Fleet fleet = column.getFleetByName((String) fleetDefinition.get("name"));
                assertNotNull(fleet, name);
                assertEquals(((Number) fleetDefinition.get("ordering")).intValue(), fleet.getOrdering(), name);
            }
        }
        assertEquals(expectedColumns, actualColumns);
        for (final JSONObject row : ProductionScoringFixture.objects(fixture.data, "competitors")) {
            final List<JSONObject> cells = ProductionScoringFixture.objects(row, "cells");
            assertEquals(expectedColumns.size(), cells.size(), "Complete result row: " + row.get("name"));
            for (final JSONObject cell : cells) {
                assertNotNull(cell.get("points"), "Scored cell: " + row.get("name") + " / " + cell.get("column"));
                assertNotNull(cell.get("expectedNet"), "Expected net score: " + row.get("name") + " / " + cell.get("column"));
            }
        }
    }

    @Test
    void youthWindsurfingKeepsOneWhenDoublingAndCombinesWeightingDiscardsAndTies() throws Exception {
        final ProductionScoringFixture fixture = new ProductionScoringFixture("youth2025-windsurf-women", /* reverseInsertionOrder */ false);
        final Leaderboard board = fixture.leaderboard;
        final RaceColumn slalomRace = board.getRaceColumnByName("R3");
        final Series slalom = ((RaceColumnInSeries) slalomRace).getSeries();
        assertTrue(slalom.isOneAlwaysStaysOne());
        assertEquals(/* expected */ 2.0, board.getScoringScheme().getScoreFactor(slalomRace), TOLERANCE);
        for (final Series series : ((RegattaLeaderboard) board).getRegatta().getSeries()) {
            assertNull(series.getResultDiscardingRule(), series.getName());
            assertEquals(series.getName().startsWith("Slalom"), series.isOneAlwaysStaysOne(), series.getName());
        }
        final Competitor winner = fixture.competitors.get("580c1124-1f0d-4624-81c2-c16d349841f0");
        final Competitor martina = fixture.competitors.get("d293d592-de58-448c-8967-f4c83361d197");
        final Competitor alba = fixture.competitors.get("861f2e50-5534-4803-bd6a-36c8af2a3d45");
        // Fixed values independently anchor fixture inversion: a flag error must not cancel between input and output.
        assertEquals(/* expected */ 1.0, board.getTotalPoints(winner, slalomRace, fixture.timePoint), TOLERANCE);
        assertEquals(/* expected */ 1.0, board.getNetPoints(winner, slalomRace, fixture.timePoint, Collections.emptySet()), TOLERANCE);
        assertEquals(/* expected */ 6.0, board.getNetPoints(winner, fixture.timePoint), TOLERANCE);
        assertEquals(/* expected */ 2.0, board.getTotalPoints(martina, slalomRace, fixture.timePoint), TOLERANCE);
        assertEquals(/* expected */ 3.0, board.getNetPoints(martina, slalomRace, fixture.timePoint, Collections.emptySet()), TOLERANCE);
        assertEquals(/* expected */ 4.0, board.getTotalPoints(alba, slalomRace, fixture.timePoint), TOLERANCE);
        assertEquals(/* expected */ 7.0, board.getNetPoints(alba, slalomRace, fixture.timePoint, Collections.emptySet()), TOLERANCE);
        final Map<Competitor, Set<RaceColumn>> discards = new HashMap<>();
        for (final Competitor competitor : Arrays.asList(martina, alba)) {
            assertEquals(/* expected */ 36.0, board.getNetPoints(competitor, fixture.timePoint), TOLERANCE);
            discards.put(competitor, board.getResultDiscardingRule().getDiscardedRaceColumns(competitor, board,
                    board.getRaceColumns(), fixture.timePoint, board.getScoringScheme()));
        }
        assertEquals(Collections.singleton(board.getRaceColumnByName("R6")), discards.get(martina));
        assertEquals(Collections.singleton(board.getRaceColumnByName("R1")), discards.get(alba));
        assertTrue(compareBetter(fixture, martina, scores(fixture, martina), alba, scores(fixture, alba), discards) < 0);
        assertTrue(compareBetter(fixture, alba, scores(fixture, alba), martina, scores(fixture, martina), discards) > 0);
    }

    @Test
    void european49erFxSeparatesExportWeightingFromLegacyA8Inputs() throws Exception {
        final ProductionScoringFixture fixture = new ProductionScoringFixture("49erfx-2017", /* reverseInsertionOrder */ false);
        final Leaderboard board = fixture.leaderboard;
        final Competitor argentina = fixture.competitors.get("259f301a-dcc3-438f-bb4b-6b564efe7f93");
        final Competitor spain = fixture.competitors.get("1296d497-2895-4136-b55a-87ff749f1c79");
        final RaceColumn q5 = board.getRaceColumnByName("Q5");
        assertEquals(/* expected */ 6.0, board.getTotalPoints(argentina, q5, fixture.timePoint), TOLERANCE);
        assertEquals(/* expected */ 3.0, board.getNetPoints(argentina, q5, fixture.timePoint, Collections.emptySet()), TOLERANCE);
        assertEquals(/* expected */ 12.0, board.getScoringScheme().getOriginalScoreFromScoreScaledByFactor(q5,
                board.getTotalPoints(argentina, q5, fixture.timePoint)), TOLERANCE);
        final Map<Competitor, Set<RaceColumn>> discards = new HashMap<>();
        for (final Competitor competitor : Arrays.asList(argentina, spain)) {
            discards.put(competitor, board.getResultDiscardingRule().getDiscardedRaceColumns(competitor, board,
                    board.getRaceColumns(), fixture.timePoint, board.getScoringScheme()));
            assertEquals(/* expected */ 69.0, board.getNetPoints(competitor, fixture.timePoint), TOLERANCE);
        }
        // Legacy A8 inversely scales already unweighted total points: ARG's F2=7 beats ESP's Q9=8.
        assertTrue(compareBetter(fixture, argentina, scores(fixture, argentina), spain, scores(fixture, spain), discards) < 0);
        final List<Pair<RaceColumn, Double>> argentinaExport = exportedScores(fixture, argentina);
        final List<Pair<RaceColumn, Double>> spainExport = exportedScores(fixture, spain);
        // Counterfactual weighted input removes the second inverse scaling and reverses this comparison.
        assertTrue(compareBetter(fixture, argentina, argentinaExport, spain, spainExport, discards) > 0);
        // SI 20.4.1 (p6) instead specifies Fleet-Racing-only A8, independently also favoring ARG.
        assertEquals(Arrays.asList(/* scores */ 1.0, /* scores */ 4.0, /* scores */ 7.0, /* scores */ 10.0),
                countingFleetScores(fixture, argentina, discards.get(argentina)));
        assertEquals(Arrays.asList(/* scores */ 1.0, /* scores */ 4.0, /* scores */ 8.0, /* scores */ 14.0),
                countingFleetScores(fixture, spain, discards.get(spain)));
    }

    private List<Double> countingFleetScores(final ProductionScoringFixture fixture, final Competitor competitor,
            final Set<RaceColumn> discarded) {
        final List<Double> result = new ArrayList<>();
        for (final Pair<RaceColumn, Double> score : scores(fixture, competitor)) {
            if (score.getA().getName().startsWith("F") && !discarded.contains(score.getA())) {
                result.add(score.getB());
            }
        }
        Collections.sort(result);
        return result;
    }

    private List<Pair<RaceColumn, Double>> exportedScores(final ProductionScoringFixture fixture, final Competitor competitor) {
        final List<Pair<RaceColumn, Double>> result = new ArrayList<>();
        for (final Pair<RaceColumn, Double> score : scores(fixture, competitor)) {
            result.add(new Pair<>(score.getA(), fixture.leaderboard.getScoringScheme().getScoreScaledByFactor(score.getA(), score.getB())));
        }
        return result;
    }

    private int compareBetter(final ProductionScoringFixture fixture, final Competitor first,
            final List<Pair<RaceColumn, Double>> firstScores, final Competitor second,
            final List<Pair<RaceColumn, Double>> secondScores, final Map<Competitor, Set<RaceColumn>> discards) {
        final Leaderboard board = fixture.leaderboard;
        return board.getScoringScheme().compareByBetterScore(first, firstScores, second, secondScores,
                board.getRaceColumns(), /* nullScoresAreBetter */ false, fixture.timePoint, board, discards,
                (final Competitor competitor, final RaceColumn column) -> board.getTotalPoints(competitor, column, fixture.timePoint), /* cache */ null);
    }

    @Test
    void kiel2014OfficialSharedRankRemainsASportingTie() throws Exception {
        final ProductionScoringFixture fixture = new ProductionScoringFixture("kw2014-49er", /* reverseInsertionOrder */ false);
        final List<Competitor> tied = new ArrayList<>();
        for (final JSONObject row : ProductionScoringFixture.objects(fixture.data, "competitors")) {
            if (row.containsKey("sportingRank")) {
                assertEquals(/* expected */ 66, ((Number) row.get("sportingRank")).intValue());
                tied.add(fixture.competitors.get(row.get("id")));
            }
        }
        assertEquals(/* expected */ 3, tied.size());
        assertNotNull(fixture.data.get("officialTieSource"));
        final Leaderboard board = fixture.leaderboard;
        final Map<Competitor, Set<RaceColumn>> discards = new HashMap<>();
        for (final Competitor competitor : tied) {
            discards.put(competitor, board.getResultDiscardingRule().getDiscardedRaceColumns(competitor, board,
                    board.getRaceColumns(), fixture.timePoint, board.getScoringScheme()));
        }
        for (final Competitor first : tied) {
            for (final Competitor second : tied) {
                assertEquals(/* expected */ 0, board.getScoringScheme().compareByBetterScore(first, scores(fixture, first),
                        second, scores(fixture, second), board.getRaceColumns(), /* nullScoresAreBetter */ false,
                        fixture.timePoint, board, discards,
                        (final Competitor competitor, final RaceColumn column) -> board.getTotalPoints(competitor, column, fixture.timePoint), /* cache */ null));
                assertEquals(/* expected */ 0, board.getScoringScheme().compareByLastRace(scores(fixture, first),
                        scores(fixture, second), /* nullScoresAreBetter */ false, first, second, fixture.timePoint, /* cache */ null));
            }
        }
    }

    private List<Pair<RaceColumn, Double>> scores(final ProductionScoringFixture fixture, final Competitor competitor) {
        final List<Pair<RaceColumn, Double>> result = new ArrayList<>();
        for (final RaceColumn column : fixture.leaderboard.getRaceColumns()) {
            final Double score = fixture.leaderboard.getTotalPoints(competitor, column, fixture.timePoint);
            if (score != null) {
                result.add(new Pair<>(column, score));
            }
        }
        return result;
    }

    private List<String> rankedIds(final ProductionScoringFixture fixture) {
        final List<String> result = new ArrayList<>();
        for (final Competitor competitor : fixture.leaderboard.getCompetitorsFromBestToWorst(fixture.timePoint)) {
            result.add(competitor.getId().toString());
        }
        return result;
    }
}
