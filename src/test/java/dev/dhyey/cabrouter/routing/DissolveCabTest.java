package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;
import static dev.dhyey.cabrouter.routing.Fixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Dissolving a cab moves every one of its riders into other existing cabs so the vehicle
 * is not needed, without opening a replacement. Plans here are built the same way as
 * {@link ShiftPlanTest}: real {@code create}/{@code cancel} calls against a fake travel
 * provider, thinning cabs out the way a dispatcher would see them thin out in practice.
 */
class DissolveCabTest {

    @Test
    void thinnedCabNextToACabWithRoomIsSuggestedAndDissolves() {
        // North: 3 of 4 seats used, one spare. South: starts full, then three cancel,
        // leaving one rider. East is far away and unrelated: it must come out untouched.
        List<Stop> stops = new ArrayList<>();
        stops.add(stop(1, 10, 0));
        stops.add(stop(2, 10.1, 0.1));
        stops.add(stop(3, 9.9, -0.1));
        stops.add(stop(4, -10, 0));
        stops.add(stop(5, -10.1, 0.1));
        stops.add(stop(6, -9.9, -0.1));
        stops.add(stop(7, -10, 0.2));
        stops.add(stop(8, 0, 120));
        stops.add(stop(9, 0.1, 120.1));
        stops.add(stop(10, -0.1, 119.9));
        stops.add(stop(11, 0.05, 120.2));
        RoutingParams params = Fixtures.params(4, 1_000);
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new FixedTravel(), stops);

        int northNumber = cabOf(plan, 1).cabNumber();
        int southNumber = cabOf(plan, 4).cabNumber();
        ShiftPlan.Cab eastBefore = cabOf(plan, 8);

        plan = plan.cancel(5, ReplanStrategy.LOCAL).cancel(6, ReplanStrategy.LOCAL).cancel(7, ReplanStrategy.LOCAL);
        assertThat(cabOf(plan, 4).stops()).hasSize(1); // sanity: south really is thinned out

        List<ShiftPlan.DissolveSuggestion> suggestions = plan.dissolveSuggestions();
        Optional<ShiftPlan.DissolveSuggestion> south = suggestions.stream()
                .filter(s -> s.cabNumber() == southNumber).findFirst();
        assertThat(south).isPresent();
        assertThat(south.get().saving()).isGreaterThan(0);
        assertThat(south.get().ridersMoved()).isEqualTo(1);
        assertThat(south.get().receivingCabNumbers()).containsExactly(northNumber);

        ShiftPlan after = plan.dissolveCab(southNumber);

