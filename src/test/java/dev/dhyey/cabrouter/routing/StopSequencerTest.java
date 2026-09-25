package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;
import static dev.dhyey.cabrouter.routing.Fixtures.TRAVEL;
import static dev.dhyey.cabrouter.routing.Fixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StopSequencerTest {

    private final StopSequencer sequencer = new StopSequencer(TRAVEL);

    @Test
    void stopsOnOneRoadAreVisitedNearestFirst() {
        List<Stop> shuffled = List.of(stop(3, 9, 0), stop(1, 3, 0), stop(4, 12, 0), stop(2, 6, 0));

        List<Stop> route = sequencer.sequence(OFFICE, shuffled);

        assertThat(route).extracting(Stop::employeeId).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void twoOptUntanglesACrossingThatNearestNeighbourCreates() {
        // NN from the office goes to A (closest), then jumps east to C, back west to B,
        // then across to D, which crosses its own path. 2-opt must find something shorter.
        List<Stop> stops = List.of(stop(1, 1, 0), stop(2, 2, -1.5), stop(3, 2, 1.4), stop(4, 3.5, 0.2));
        List<Stop> nn = sequencer.nearestNeighbour(OFFICE, stops);

        List<Stop> improved = sequencer.twoOpt(OFFICE, nn, null);

        assertThat(km(improved)).isLessThanOrEqualTo(km(nn));
        assertThat(km(improved)).isCloseTo(km(bruteForce(stops, null)), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void fixedEndIsRespected() {
        Stop far = stop(9, 10, 0);
        List<Stop> others = List.of(stop(1, 2, 1), stop(2, 5, -1), stop(3, 7, 1));

        List<Stop> route = sequencer.sequence(OFFICE, others, far.location());

        // Visiting in north-south order and then finishing at `far` is optimal here.
        assertThat(route).extracting(Stop::employeeId).containsExactly(1L, 2L, 3L);
    }

    /**
     * The main quality check. On 500 random cabs of 2 to 7 stops, compare against the
     * true optimum found by brute force. The full sequencer must never beat the optimum
     * (that would mean a bug in the cost arithmetic), never lose to plain 2-opt or to
     * nearest-neighbour, and stay close to optimal on average.
     *
     * <p>Measured with seed 42:
     * <ul>
     *   <li>2-opt alone: 412/500 optimal, mean gap 0.84%, worst 23.3%</li>
     *   <li>2-opt plus Or-opt: 471/500 optimal, mean gap 0.19%, worst 12.6%</li>
     * </ul>
     */
    @Test
    void sequencerIsNearOptimalOnRandomCabs() {
        Random rnd = new Random(42);
        int trials = 500;
        Stats twoOptOnly = new Stats();
        Stats full = new Stats();

        for (int t = 0; t < trials; t++) {
            int n = 2 + rnd.nextInt(6);
            List<Stop> stops = Fixtures.randomStops(rnd, n, 15);

            List<Stop> nn = sequencer.nearestNeighbour(OFFICE, stops);
            double nnKm = km(nn);
            double twoOptKm = km(sequencer.twoOpt(OFFICE, nn, null));
            double fullKm = km(sequencer.sequence(OFFICE, stops));
            double optimalKm = km(bruteForce(stops, null));

            assertThat(fullKm).isGreaterThanOrEqualTo(optimalKm - 1e-9);
            assertThat(fullKm).isLessThanOrEqualTo(twoOptKm + 1e-9);
            assertThat(twoOptKm).isLessThanOrEqualTo(nnKm + 1e-9);

            twoOptOnly.add(twoOptKm / optimalKm - 1);
            full.add(fullKm / optimalKm - 1);
        }
        System.out.println("2-opt only     : " + twoOptOnly.summary(trials));
        System.out.println("2-opt + Or-opt : " + full.summary(trials));

        assertThat(full.totalGap / trials).isLessThan(0.005);
        assertThat(full.optimalHits).isGreaterThan(trials * 90 / 100);
    }

    private static final class Stats {
        int optimalHits;
        double totalGap;
        double worstGap;

        void add(double gap) {
            totalGap += gap;
            worstGap = Math.max(worstGap, gap);
            if (gap < 1e-9) {
                optimalHits++;
            }
        }

        String summary(int trials) {
            return String.format("%d/%d optimal, mean gap %.2f%%, worst %.1f%%",
                    optimalHits, trials, totalGap / trials * 100, worstGap * 100);
        }
    }

    private static double km(List<Stop> route) {
        return RouteMetrics.pathKm(TRAVEL, OFFICE, route);
    }

    private static List<Stop> bruteForce(List<Stop> stops, GeoPoint end) {
        List<List<Stop>> perms = new ArrayList<>();
        permute(new ArrayList<>(stops), 0, perms);
        return perms.stream()
                .min((a, b) -> Double.compare(
                        RouteMetrics.pathKm(TRAVEL, OFFICE, a, end), RouteMetrics.pathKm(TRAVEL, OFFICE, b, end)))
                .orElseThrow();
    }

    private static void permute(List<Stop> a, int k, List<List<Stop>> out) {
        if (k == a.size()) {
            out.add(new ArrayList<>(a));
            return;
        }
        for (int i = k; i < a.size(); i++) {
            java.util.Collections.swap(a, k, i);
            permute(a, k + 1, out);
            java.util.Collections.swap(a, k, i);
        }
    }
}
