package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;
import static dev.dhyey.cabrouter.routing.Fixtures.TRAVEL;
import static dev.dhyey.cabrouter.routing.Fixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoutePlannerTest {

    private final RoutePlanner planner = new RoutePlanner(TRAVEL);

    @ParameterizedTest
    @CsvSource({"1, 4", "7, 4", "60, 4", "200, 4", "200, 6", "31, 1"})
    void everyoneIsRoutedExactlyOnceAndNoCabIsOverCapacity(int n, int capacity) {
        List<Stop> stops = Fixtures.randomStops(new Random(n), n, 20);

        List<PlannedCab> cabs = planner.plan(OFFICE, stops, Fixtures.params(capacity, 1_000));

        List<Long> routed = cabs.stream().flatMap(c -> c.stops().stream()).map(Stop::employeeId).toList();
        assertThat(routed).hasSize(n).doesNotHaveDuplicates();
        assertThat(cabs).allSatisfy(c -> assertThat(c.stops()).hasSizeBetween(1, capacity));
    }

    @Test
    void withNoRideLimitSweepUsesTheMinimumNumberOfCabs() {
        List<Stop> stops = Fixtures.randomStops(new Random(7), 50, 20);

        List<PlannedCab> cabs = planner.plan(OFFICE, stops, Fixtures.params(4, 10_000));

        assertThat(cabs).hasSize(13); // ceil(50 / 4)
    }

    @Test
    void rideLimitIsRespectedWheneverACabHasMoreThanOnePassenger() {
        List<Stop> stops = Fixtures.randomStops(new Random(3), 120, 25);
        RoutingParams params = Fixtures.params(6, 60);

        List<PlannedCab> cabs = planner.plan(OFFICE, stops, params);

        assertThat(cabs).filteredOn(c -> c.stops().size() > 1)
                .allSatisfy(c -> assertThat(c.maxRideMinutes()).isLessThanOrEqualTo(60));
        // A tight ride limit costs cabs compared with packing every seat.
        assertThat(cabs.size()).isGreaterThan(20);
    }

    @Test
    void neighboursShareACabAndOppositeSidesDoNot() {
        List<Stop> stops = List.of(
                stop(1, 10, 0), stop(2, 10.5, 0.3), // north
                stop(3, -10, 0), stop(4, -10.4, -0.2)); // south

        List<PlannedCab> cabs = planner.plan(OFFICE, stops, Fixtures.params(2, 1_000));

        Set<Set<Long>> groups = cabs.stream()
                .map(c -> c.stops().stream().map(Stop::employeeId).collect(Collectors.toSet()))
                .collect(Collectors.toSet());
        assertThat(groups).containsExactlyInAnyOrder(Set.of(1L, 2L), Set.of(3L, 4L));
    }

    /**
     * Sweep puts someone near the office in the same cab as people far out on the same
     * bearing. The inter-route pass should recover that distance without adding cabs
     * or escort flags.
     */
    @Test
    void interRouteImprovementShortensSweepWithoutAddingCabs() {
        double sweptKm = 0;
        double improvedKm = 0;
        for (int seed = 1; seed <= 20; seed++) {
            List<Stop> stops = Fixtures.randomStops(new Random(seed), 60, 20);
            RoutingParams params = Fixtures.params(4, 75);

            List<PlannedCab> swept = planner.sweepOnly(OFFICE, stops, params);
            List<PlannedCab> improved = planner.plan(OFFICE, stops, params);

            assertThat(improved.size()).isLessThanOrEqualTo(swept.size());
            assertThat(totalKm(improved)).isLessThanOrEqualTo(totalKm(swept) + 1e-9);
            assertThat(improved).filteredOn(c -> c.stops().size() > 1)
                    .allSatisfy(c -> assertThat(c.maxRideMinutes()).isLessThanOrEqualTo(75));
            sweptKm += totalKm(swept);
            improvedKm += totalKm(improved);
        }
        double saving = 1 - improvedKm / sweptKm;
        System.out.printf("inter-route pass saves %.1f%% of sweep distance%n", saving * 100);
        assertThat(saving).isGreaterThan(0.03);
    }

    private static double totalKm(List<PlannedCab> cabs) {
        return cabs.stream().mapToDouble(PlannedCab::distanceKm).sum();
    }

    @Test
    void rejectsDuplicateEmployees() {
        assertThatThrownBy(() -> planner.plan(OFFICE, List.of(stop(1, 1, 1), stop(1, 2, 2)), Fixtures.params(4, 90)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Night escort rule

    private static RoutingParams night(double tolerance) {
        return new RoutingParams(4, 1_000, 2, true, tolerance, 48);
    }

    @Test
    void atNightAWomanIsNotLeftAsTheFarthestStopWhenAReorderIsCheap() {
        Stop man = stop(1, 8, 0);
        Stop woman = stop(2, 10, 0, true);
        // Natural order: office, man, woman (10 km). Safe order: office, woman, man (12 km, +20%).

        PlannedCab cab = planner.buildCab(OFFICE, List.of(man, woman), night(0.25));

        assertThat(cab.stops()).extracting(Stop::employeeId).containsExactly(2L, 1L);
        assertThat(cab.escortRequired()).isFalse();
    }

    @Test
    void atNightTheCabIsFlaggedForAGuardWhenTheReorderDetourIsTooLong() {
        Stop man = stop(1, 8, 0);
        Stop woman = stop(2, 10, 0, true);

        PlannedCab cab = planner.buildCab(OFFICE, List.of(man, woman), night(0.10));

        assertThat(cab.stops()).extracting(Stop::employeeId).containsExactly(1L, 2L);
        assertThat(cab.escortRequired()).isTrue();
    }

    @Test
    void anAllWomenCabAtNightAlwaysNeedsAGuard() {
        PlannedCab cab = planner.buildCab(OFFICE, List.of(stop(1, 5, 0, true), stop(2, 6, 0, true)), night(1.0));

        assertThat(cab.escortRequired()).isTrue();
    }

    @Test
    void escortRuleDoesNotApplyToDayShifts() {
        PlannedCab cab = planner.buildCab(OFFICE, List.of(stop(1, 8, 0), stop(2, 10, 0, true)), Fixtures.params(4, 1_000));

        assertThat(cab.stops()).extracting(Stop::employeeId).containsExactly(1L, 2L);
        assertThat(cab.escortRequired()).isFalse();
    }

    // Cheapest insertion

    @Test
    void lateBookingJoinsTheNearbyCabWithASpareSeat() {
        RoutingParams params = Fixtures.params(4, 1_000);
        PlannedCab north = planner.buildCab(OFFICE, List.of(stop(1, 10, 0), stop(2, 11, 0)), params);
        PlannedCab south = planner.buildCab(OFFICE, List.of(stop(3, -10, 0), stop(4, -11, 0)), params);

        RoutePlanner.Insertion ins = planner.bestInsertion(OFFICE, List.of(north, south), stop(5, 10.5, 0.5), params);

        assertThat(ins.opensNewCab()).isFalse();
        assertThat(ins.cabIndex()).isZero();
        assertThat(ins.cab().stops()).extracting(Stop::employeeId).containsExactlyInAnyOrder(1L, 2L, 5L);
    }

    @Test
    void lateBookingOpensANewCabWhenEveryCabIsFull() {
        RoutingParams params = Fixtures.params(2, 1_000);
        PlannedCab full = planner.buildCab(OFFICE, List.of(stop(1, 10, 0), stop(2, 11, 0)), params);

        RoutePlanner.Insertion ins = planner.bestInsertion(OFFICE, List.of(full), stop(3, 10.5, 0), params);

        assertThat(ins.opensNewCab()).isTrue();
        assertThat(ins.cab().stops()).extracting(Stop::employeeId).containsExactly(3L);
    }

    @Test
    void lateBookingSkipsACabItWouldPushOverTheRideLimit() {
        // At 30 km/h, a cab going 20 km north rides about 40 minutes. Adding a stop 30 km
        // south would push its ride over the 70-minute limit.
        RoutingParams params = Fixtures.params(4, 70);
        PlannedCab north = planner.buildCab(OFFICE, List.of(stop(1, 20, 0)), params);

        RoutePlanner.Insertion ins = planner.bestInsertion(OFFICE, List.of(north), stop(2, -30, 0), params);

        assertThat(ins.opensNewCab()).isTrue();
    }
}
