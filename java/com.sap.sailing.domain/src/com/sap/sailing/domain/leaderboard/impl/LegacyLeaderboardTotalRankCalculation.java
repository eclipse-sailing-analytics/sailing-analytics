package com.sap.sailing.domain.leaderboard.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import com.sap.sailing.domain.base.Competitor;
import com.sap.sailing.domain.base.Fleet;
import com.sap.sailing.domain.base.RaceColumn;
import com.sap.sailing.domain.base.RaceColumnInSeries;
import com.sap.sailing.domain.base.Regatta;
import com.sap.sailing.domain.base.Series;
import com.sap.sailing.domain.leaderboard.Leaderboard;
import com.sap.sailing.domain.leaderboard.RegattaLeaderboardWithOtherTieBreakingLeaderboard;
import com.sap.sailing.domain.leaderboard.ScoringScheme;
import com.sap.sailing.domain.tracking.WindLegTypeAndLegBearingAndORCPerformanceCurveCache;
import com.sap.sse.common.TimePoint;
import com.sap.sse.common.Util;
import com.sap.sse.common.Util.Pair;
import com.sap.sse.common.util.NaturalComparator;

/**
 * Calculation-local legacy ranking state and callback sequence. Constructed once per public comparator,
 * including nested Opening Series and earlier-stage comparisons.
 * <p>
 * The captured leaderboard is deliberately distinct from the virtual accessor: eager score preparation
 * and Opening Series ranking use the former, while the existing comparison hooks use the latter.
 * The accessor must not be invoked during construction.
 */
final class LegacyLeaderboardTotalRankCalculation implements LeaderboardTotalRankCalculation {
    private final Leaderboard leaderboard;
    private final ScoringScheme scoringScheme;
    private final Map<Util.Pair<Competitor, RaceColumn>, Double> netPointsCache;
    private final Map<Util.Pair<Competitor, RaceColumn>, Double> totalPointsCache;
    private final Map<Competitor, Set<RaceColumn>> discardedRaceColumnsPerCompetitor;
    private final boolean nullScoresAreBetter;
    private final TimePoint timePoint;
    private final Iterable<RaceColumn> raceColumnsToConsider;
    private final WindLegTypeAndLegBearingAndORCPerformanceCurveCache cache;
    private final NaturalComparator lastResortNaturalComparator;

    /**
     * An on-demand map of competitors with their opening series rank, 1-based.<p>
     *
     * Some scoring schemes that support medal series may need to break ties or determine promotions to
     * later rounds/stages based on the "opening series" results. The opening series then is considered
     * to contain all races excluding {@link RaceColumn#isMedalRace() medal races}.<p>
     *
     * When using this comparator in multiple comparisons, the opening series ranking may then be required
     * in many of these comparisons. It would be unnecessarily expensive to compute that ranking upon each
     * such comparison. Instead, once requested by this comparator, it is cached and can be re-used by later
     * comparisons performed by this comparator's {@link #compare(Competitor, Competitor)} method.<p>
     *
     * The implementation of the {@link Supplier} synchronizes on itself when {@link Supplier#get() asked}
     * for its value. If the opening series ranking has not been computed yet then this happens while
     * holding the supplier's monitor locked. This way, other threads waiting for the opening series
     * ranking will wait for the computation to complete. If the ranking has already been computed it
     * is returned immediately and the monitor is released quickly.
     */
    private final Supplier<Map<Competitor, Integer>> competitorsRankedByOpeningSeries;

    private final Supplier<Leaderboard> leaderboardAccessor;

