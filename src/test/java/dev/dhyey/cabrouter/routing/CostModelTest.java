package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;
import static dev.dhyey.cabrouter.routing.Fixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Plans minimise cost: each vehicle's trip charge plus its per-km charge, plus guards.
 * Prices here are illustrative, not quotes.
 */
class CostModelTest {

    private final RoutePlanner planner = new RoutePlanner(Fixtures.TRAVEL);

    private static final VehicleType SEDAN = new VehicleType("SEDAN", 4, 800, 14);

    /** Riders in one tight group about 10 km north of the office. */
    private static List<Stop> group(int n) {
        return IntStream.range(0, n).mapToObj(i -> stop(i + 1, 10 + i * 0.1, (i % 3) * 0.2)).toList();
    }

    private static RoutingParams params(Integer suvs, VehicleType suv) {
        return Fixtures.params(new Fleet(List.of(new Fleet.Entry(SEDAN, null), new Fleet.Entry(suv, suvs))), 1_000);
    }

    @Test
    void twoSedansBeatOneSuvWhenTheSuvIsExpensive() {
        VehicleType pricey = new VehicleType("SUV", 6, 2500, 20);

        List<PlannedCab> cabs = planner.plan(OFFICE, group(5), params(null, pricey));

        // One SUV: about 2500 + 20 x 11 km = 2720. Two sedans: about 1600 + 14 x 22 km = 1910.
        assertThat(cabs).extracting(PlannedCab::vehicle).containsExactly(SEDAN, SEDAN);
    }

    @Test
    void oneSuvBeatsTwoSedansWhenItIsCheap() {
        VehicleType fair = new VehicleType("SUV", 6, 1000, 16);

        List<PlannedCab> cabs = planner.plan(OFFICE, group(5), params(null, fair));

        assertThat(cabs).extracting(PlannedCab::vehicle).containsExactly(fair);
    }

    @Test
    void rightSizingPicksTheCheapestVehicleNotTheSmallest() {
        // An odd contract where the bigger vehicle is cheaper per trip.
        VehicleType cheapBig = new VehicleType("SUV", 6, 500, 14);

        List<PlannedCab> cabs = planner.plan(OFFICE, group(3), params(null, cheapBig));

        assertThat(cabs).extracting(PlannedCab::vehicle).containsExactly(cheapBig);
    }

    @Test
    void lateBookingUpgradesAFullSedanToAFreeSuvWhenThatIsCheaperThanAnotherCab() {
        VehicleType suv = new VehicleType("SUV", 6, 1000, 16);
        RoutingParams params = params(1, suv);
        PlannedCab fullSedan = planner.buildCab(OFFICE, SEDAN, group(4), params);

        RoutePlanner.Insertion ins = planner.bestInsertion(OFFICE, List.of(fullSedan), stop(9, 10.2, 0.1), params);

        // Upgrade adds about 1176 - 950 = 226. A new sedan would cost about 800 + 14 x 10 = 940.
        assertThat(ins.opensNewCab()).isFalse();
        assertThat(ins.cab().vehicle()).isEqualTo(suv);
        assertThat(ins.cab().stops()).hasSize(5);
    }

    @Test
    void lateBookingSendsANewSedanWhenTheUpgradeCostsMore() {
        VehicleType pricey = new VehicleType("SUV", 6, 2500, 20);
        RoutingParams params = params(1, pricey);
        PlannedCab fullSedan = planner.buildCab(OFFICE, SEDAN, group(4), params);

        RoutePlanner.Insertion ins = planner.bestInsertion(OFFICE, List.of(fullSedan), stop(9, 10.2, 0.1), params);

        assertThat(ins.opensNewCab()).isTrue();
        assertThat(ins.cab().vehicle()).isEqualTo(SEDAN);
    }

    @Test
    void aGuardAddsToTheCabsCost() {
        RoutingParams params = new RoutingParams(Fleet.unlimited("CAB", 4), 1_000, 2, Fixtures.NIGHT, 0.0, 48, 600);
        PlannedCab guarded = planner.buildCab(OFFICE, new VehicleType("CAB", 4), List.of(stop(1, 5, 0, true)), params);

        assertThat(guarded.escortRequired()).isTrue();
        assertThat(params.cost(guarded))
                .isEqualTo(VehicleType.DEFAULT_COST_PER_TRIP + VehicleType.DEFAULT_COST_PER_KM * guarded.distanceKm() + 600);
    }
}
