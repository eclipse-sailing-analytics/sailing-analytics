package com.sap.sailing.domain.leaderboard.impl;

import java.util.Comparator;
import java.util.function.BiFunction;

import com.sap.sailing.domain.base.Competitor;
import com.sap.sailing.domain.base.RaceColumn;
import com.sap.sailing.domain.leaderboard.Leaderboard;
import com.sap.sailing.domain.leaderboard.ScoringScheme;
import com.sap.sailing.domain.tracking.WindLegTypeAndLegBearingAndORCPerformanceCurveCache;
import com.sap.sse.common.TimePoint;

/**
 * Compares two competitors that occur in a {@link Leaderboard#getCompetitors()} set in the context of the
 * {@link Leaderboard} according to their total rank at a given point in time. "Better" is represented as "lesser."
 * <p>
 * 
 * For a general {@link Leaderboard} we don't know about Series. In a {@link Leaderboard}, all we have are the columns
 * with their fleets which can be compared but may compare equal; also, we know if a column is a medal race column.
 * Participants of a medal race always score better than all remaining competitors. (We only know the medal race
 * participants if the column has a tracked race.) If both competitors scored in a medal race, this score will be
 * compared, regardless of the sum of any other scores.
 * <p>
 * 
 * If that hasn't decided the order yet, then as soon as we find a column with more than one tracked race with the fleets
 * comparing non-equal, this decides the order if the two competitors belong to different fleets.
 * <p>
 * 
 * If that still hasn't decided the order, the scores will decide. If the competitors scored in a different number of
 * races, the competitor scoring in more races is considered better (lesser). If they scored in an equal number of races,
 * the score sums are compared using {@link #compareByScoreSum(double, double)}. If that still doesn't decide the order, the scores
 * are sorted from best to worst and compared one by one. The first differing score decides. If all scores are equal pairwise,
 * both competitors are ranked equal.<p>
 * 
 * For a RegattaLeaderboard, if a column in a series has tracked races for all of its fleets, these competitors rank
 * better than all remaining competitors that appear in prior series. This is probably the generalization of the
 * "medal race" rule where the medal "series" has one race, and if it's tracked, its participants rank better than all
 * others in prior series who did not reach the medal race.
 * 
 * @author Axel Uhl (D043530)
 */
public class LeaderboardTotalRankComparator implements Comparator<Competitor> {
    private final Leaderboard leaderboard;
    private final ScoringScheme scoringScheme;
    private final boolean nullScoresAreBetter;
    private final LeaderboardTotalRankCalculation calculation;

    /**
     * Considers only the race columns specified in <code>raceColumnsToConsider</code> and behaves as if the other columns
     * were filled with <code>null</code> values. Those columns not considered do not count for determining the discards either.
     * For example, if the first race may be discarded when five races have been completed, and only four {@link RaceColumn}s are
     * considered, no race's score will be discarded for this call. This allows clients to tell what the ranking would have been
     * with only the race columns specified in <code>raceColumnsToConsider</code> having completed for all fleets.<p>
     * 
     * Note, that <code>timePoint</code> is considered in addition to <code>raceColumnsToConsider</code> such that the scores in
     * those columns considered is computed for the <code>timePoint</code> specified. In particular, if a time point is chosen that
     * is before a race in a column that is considered has started, <code>null</code> values may result in that column.
     */
    public LeaderboardTotalRankComparator(final Leaderboard leaderboard, final TimePoint timePoint, final ScoringScheme scoringScheme,
            final boolean nullScoresAreBetter, final Iterable<RaceColumn> raceColumnsToConsider,
            final WindLegTypeAndLegBearingAndORCPerformanceCurveCache cache) {
        this(leaderboard, timePoint, scoringScheme, nullScoresAreBetter, raceColumnsToConsider,
                (final Competitor competitor, final RaceColumn raceColumn)->leaderboard.getTotalPoints(competitor, raceColumn, timePoint, cache),
                cache);
    }

    /**
     * Like
     * {@link #LeaderboardTotalRankComparator(Leaderboard, TimePoint, ScoringScheme, boolean, Iterable, WindLegTypeAndLegBearingAndORCPerformanceCurveCache)},
     * but additionally allows the caller to supply the
     * {@link Leaderboard#getTotalPoints(Competitor, RaceColumn, TimePoint, WindLegTypeAndLegBearingAndORCPerformanceCurveCache)
     * total points} function which may take its results from a pre-calculated cache such as the one otherwise
     * constructed here.
     */
    public LeaderboardTotalRankComparator(final Leaderboard leaderboard, final TimePoint timePoint, final ScoringScheme scoringScheme,
            final boolean nullScoresAreBetter, final Iterable<RaceColumn> raceColumnsToConsider,
            final BiFunction<Competitor, RaceColumn, Double> totalPointsSupplier,
            final WindLegTypeAndLegBearingAndORCPerformanceCurveCache cache) {
        this.leaderboard = leaderboard;
        this.scoringScheme = scoringScheme;
        this.nullScoresAreBetter = nullScoresAreBetter;
        calculation = LeaderboardTotalRankCalculation.create(leaderboard, this::getLeaderboard, timePoint,
                scoringScheme, nullScoresAreBetter, raceColumnsToConsider, totalPointsSupplier, cache);
    }

    protected Leaderboard getLeaderboard() {
        return leaderboard;
    }

    @Override
    public int compare(final Competitor o1, final Competitor o2) {
        return calculation.compare(o1, o2);
    }

    /**
     * Returns a comparator for comparing individual scores. This implementation returns a comparator for the usual ISAF
     * scheme where lesser scores compare "better" which again means "lesser." Therefore, the comparator returned compares
     * the integer numbers by their natural ordering.
     */
    protected Comparator<Double> getScoreComparator() {
        return scoringScheme.getScoreComparator(nullScoresAreBetter);
    }

    /**
     * This implementation ranks a competitor better (lesser) if it has the lower score sum
     */
    protected int compareByScoreSum(final double o1ScoreSum, final double o2ScoreSum) {
        return getScoreComparator().compare(o1ScoreSum, o2ScoreSum);
    }
}