    LegacyLeaderboardTotalRankCalculation(final Leaderboard leaderboard, final Supplier<Leaderboard> leaderboardAccessor,
            final TimePoint timePoint, final ScoringScheme scoringScheme, final boolean nullScoresAreBetter,
            final Iterable<RaceColumn> raceColumnsToConsider,
            final BiFunction<Competitor, RaceColumn, Double> totalPointsSupplier,
            final WindLegTypeAndLegBearingAndORCPerformanceCurveCache cache) {
        this.leaderboardAccessor = leaderboardAccessor;
        this.leaderboard = leaderboard;
        this.timePoint = timePoint;
        this.scoringScheme = scoringScheme;
        this.nullScoresAreBetter = nullScoresAreBetter;
        this.cache = cache;
        this.lastResortNaturalComparator = new NaturalComparator(/* caseSensitive */ true);
        netPointsCache = new HashMap<>();
        totalPointsCache = new HashMap<>();
        discardedRaceColumnsPerCompetitor = new HashMap<>();
        this.raceColumnsToConsider = raceColumnsToConsider;
        for (final Competitor competitor : leaderboard.getCompetitors()) {
            final Set<RaceColumn> discardedRaceColumns = leaderboard.getResultDiscardingRule().getDiscardedRaceColumns(
                    competitor, leaderboard, raceColumnsToConsider, timePoint, leaderboard.getScoringScheme());
            this.discardedRaceColumnsPerCompetitor.put(competitor, discardedRaceColumns);
            for (final RaceColumn raceColumn : raceColumnsToConsider) {
                final Pair<Competitor, RaceColumn> key = new Util.Pair<Competitor, RaceColumn>(competitor, raceColumn);
                final Double totalPoints = totalPointsSupplier.apply(competitor,  raceColumn);
                netPointsCache.put(key, leaderboard.getNetPoints(competitor, raceColumn, timePoint, discardedRaceColumns,
                        /* total points provider */ ()->totalPoints));
                totalPointsCache.put(key, totalPoints);
            }
        }
        competitorsRankedByOpeningSeries = new Supplier<Map<Competitor, Integer>>() {
            private volatile Map<Competitor, Integer> openingSeriesRanking = null;
            @Override
            public synchronized Map<Competitor, Integer> get() {
                if (openingSeriesRanking == null) {
                    final List<Competitor> result = Util.asList(leaderboard.getCompetitors());
                    Collections.sort(result, scoringScheme.getOpeningSeriesRankComparator(raceColumnsToConsider, nullScoresAreBetter, timePoint, leaderboard, totalPointsSupplier, cache));
                    openingSeriesRanking = new HashMap<>();
                    for (int i=0; i<result.size(); i++) {
                        openingSeriesRanking.put(result.get(i), i+1);
                    }
                }
                return openingSeriesRanking;
            }
        };
    }

    private Leaderboard getLeaderboard() {
        return leaderboardAccessor.get();
    }

