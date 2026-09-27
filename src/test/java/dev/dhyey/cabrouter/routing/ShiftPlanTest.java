package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;
import static dev.dhyey.cabrouter.routing.Fixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * ShiftPlan holds the plan-editing rules that used to live behind HTTP + H2 in
 * PlanningService. Plain Java, no Spring: a fake {@link TravelModelProvider} stands in
 * for OSRM/haversine and records what it was asked for.
 */
class ShiftPlanTest {

    private static final ShiftContext DAY_DROP = new ShiftContext(Direction.DROP, LocalDateTime.of(2026, 10, 1, 18, 0), 20, 7);

    @Test
    void createRoutesEveryRiderExactlyOnceWithCabNumbersOneToK() {
        List<Stop> stops = Fixtures.randomStops(new Random(1), 20, 20);
        RoutingParams params = Fixtures.params(4, 1_000);

        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new RecordingTravel(), stops);

        List<ShiftPlan.Cab> cabs = plan.cabs();
        assertThat(cabs).extracting(ShiftPlan.Cab::cabNumber)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, cabs.size()).boxed().toList());
        List<Long> routed = cabs.stream().flatMap(c -> c.stops().stream()).map(t -> t.stop().employeeId()).toList();
        assertThat(routed).containsExactlyInAnyOrderElementsOf(stops.stream().map(Stop::employeeId).toList());

        for (ShiftPlan.Cab cab : cabs) {
            List<LocalDateTime> etas = cab.stops().stream().map(StopTiming::eta).toList();
            assertThat(etas).isSorted();
            assertThat(etas).allSatisfy(eta -> assertThat(eta).isBefore(cab.officeTime()));
        }
    }

    @Test
    void createOnADropPlanReturnsAscendingEtasAfterOfficeTime() {
        List<Stop> stops = Fixtures.randomStops(new Random(2), 15, 20);
        RoutingParams params = new RoutingParams(Fleet.unlimited("CAB", 4), 1_000, 2, DAY_DROP, 0.25, 48, 0);

        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new RecordingTravel(), stops);

        for (ShiftPlan.Cab cab : plan.cabs()) {
            List<LocalDateTime> etas = cab.stops().stream().map(StopTiming::eta).toList();
            assertThat(etas).isSorted();
            assertThat(etas).allSatisfy(eta -> assertThat(eta).isAfter(cab.officeTime()));
        }
    }

    @Test
    void localCancelChangesOnlyTheAffectedCabAndKeepsItsNumberAndVehicle() {
        List<Stop> stops = List.of(stop(1, 10, 0), stop(2, 10.3, 0.2), stop(3, -10, 0), stop(4, -10.3, -0.2));
        RoutingParams params = Fixtures.params(2, 1_000);
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new RecordingTravel(), stops);
        ShiftPlan.Cab northBefore = cabOf(plan, 1);
        ShiftPlan.Cab southBefore = cabOf(plan, 3);
        assertThat(northBefore.stops()).hasSize(2); // sanity: the pair really does share a cab

        ShiftPlan after = plan.cancel(1, ReplanStrategy.LOCAL);

        assertThat(after.cabs()).hasSize(2);
        ShiftPlan.Cab northAfter = cabByNumber(after, northBefore.cabNumber());
        assertThat(northAfter.cabNumber()).isEqualTo(northBefore.cabNumber());
        assertThat(northAfter.vehicle()).isEqualTo(northBefore.vehicle());
        assertThat(northAfter.stops()).extracting(t -> t.stop().employeeId()).containsExactly(2L);
        assertThat(cabByNumber(after, southBefore.cabNumber())).isEqualTo(southBefore);
    }

    @Test
    void localCancelOfASoloRiderRemovesTheCabWithoutRenumberingOthers() {
        List<Stop> stops = List.of(stop(1, 10, 0), stop(2, 10.3, 0.2), stop(3, -10, 0), stop(4, -10.3, -0.2), stop(5, 0, 20));
        RoutingParams params = Fixtures.params(2, 1_000);
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new RecordingTravel(), stops);
        ShiftPlan.Cab soloBefore = cabOf(plan, 5);
        ShiftPlan.Cab northBefore = cabOf(plan, 1);
        ShiftPlan.Cab southBefore = cabOf(plan, 3);
        assertThat(soloBefore.stops()).hasSize(1); // sanity: employee 5 really is alone

        ShiftPlan after = plan.cancel(5, ReplanStrategy.LOCAL);

        assertThat(after.cabs()).hasSize(2);
        assertThat(after.cabs()).extracting(ShiftPlan.Cab::cabNumber)
                .containsExactlyInAnyOrder(northBefore.cabNumber(), southBefore.cabNumber());
        assertThat(cabByNumber(after, northBefore.cabNumber())).isEqualTo(northBefore);
        assertThat(cabByNumber(after, southBefore.cabNumber())).isEqualTo(southBefore);
    }

    @Test
    void localCancelAsksTheTravelModelOnlyForThatCabsRemainingStops() {
        List<Stop> stops = List.of(stop(1, 10, 0), stop(2, 10.3, 0.2), stop(3, -10, 0), stop(4, -10.3, -0.2));
        RoutingParams params = Fixtures.params(2, 1_000);
        RecordingTravel travel = new RecordingTravel();
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, travel, stops);

        plan.cancel(1, ReplanStrategy.LOCAL);

        List<GeoPoint> lastRequest = travel.requests.get(travel.requests.size() - 1);
        assertThat(lastRequest).containsExactlyInAnyOrder(OFFICE, stop(2, 10.3, 0.2).location());
    }

    @Test
    void fullCancelReRoutesEveryoneElseWithCabNumbersOneToK() {
        List<Stop> stops = Fixtures.randomStops(new Random(3), 20, 20);
        ShiftPlan plan = ShiftPlan.create(OFFICE, Fixtures.params(4, 1_000), new RecordingTravel(), stops);
        long cancelled = stops.get(0).employeeId();

        ShiftPlan after = plan.cancel(cancelled, ReplanStrategy.FULL);

        List<Long> routed = after.cabs().stream().flatMap(c -> c.stops().stream())
                .map(t -> t.stop().employeeId()).toList();
        assertThat(routed).doesNotContain(cancelled).hasSize(stops.size() - 1);
        assertThat(after.cabs()).extracting(ShiftPlan.Cab::cabNumber)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, after.cabs().size()).boxed().toList());
    }

    @Test
    void addJoinsANearbyCabWithASpareSeatKeepingItsNumber() {
        List<Stop> stops = List.of(stop(1, 10, 0), stop(2, 10.3, 0.2), stop(3, -10, 0));
        RoutingParams params = Fixtures.params(4, 1_000);
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new RecordingTravel(), stops);
        int northCabNumber = cabOf(plan, 1).cabNumber();

        ShiftPlan after = plan.add(stop(4, 10.4, 0.1));

        assertThat(after.cabs()).hasSize(plan.cabs().size());
        ShiftPlan.Cab northAfter = cabByNumber(after, northCabNumber);
        assertThat(northAfter.stops()).extracting(t -> t.stop().employeeId()).contains(4L);
    }

    @Test
    void addOpensANewCabNumberedAfterTheHighestExistingNumberEvenAcrossAGap() {
        // Capacity 1 means every rider gets a solo cab, and no existing cab ever has a free seat.
        RoutingParams params = Fixtures.params(1, 1_000);
        List<Stop> stops = List.of(stop(1, 10, 0), stop(2, -10, 0), stop(3, 0, 15));
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new RecordingTravel(), stops);
        int middleCabNumber = cabOf(plan, 2).cabNumber();

        ShiftPlan withGap = plan.cancel(2, ReplanStrategy.LOCAL);
        assertThat(withGap.cabs()).extracting(ShiftPlan.Cab::cabNumber).doesNotContain(middleCabNumber);
        int maxNumber = withGap.cabs().stream().mapToInt(ShiftPlan.Cab::cabNumber).max().orElseThrow();

        ShiftPlan after = withGap.add(stop(4, 0, -15));

        assertThat(after.cabs()).extracting(ShiftPlan.Cab::cabNumber)
                .doesNotContain(middleCabNumber)
                .contains(maxNumber + 1);
    }

    @Test
    void cancelOfAnAbsentRiderThrowsNotOnPlanException() {
        ShiftPlan plan = ShiftPlan.create(OFFICE, Fixtures.params(4, 1_000), new RecordingTravel(), List.of(stop(1, 10, 0)));

        assertThatThrownBy(() -> plan.cancel(99, ReplanStrategy.LOCAL))
                .isInstanceOf(NotOnPlanException.class)
                .satisfies(e -> assertThat(((NotOnPlanException) e).employeeId()).isEqualTo(99L));
    }

    @Test
    void addOfAPresentRiderThrowsAlreadyOnPlanException() {
        ShiftPlan plan = ShiftPlan.create(OFFICE, Fixtures.params(4, 1_000), new RecordingTravel(), List.of(stop(1, 10, 0)));

        assertThatThrownBy(() -> plan.add(stop(1, 10, 0)))
                .isInstanceOf(AlreadyOnPlanException.class)
                .satisfies(e -> assertThat(((AlreadyOnPlanException) e).employeeId()).isEqualTo(1L));
    }

    @Test
    void restoreRoundTripsAPlansCabs() {
        List<Stop> stops = Fixtures.randomStops(new Random(4), 12, 20);
        RoutingParams params = Fixtures.params(4, 1_000);
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new RecordingTravel(), stops);

        ShiftPlan restored = ShiftPlan.restore(OFFICE, params, new RecordingTravel(), plan.cabs());

        assertThat(restored.cabs()).isEqualTo(plan.cabs());
    }

    private static ShiftPlan.Cab cabOf(ShiftPlan plan, long employeeId) {
        return plan.cabs().stream().filter(c -> c.carries(employeeId)).findFirst()
                .orElseThrow(() -> new AssertionError("employee " + employeeId + " not on the plan"));
    }

    private static ShiftPlan.Cab cabByNumber(ShiftPlan plan, int number) {
        return plan.cabs().stream().filter(c -> c.cabNumber() == number).findFirst()
                .orElseThrow(() -> new AssertionError("no cab numbered " + number));
    }

    /** Always answers with {@link Fixtures#TRAVEL}, and remembers the points it was asked for. */
    private static final class RecordingTravel implements TravelModelProvider {

        final List<List<GeoPoint>> requests = new ArrayList<>();

        @Override
        public TravelEstimate forPoints(Collection<GeoPoint> points) {
            requests.add(List.copyOf(points));
            return new TravelEstimate(Fixtures.TRAVEL, TravelSource.HAVERSINE);
        }
    }
}