        assertThat(after.cabs()).extracting(ShiftPlan.Cab::cabNumber).doesNotContain(southNumber);
        assertThat(after.cabs()).hasSize(plan.cabs().size() - 1);
        ShiftPlan.Cab northAfter = cabByNumber(after, northNumber);
        assertThat(northAfter.stops()).extracting(t -> t.stop().employeeId()).contains(4L);
        assertThat(cabByNumber(after, eastBefore.cabNumber())).isEqualTo(eastBefore);
        // No cab number is ever reused, in this plan or a later one built on it.
        ShiftPlan afterAdd = after.add(stop(12, -10, 0));
        assertThat(afterAdd.cabs()).extracting(ShiftPlan.Cab::cabNumber).doesNotContain(southNumber);
    }

    @Test
    void noRoomNearbyMeansNoSuggestionAndAnException() {
        // Both cabs start full at capacity 4 with a single vehicle type: no free seat and
        // no bigger vehicle to upgrade into anywhere. South is then thinned to one rider.
        List<Stop> stops = List.of(
                stop(1, 10, 0), stop(2, 10.1, 0.1), stop(3, 9.9, -0.1), stop(4, 10, 0.2),
                stop(5, -10, 0), stop(6, -10.1, 0.1), stop(7, -9.9, -0.1), stop(8, -10, 0.2));
        RoutingParams params = Fixtures.params(4, 1_000);
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new FixedTravel(), stops);
        int southNumber = cabOf(plan, 5).cabNumber();

        plan = plan.cancel(6, ReplanStrategy.LOCAL).cancel(7, ReplanStrategy.LOCAL).cancel(8, ReplanStrategy.LOCAL);
        assertThat(cabOf(plan, 5).stops()).hasSize(1);

        assertThat(plan.dissolveSuggestions()).isEmpty();
        ShiftPlan finalPlan = plan;
        assertThatThrownBy(() -> finalPlan.dissolveCab(southNumber)).isInstanceOf(CannotDissolveException.class);
    }

    @Test
    void aReceiverThatWouldBreakTheRideLimitIsRejected() {
        // North is close in (short rides); south, once thinned to one rider, is 60 km
        // out. Merging them would push that rider's ride time well past the limit.
        List<Stop> stops = List.of(
                stop(1, 2, 0), stop(2, 2.1, 0.1), stop(3, 1.9, -0.1),
                stop(4, -60, 0), stop(5, -60.1, 0.1), stop(6, -59.9, -0.1), stop(7, -60, 0.2));
        RoutingParams params = Fixtures.params(4, 50);
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new FixedTravel(), stops);
        int southNumber = cabOf(plan, 4).cabNumber();

        plan = plan.cancel(5, ReplanStrategy.LOCAL).cancel(6, ReplanStrategy.LOCAL).cancel(7, ReplanStrategy.LOCAL);
        assertThat(cabOf(plan, 4).stops()).hasSize(1);

        assertThat(plan.dissolveSuggestions()).isEmpty();
        ShiftPlan finalPlan = plan;
        assertThatThrownBy(() -> finalPlan.dissolveCab(southNumber)).isInstanceOf(CannotDissolveException.class);
    }

    @Test
    void aDetourThatCostsMoreThanItSavesIsNotSuggested() {
        // Cheap per-trip, expensive per-km: removing south's small trip charge is not
        // worth the long detour north would need to reach it.
        VehicleType cab = new VehicleType("CAB", 4, 10, 200);
        RoutingParams params = Fixtures.params(new Fleet(List.of(new Fleet.Entry(cab, null))), 1_000);
        List<Stop> stops = List.of(
                stop(1, 10, 0), stop(2, 10.1, 0.1), stop(3, 9.9, -0.1),
                stop(4, -10, 0), stop(5, -10.1, 0.1), stop(6, -9.9, -0.1), stop(7, -10, 0.2));
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new FixedTravel(), stops);
        int southNumber = cabOf(plan, 4).cabNumber();

        plan = plan.cancel(5, ReplanStrategy.LOCAL).cancel(6, ReplanStrategy.LOCAL).cancel(7, ReplanStrategy.LOCAL);
        assertThat(cabOf(plan, 4).stops()).hasSize(1);

        List<ShiftPlan.DissolveSuggestion> suggestions = plan.dissolveSuggestions();
        assertThat(suggestions).noneMatch(s -> s.cabNumber() == southNumber);
        ShiftPlan finalPlan = plan;
        assertThatThrownBy(() -> finalPlan.dissolveCab(southNumber)).isInstanceOf(CannotDissolveException.class);
    }

    @Test
    void aReceiverThatWouldNewlyNeedAnEscortIsRejected() {
        // North ends up with one non-sensitive rider 8 km out; south ends up with one
        // escort-sensitive rider 12 km out on a different bearing. On a night shift with
        // a tight detour tolerance, folding south's rider into north leaves her as the
        // farthest stop with no cheap reorder available, so north would newly need a guard.
        List<Stop> stops = List.of(
                stop(1, 8, 0), stop(2, 8.1, 0.05), stop(3, 7.9, -0.05), stop(4, 8, 0.1),
                stop(5, 0, 12, true), stop(6, 0.05, 12.05), stop(7, -0.05, 11.95), stop(8, 0.05, 11.9));
        RoutingParams params = new RoutingParams(Fleet.unlimited("CAB", 4), 1_000, 2, Fixtures.NIGHT, 0.10, 48, 0);
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new FixedTravel(), stops);
        int northNumber = cabOf(plan, 1).cabNumber();
        int southNumber = cabOf(plan, 5).cabNumber();

        plan = plan.cancel(2, ReplanStrategy.LOCAL).cancel(3, ReplanStrategy.LOCAL).cancel(4, ReplanStrategy.LOCAL)
                .cancel(6, ReplanStrategy.LOCAL).cancel(7, ReplanStrategy.LOCAL).cancel(8, ReplanStrategy.LOCAL);
        assertThat(cabOf(plan, 1).stops()).hasSize(1);
        assertThat(cabOf(plan, 5).stops()).hasSize(1);
        assertThat(cabByNumber(plan, northNumber).escortRequired()).isFalse();

        List<ShiftPlan.DissolveSuggestion> suggestions = plan.dissolveSuggestions();
        assertThat(suggestions).noneMatch(s -> s.cabNumber() == southNumber);
        ShiftPlan finalPlan = plan;
        assertThatThrownBy(() -> finalPlan.dissolveCab(southNumber)).isInstanceOf(CannotDissolveException.class);
    }

    /**
     * The fleet has a single scarce SUV, big enough to seat only one cluster on its own.
     * The receiving cab is planned separately (real {@code create} calls each), otherwise
     * the planner would just fold both clusters into one cab from the start, since a
     * single 1000-per-trip charge beats two. Once combined into one plan and dissolved,
     * the released SUV is what lets the full receiver upgrade to take every rider.
     */
    @Test
    void dissolvingCanUpgradeAFullReceiverWithTheFreedVehicle() {
        VehicleType sedan = new VehicleType("SEDAN", 2);
        VehicleType suv = new VehicleType("SUV", 5);
        Fleet fleet = new Fleet(List.of(new Fleet.Entry(sedan, null), new Fleet.Entry(suv, 1)));
        RoutingParams params = Fixtures.params(fleet, 1_000);

        List<Stop> cStops = List.of(stop(1, 10, 0), stop(2, 10.1, 0.1), stop(3, 9.9, -0.1));
        List<Stop> rStops = List.of(stop(4, -10, 0), stop(5, -10.1, -0.1));
        ShiftPlan cOnly = ShiftPlan.create(OFFICE, params, new FixedTravel(), cStops);
        ShiftPlan rOnly = ShiftPlan.create(OFFICE, params, new FixedTravel(), rStops);
        assertThat(cOnly.cabs()).hasSize(1);
        assertThat(rOnly.cabs()).hasSize(1);
        ShiftPlan.Cab c = cOnly.cabs().get(0);
        ShiftPlan.Cab r = rOnly.cabs().get(0);
        assertThat(c.vehicle()).isEqualTo(suv); // forced: 3 riders do not fit a 2-seat sedan
        assertThat(r.vehicle()).isEqualTo(sedan);
        assertThat(r.stops()).hasSize(2); // sanity: the sedan really is full

        int cNumber = c.cabNumber();
        int rNumber = r.cabNumber() == cNumber ? r.cabNumber() + 1 : r.cabNumber();
        ShiftPlan.Cab rRenumbered = rNumber == r.cabNumber() ? r
                : new ShiftPlan.Cab(rNumber, r.vehicle(), r.stops(), r.distanceKm(), r.maxRideMinutes(),
                        r.escortRequired(), r.cost(), r.officeTime(), r.timedBy());
        ShiftPlan plan = ShiftPlan.restore(OFFICE, params, new FixedTravel(), List.of(c, rRenumbered));

        List<ShiftPlan.DissolveSuggestion> suggestions = plan.dissolveSuggestions();
        Optional<ShiftPlan.DissolveSuggestion> found = suggestions.stream()
                .filter(s -> s.cabNumber() == cNumber).findFirst();
        assertThat(found).isPresent();
        assertThat(found.get().receivingCabNumbers()).containsExactly(rNumber);

        ShiftPlan after = plan.dissolveCab(cNumber);

        assertThat(after.cabs()).hasSize(1);
        ShiftPlan.Cab merged = after.cabs().get(0);
        assertThat(merged.cabNumber()).isEqualTo(rNumber);
        assertThat(merged.vehicle()).isEqualTo(suv);
        assertThat(merged.stops()).extracting(t -> t.stop().employeeId())
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void unknownCabNumberThrowsNoSuchCabException() {
        ShiftPlan plan = ShiftPlan.create(OFFICE, Fixtures.params(4, 1_000), new FixedTravel(), List.of(stop(1, 10, 0)));

        assertThatThrownBy(() -> plan.dissolveCab(999))
                .isInstanceOf(NoSuchCabException.class)
                .satisfies(e -> assertThat(((NoSuchCabException) e).cabNumber()).isEqualTo(999));
    }

    @Test
    void bestInsertionIntoExistingReturnsEmptyWhereBestInsertionWouldOpenANewCab() {
        RoutePlanner planner = new RoutePlanner(Fixtures.TRAVEL);
        RoutingParams params = Fixtures.params(2, 1_000);
        PlannedCab full = planner.buildCab(OFFICE, Fixtures.cab(2), List.of(stop(1, 10, 0), stop(2, 11, 0)), params);

        RoutePlanner.Insertion viaBestInsertion = planner.bestInsertion(OFFICE, List.of(full), stop(3, 10.5, 0), params);
        assertThat(viaBestInsertion.opensNewCab()).isTrue();

        Optional<RoutePlanner.Insertion> viaExisting =
                planner.bestInsertionIntoExisting(OFFICE, List.of(full), stop(3, 10.5, 0), params);
        assertThat(viaExisting).isEmpty();
    }

    /**
     * Regression: vehicles in use by cabs outside the dissolving cab's neighbourhood are
     * not free. Here the fleet's only SUV drives a cab far to the east, and every cab
     * near the one being dissolved is a full sedan. The rider has nowhere to go; an
     * "upgrade" into the SUV would put one vehicle on two cabs.
     */
    @Test
    void aVehicleInUseOutsideTheNeighbourhoodIsNotOfferedAsAnUpgrade() {
        VehicleType sedan = new VehicleType("SEDAN", 2);
        VehicleType suv = new VehicleType("SUV", 5);
        Fleet fleet = new Fleet(List.of(new Fleet.Entry(sedan, null), new Fleet.Entry(suv, 1)));
        RoutingParams params = Fixtures.params(fleet, 1_000);

        List<ShiftPlan> parts = new java.util.ArrayList<>();
        // The cab to dissolve: one rider, 10 km north.
        parts.add(ShiftPlan.create(OFFICE, params, new FixedTravel(), List.of(stop(1, 10, 0))));
        // Six full sedans around it, the nearest six cabs.
        long id = 10;
        for (int i = 0; i < 6; i++) {
            double north = 10 + (i - 3) * 0.6;
            double east = (i % 2 == 0 ? 0.4 : -0.4);
            parts.add(ShiftPlan.create(OFFICE, params, new FixedTravel(),
                    List.of(stop(id++, north, east), stop(id++, north + 0.1, east + 0.1))));
        }
        // The only SUV, far away to the east.
        parts.add(ShiftPlan.create(OFFICE, params, new FixedTravel(),
                List.of(stop(90, 0, 40), stop(91, 0.1, 40.1), stop(92, -0.1, 39.9))));

        List<ShiftPlan.Cab> cabs = new java.util.ArrayList<>();
        int number = 1;
        for (ShiftPlan part : parts) {
            assertThat(part.cabs()).hasSize(1);
            ShiftPlan.Cab c = part.cabs().get(0);
            cabs.add(new ShiftPlan.Cab(number++, c.vehicle(), c.stops(), c.distanceKm(), c.maxRideMinutes(),
                    c.escortRequired(), c.cost(), c.officeTime(), c.timedBy()));
        }
        assertThat(cabs.get(cabs.size() - 1).vehicle()).isEqualTo(suv);
        assertThat(cabs.subList(1, 7)).allSatisfy(c -> {
            assertThat(c.vehicle()).isEqualTo(sedan);
            assertThat(c.stops()).hasSize(2);
        });
        ShiftPlan plan = ShiftPlan.restore(OFFICE, params, new FixedTravel(), cabs);

        assertThat(plan.dissolveSuggestions()).noneMatch(s -> s.cabNumber() == 1);
        assertThatThrownBy(() -> plan.dissolveCab(1)).isInstanceOf(CannotDissolveException.class);
    }

    private static ShiftPlan.Cab cabOf(ShiftPlan plan, long employeeId) {
        return plan.cabs().stream().filter(c -> c.carries(employeeId)).findFirst()
                .orElseThrow(() -> new AssertionError("employee " + employeeId + " not on the plan"));
    }

    private static ShiftPlan.Cab cabByNumber(ShiftPlan plan, int number) {
        return plan.cabs().stream().filter(c -> c.cabNumber() == number).findFirst()
                .orElseThrow(() -> new AssertionError("no cab numbered " + number));
    }

    /** Always answers with {@link Fixtures#TRAVEL}, like {@code ShiftPlanTest}'s fake provider. */
    private static final class FixedTravel implements TravelModelProvider {

        @Override
        public TravelEstimate forPoints(Collection<GeoPoint> points) {
            return new TravelEstimate(Fixtures.TRAVEL, TravelSource.HAVERSINE);
        }
    }
}