    @Override
    public int compare(final Competitor o1, final Competitor o2) {
        final List<Util.Pair<RaceColumn, Double>> o1Scores = new ArrayList<>();
        final List<Util.Pair<RaceColumn, Double>> o2Scores = new ArrayList<>();
        final List<Util.Pair<RaceColumn, Double>> o1TotalPoints = new ArrayList<>();
        final List<Util.Pair<RaceColumn, Double>> o2TotalPoints = new ArrayList<>();
        double o1ScoreSum = getLeaderboard().getCarriedPoints(o1);
        double o2ScoreSum = getLeaderboard().getCarriedPoints(o2);
        final List<Util.Pair<RaceColumn, Double>> o1ScoringMedalRaces = new ArrayList<>();
        final List<Util.Pair<RaceColumn, Double>> o2ScoringMedalRaces = new ArrayList<>();
        Double o1MedalRaceScoreSum = 0.0; // "blindly" adds them all up, regardless of "series starts with zero score"
        Double o2MedalRaceScoreSum = 0.0; // "blindly" adds them all up, regardless of "series starts with zero score"
        Double o1CarryForwardScoreInMedals = null;
        Double o2CarryForwardScoreInMedals = null;
        // When a column has isStartsWithZeroScore, the competitor's score only need to be reset to zero if from there on
        // the competitor scored in this or any subsequent columns
        boolean needToResetO1ScoreUponNextValidResult = false;
        boolean needToResetO2ScoreUponNextValidResult = false;
        // Once we have established the fleet of a competitor in a series the fleet ordering for that competitor in that series
        // cannot change anymore; if assigned to an ordered fleet, the ordering will have to remain unchanged throughout the fleet,
        // and if assigned to an unordered fleet (ordering==0) the only change may be a re-assignment to a different unordered
        // fleet but not to an ordered fleet. Therefore, once we know the fleet ordering for a competitor in a series for sure,
        // we don't have to bother computing it again. It can be cached. See also bug 3838.
        // The fleet stored here may not be the same fleet for the competitor for all columns in that series but
        // it is guaranteed to have equal ordering as all fleets that the competitor is assigned to within the key series.
        final Map<Series, Map<Competitor, Fleet>> fleetWithCorrectOrderingForCompetitorBySeries = new HashMap<>();
        int defaultFleetBasedComparisonResult = 0; // relevant if no authoritative fleet-based comparison result was determined; based on extreme fleet vs. no fleet comparison
        int numberOfMedalRacesWonO1 = 0;
        boolean clearNumberOfMedalRacesWonByO1UponNextValidMedalRaceScore = false;
        boolean clearNumberOfMedalRacesWonByO2UponNextValidMedalRaceScore = false;
        int numberOfMedalRacesWonO2 = 0;
        int zeroBasedIndexOfLastMedalSeriesInWhichO1Scored = -1;
        int zeroBasedIndexOfLastMedalSeriesInWhichO2Scored = -1;
        int result = 0;
        columns: for (final RaceColumn raceColumn : raceColumnsToConsider) {
            needToResetO1ScoreUponNextValidResult = needToResetO1ScoreUponNextValidResult || raceColumn.isStartsWithZeroScore();
            needToResetO2ScoreUponNextValidResult = needToResetO2ScoreUponNextValidResult || raceColumn.isStartsWithZeroScore();
            final boolean o1ValidInNetScore = getLeaderboard().getScoringScheme().isValidInNetScore(getLeaderboard(), raceColumn, o1, timePoint);
            final boolean o2ValidInNetScore = getLeaderboard().getScoringScheme().isValidInNetScore(getLeaderboard(), raceColumn, o2, timePoint);
            final Double o1Score;
            if (o1ValidInNetScore) {
                final Pair<Competitor, RaceColumn> key = new Util.Pair<>(o1, raceColumn);
                o1Score = netPointsCache.get(key);
                if (o1Score != null) {
                    o1Scores.add(new Util.Pair<>(raceColumn, o1Score));
                    if (needToResetO1ScoreUponNextValidResult) {
                        o1ScoreSum = 0;
                        needToResetO1ScoreUponNextValidResult = false;
                    }
                    o1ScoreSum += o1Score;
                }
                final Double o1Total = totalPointsCache.get(key);
                if (o1Total != null) {
                    o1TotalPoints.add(new Util.Pair<>(raceColumn, o1Total));
                }
            } else {
                o1Score = null;
            }
            final Double o2Score;
            if (o2ValidInNetScore) {
                final Pair<Competitor, RaceColumn> key = new Util.Pair<Competitor, RaceColumn>(o2, raceColumn);
                o2Score = netPointsCache.get(key);
                if (o2Score != null) {
                    o2Scores.add(new Util.Pair<RaceColumn, Double>(raceColumn, o2Score));
                    if (needToResetO2ScoreUponNextValidResult) {
                        o2ScoreSum = 0;
                        needToResetO2ScoreUponNextValidResult = false;
                    }
                    o2ScoreSum += o2Score;
                }
                final Double o2Total = totalPointsCache.get(key);
                if (o2Total != null) {
                    o2TotalPoints.add(new Util.Pair<>(raceColumn, o2Total));
                }
            } else {
                o2Score = null;
            }
            if (o1ValidInNetScore && o2ValidInNetScore) {
                if (raceColumn.isMedalRace()) {
                    // only count the score for the medal race score if it wasn't a carry-forward column
                    if (!raceColumn.isCarryForward()) {
                        if (o1Score != null) {
                            o1MedalRaceScoreSum += o1Score;
                            o1ScoringMedalRaces.add(new Pair<>(raceColumn, o1Score));
                        }
                        if (o2Score != null) {
                            o2MedalRaceScoreSum += o2Score;
                            o2ScoringMedalRaces.add(new Pair<>(raceColumn, o2Score));
                        }
                    } else {
                        o1CarryForwardScoreInMedals = o1Score;
                        o2CarryForwardScoreInMedals = o2Score;
                    }
                    if (raceColumn instanceof RaceColumnInSeries && scoringScheme.isParticipatingInMedalRace(o1, o1Score, (RaceColumnInSeries) raceColumn, competitorsRankedByOpeningSeries)) {
                        zeroBasedIndexOfLastMedalSeriesInWhichO1Scored = getZeroBasedIndexOfSeries((RaceColumnInSeries) raceColumn);
                    }
                    if (raceColumn instanceof RaceColumnInSeries && scoringScheme.isParticipatingInMedalRace(o2, o2Score, (RaceColumnInSeries) raceColumn, competitorsRankedByOpeningSeries)) {
                        zeroBasedIndexOfLastMedalSeriesInWhichO2Scored = getZeroBasedIndexOfSeries((RaceColumnInSeries) raceColumn);
                    }
                    // similar to compareByFleet, however, tracking is not required; having medal race column points
                    // (tracked or manual) is sufficient
                    if (scoringScheme.isMedalWinAmountCriteria()) {
                        final Pair<Integer, Boolean> o1Result = leaderboard.getScoringScheme()
                                .getNewNumberOfMedalRacesWon(numberOfMedalRacesWonO1,
                                        clearNumberOfMedalRacesWonByO1UponNextValidMedalRaceScore, leaderboard, o1,
                                        raceColumn, timePoint, (final Competitor competitor)->totalPointsCache.get(new Pair<>(competitor, raceColumn)), cache);
                        final Pair<Integer, Boolean> o2Result = leaderboard.getScoringScheme()
                                .getNewNumberOfMedalRacesWon(numberOfMedalRacesWonO2,
                                        clearNumberOfMedalRacesWonByO2UponNextValidMedalRaceScore, leaderboard, o2,
                                        raceColumn, timePoint, (final Competitor competitor)->totalPointsCache.get(new Pair<>(competitor, raceColumn)), cache);
                        numberOfMedalRacesWonO1 = o1Result.getA();
                        clearNumberOfMedalRacesWonByO1UponNextValidMedalRaceScore = o1Result.getB();
                        numberOfMedalRacesWonO2 = o2Result.getA();
                        clearNumberOfMedalRacesWonByO2UponNextValidMedalRaceScore = o2Result.getB();
                    }
                }
                if (raceColumn.isTotalOrderDefinedByFleet()) {
                    final int preemptiveColumnResult;
                    final FleetComparisonResult compareByFleetResult = compareByFleet(raceColumn, o1, o2, fleetWithCorrectOrderingForCompetitorBySeries);
                    if (compareByFleetResult.getAuthoritativeFleetComparisonResult() != null) {
                        preemptiveColumnResult = compareByFleetResult.getAuthoritativeFleetComparisonResult();
                        defaultFleetBasedComparisonResult = 0;
                    } else {
                        if (defaultFleetBasedComparisonResult == 0) {
                            defaultFleetBasedComparisonResult = compareByFleetResult.getDefaultFleetComparisonResultBasedOnUnknownFleetAssignment();
                        }
                        preemptiveColumnResult = 0;
                    }
                    if (preemptiveColumnResult != 0) {
                        result = preemptiveColumnResult;
                        break columns;
                    }
                }
            }
        }
        if (result == 0) {
            result = scoringScheme.compareByMedalRaceParticipation(zeroBasedIndexOfLastMedalSeriesInWhichO1Scored, zeroBasedIndexOfLastMedalSeriesInWhichO2Scored);
        }
        if (result == 0) {
            result = defaultFleetBasedComparisonResult;
            if (result == 0) {
                // now count the races in which they scored; if they scored in a different number of races, prefer the
                // competitor who scored more often; otherwise, prefer the competitor who has a better score sum; if score sums are equal,
                // break tie by sorting scores and looking for the first score difference.
                result = scoringScheme.compareByNumberOfRacesScored(o1Scores.size(), o2Scores.size());
                if (result == 0) {
                    if (scoringScheme.isMedalWinAmountCriteria()) {
                        // if one reaches the target amount of races won then this has priority, else proceed with normal
                        // points-based scoring (e.g., not enough races yet)
                        result = scoringScheme.compareByMedalRacesWon(numberOfMedalRacesWonO1, numberOfMedalRacesWonO2);
                    }
                    if (result == 0) {
                        result = scoringScheme.compareByScoreSum(o1, o1Scores, o1ScoreSum, o2, o2Scores, o2ScoreSum, nullScoresAreBetter,
                                zeroBasedIndexOfLastMedalSeriesInWhichO1Scored >= 0 || zeroBasedIndexOfLastMedalSeriesInWhichO2Scored >= 0,
                                competitorsRankedByOpeningSeries);
                        if (result == 0) {
                            if (scoringScheme.isCarryForwardInMedalsCriteria()) {
                                result = scoringScheme.compareBySingleRaceColumnScore(o1CarryForwardScoreInMedals,
                                        o2CarryForwardScoreInMedals, nullScoresAreBetter);
                            }
                            if (result == 0) {
                                result = scoringScheme.compareByLastMedalRacesCriteria(o1, o1Scores, o2, o2Scores, nullScoresAreBetter, leaderboard,
                                        raceColumnsToConsider,
                                        (final Competitor competitor, final RaceColumn raceColumn)->totalPointsCache.get(new Pair<>(competitor, raceColumn)), cache, timePoint,
                                        zeroBasedIndexOfLastMedalSeriesInWhichO1Scored, numberOfMedalRacesWonO1, numberOfMedalRacesWonO2);
                                if (result == 0) {
                                    result = scoringScheme.compareByMedalRaceScore(o1, o2, o1MedalRaceScoreSum,
                                            o2MedalRaceScoreSum, o1ScoringMedalRaces, o2ScoringMedalRaces, timePoint,
                                            leaderboard, Collections.unmodifiableMap(discardedRaceColumnsPerCompetitor),
                                            (final Competitor competitor1, final RaceColumn raceColumn1) -> totalPointsCache
                                                    .get(new Pair<>(competitor1, raceColumn1)),
                                            nullScoresAreBetter, cache);
                                    if (result == 0) {
                                        result = scoringScheme.compareByBetterScore(o1, Collections.unmodifiableList(o1TotalPoints),
                                                                                    o2, Collections.unmodifiableList(o2TotalPoints),
                                                                                    raceColumnsToConsider, nullScoresAreBetter, timePoint,
                                                                                    leaderboard,
                                                                                    Collections.unmodifiableMap(discardedRaceColumnsPerCompetitor),
                                                                                    (final Competitor competitor1, final RaceColumn raceColumn1) -> totalPointsCache.get(new Pair<>(competitor1, raceColumn1)),
                                                                                    cache);
                                        if (result == 0) {
                                            // compare by last race:
                                            result = scoringScheme.compareByLastRace(o1TotalPoints, o2TotalPoints, nullScoresAreBetter, o1, o2, timePoint, cache);
                                            if (result == 0) {
                                                result = scoringScheme.compareByLatestRegattaInMetaLeaderboard(getLeaderboard(), o1, o2, timePoint);
                                                if (result == 0) {
                                                    if (getLeaderboard() instanceof RegattaLeaderboardWithOtherTieBreakingLeaderboard) {
                                                        result = scoringScheme.compareByOtherTieBreakingLeaderboard(
                                                                (RegattaLeaderboardWithOtherTieBreakingLeaderboard) getLeaderboard(), o1, o2, timePoint);
                                                    }
                                                    if (result == 0) {
                                                        result = compareByArbitraryButStableCriteria(o1, o2);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private int getZeroBasedIndexOfSeries(final RaceColumnInSeries raceColumnInSeries) {
        final Series medalSeries = raceColumnInSeries.getSeries();
        return Util.indexOf(medalSeries.getRegatta().getSeries(), medalSeries);
    }

    private int compareByArbitraryButStableCriteria(final Competitor o1, final Competitor o2) {
        return lastResortNaturalComparator.compare(o1.getStableLastResortOrderingCriterion(), o2.getStableLastResortOrderingCriterion());
    }

    private static class FleetComparisonResult {
        /**
         * Is non-{@code 0} if the two competitors have been identified as having raced in different fleets in
         * {@code raceColumn} with those fleets having different {@link Fleet#getOrdering() orderings}, or {@code 0}
         * if the two competitors have been identified authoritatively having raced in the same fleet in
         * {@code raceColumn}. Remains {@code null} if at least one competitor's fleet couldn't be identified.
         * Evaluation of further comparison criteria is not necessary only if an authoritative non-{@code 0}
         * answer was found. If fleet comparison has been calculated for all columns and no authoritative answer
         * was found, the {@link #defaultFleetComparisonResultBasedOnUnknownFleetAssignment} result can be used.
         */
        private final Integer authoritativeFleetComparisonResult;

        /**
         * When for one of the two competitors compared the fleet in which she raced in a race column
         * could not be determined and the other competitor can be identified as having competed in the
         * best or in the worst fleet in that column, a default comparison result is derived from this
         * such that the competitor with the unknown fleet assignment would be considered worse than
         * a participant of the best, and better than a participant of the worst fleet.<p>
         *
         * This result only has relevance if no non-{@code 0} authoritative result can be acquired across
         * all columns of the leaderboard.
         */
        private final int defaultFleetComparisonResultBasedOnUnknownFleetAssignment;

        public FleetComparisonResult(final Integer authoritativeFleetComparisonResult,
                final int defaultFleetComparisonResultBasedOnUnknownFleetAssignment) {
            super();
            this.authoritativeFleetComparisonResult = authoritativeFleetComparisonResult;
            this.defaultFleetComparisonResultBasedOnUnknownFleetAssignment = defaultFleetComparisonResultBasedOnUnknownFleetAssignment;
        }

        public Integer getAuthoritativeFleetComparisonResult() {
            return authoritativeFleetComparisonResult;
        }

        public int getDefaultFleetComparisonResultBasedOnUnknownFleetAssignment() {
            return defaultFleetComparisonResultBasedOnUnknownFleetAssignment;
        }
    }

    private FleetComparisonResult compareByFleet(final RaceColumn raceColumn, final Competitor o1, final Competitor o2,
            final Map<Series, Map<Competitor, Fleet>> fleetWithCorrectOrderingForCompetitorBySeries) {
        final Fleet o1f = getAFleetWithCorrectOrderingOfCompetitorFromCacheOrRaceColumnAndCache(raceColumn, o1, fleetWithCorrectOrderingForCompetitorBySeries);
        final Fleet o2f = getAFleetWithCorrectOrderingOfCompetitorFromCacheOrRaceColumnAndCache(raceColumn, o2, fleetWithCorrectOrderingForCompetitorBySeries);
        // if the fleet for both was identified because both were tracked in this column, then if the fleets
        // don't compare equal, return the fleet comparison as result immediately. Example: o1 competed in Gold fleet,
        // o2 in Silver fleet; Gold compares better to Silver, so o1 is compared better to o2.
        final FleetComparisonResult result;
        if (o1f != null) {
            if (o2f != null) {
                result = new FleetComparisonResult(o1f.compareTo(o2f),
                        /* defaultFleetComparisonResultBasedOnUnknownFleetAssignment */ 0);
            } else {
                // check if o1's fleet is best or worst in column; in that case, o1's membership in this fleet and the fact
                // that o2 is not part of that fleet determines the result
                result = new FleetComparisonResult(/* authoritativeFleetComparisonResult */ null, extremeFleetComparison(raceColumn, o1f));
            }
        } else if (o2f != null) {
            // check if o2's fleet is best or worst in column; in that case, o2's membership in this fleet and the fact
            // that o1 is not part of that fleet determines the result
            result = new FleetComparisonResult(/* authoritativeFleetComparisonResult */ null, -extremeFleetComparison(raceColumn, o2f));
        } else {
            result = new FleetComparisonResult(/* authoritativeFleetComparisonResult */ null,
                    /* defaultFleetComparisonResultBasedOnUnknownFleetAssignment */ 0);
        }
        return result;
    }

    /**
     * Tries to find a fleet assignment for the {@code competitor} in the {@code fleetWithCorrectOrderingForCompetitorBySeries} cache for
     * the {@link Series} corresponding with {@code RaceColumn} ({@code null} in case the column is not part of a {@link Regatta}).
     * If found, that fleet is returned, although it may not be the exact fleet assignment for the competitor in that column, but
     * at least it has the correct ordering which suffices for fleet comparisons.<p>
     *
     * If a fleet assignment is not found in the cache, the race column is {@link RaceColumn#getFleetOfCompetitor(Competitor) asked}
     * for the competitor's fleet assignment. If a result is found, it is added to the {@code fleetWithCorrectOrderingForCompetitorBySeries}
     * cache.
     */
    private Fleet getAFleetWithCorrectOrderingOfCompetitorFromCacheOrRaceColumnAndCache(final RaceColumn raceColumn, final Competitor competitor,
            final Map<Series, Map<Competitor, Fleet>> fleetWithCorrectOrderingForCompetitorBySeries) {
        Fleet fleetWithCorrectOrdering = null;
        final Series series;
        if (raceColumn instanceof RaceColumnInSeries) {
            series = ((RaceColumnInSeries) raceColumn).getSeries();
        } else {
            series = null;
        }
        final Map<Competitor, Fleet> fleetForCompetitorInSeries = fleetWithCorrectOrderingForCompetitorBySeries.get(series);
        if (fleetForCompetitorInSeries != null && fleetForCompetitorInSeries.containsKey(competitor)) {
            fleetWithCorrectOrdering = fleetForCompetitorInSeries.get(competitor);
        }
        if (fleetWithCorrectOrdering == null) {
            fleetWithCorrectOrdering = getFleetOfCompetitorFromRaceColumnAndCache(raceColumn, competitor,
                    fleetWithCorrectOrderingForCompetitorBySeries, series, fleetForCompetitorInSeries);
        }
        return fleetWithCorrectOrdering;
    }

    private Fleet getFleetOfCompetitorInRaceColumn(final RaceColumn raceColumn, final Competitor competitor) {
        Fleet result = null;
        for (final Fleet fleet : raceColumn.getFleets()) {
            if (Util.contains(getLeaderboard().getAllCompetitors(raceColumn, fleet), competitor)) {
                result = fleet;
                break;
            }
        }
        return result;
    }
    private Fleet getFleetOfCompetitorFromRaceColumnAndCache(final RaceColumn raceColumn, final Competitor competitor,
            final Map<Series, Map<Competitor, Fleet>> orderedFleetsForCompetitorsBySeries, final Series series,
            Map<Competitor, Fleet> fleetForCompetitorInSeries) {
        final Fleet fleetWithCorrectOrdering = getFleetOfCompetitorInRaceColumn(raceColumn, competitor);
        if (fleetWithCorrectOrdering != null) {
            if (fleetForCompetitorInSeries == null) {
                fleetForCompetitorInSeries = new HashMap<>();
                orderedFleetsForCompetitorsBySeries.put(series, fleetForCompetitorInSeries);
            }
            fleetForCompetitorInSeries.put(competitor, fleetWithCorrectOrdering);
        }
        return fleetWithCorrectOrdering;
    }

    /**
     * If the race column only has one fleet, no decision is made and 0 is returned. Otherwise, if there are other fleets with
     * a {@link Fleet#getOrdering() rank} different from that of <code>fleet</code>, we want to sort competitors with no fleet
     * assignment to the "worse" end of the leaderboard, therefore returning 1. Otherwise, 0 is returned.
     */
    private int extremeFleetComparison(final RaceColumn raceColumn, final Fleet fleet) {
        boolean greaterFleetExists = false;
        boolean lesserFleetExists = false;
        boolean othersExist = false;
        for (final Fleet f : raceColumn.getFleets()) {
            if (f != fleet) {
                othersExist = true;
                greaterFleetExists = greaterFleetExists || f.compareTo(fleet) > 0;
                lesserFleetExists = lesserFleetExists || f.compareTo(fleet) < 0;
            }
        }
        final int result;
        if (othersExist && (greaterFleetExists || lesserFleetExists)) {
            result = -1; // the competitor with no fleet is considered worse than a competitor in one of the ranked fleets
        } else {
            result = 0;
        }
        return result;
    }

}
