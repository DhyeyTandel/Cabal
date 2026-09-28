package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;
import static dev.dhyey.cabrouter.routing.Fixtures.TRAVEL;
import static dev.dhyey.cabrouter.routing.Fixtures.cab;
import static dev.dhyey.cabrouter.routing.Fixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Standing time-window preferences (earliestPickup on PICKUP plans, latestDrop on DROP
 * plans): hard constraints enforced everywhere the ride limit is, with the same
 * single-rider exemption (a lone rider always gets a cab; if their window still cannot
 * be met, their stop is flagged {@code windowMissed} instead).
 */
class TimeWindowTest {

    private final RoutePlanner planner = new RoutePlanner(TRAVEL);

    private static Stop withWindow(Stop s, LocalTime earliestPickup, LocalTime latestDrop) {
        return new Stop(s.employeeId(), s.location(), s.escortSensitive(), earliestPickup, latestDrop);
    }

    @Test
    void pickupEarliestBindsInASharedCabOrMovesTheRiderToAnotherCab() {
        // Alone, the far rider's pickup is exactly 08:38, matching her earliest window.
        // The near rider sits on the same bearing, so the sweep would naturally try to
        // share a cab between them; doing so would push the far rider's pickup two
        // minutes earlier, so she must be kept apart from him instead.
        Stop farRider = withWindow(stop(1, 10, 0), LocalTime.of(8, 38), null);
        Stop nearRider = stop(2, 2, 0);
        // An unrelated pair, far south, that should still end up sharing a cab together.
        Stop c = stop(3, -20, 0);
        Stop d = stop(4, -20.3, 0.1);
        RoutingParams params = Fixtures.params(4, 1_000);

        List<PlannedCab> cabs = planner.plan(OFFICE, List.of(farRider, nearRider, c, d), params);

        assertThat(cabs).filteredOn(cab -> cab.stops().size() > 1)
                .allSatisfy(cab -> assertThat(cab.timetable().meetsWindows()).isTrue());

        PlannedCab farCab = cabs.stream().filter(cab -> cab.contains(1L)).findFirst().orElseThrow();
        StopTiming farTiming = farCab.timetable().stopsInDrivingOrder().stream()
                .filter(t -> t.stop().employeeId() == 1L).findFirst().orElseThrow();
        assertThat(farTiming.eta()).isAfterOrEqualTo(LocalDateTime.of(2026, 10, 1, 8, 38));
        assertThat(farTiming.windowMissed()).isFalse();
        // She was kept out of a shared cab with the near rider to respect her window.
        assertThat(farCab.contains(2L)).isFalse();
    }

    @Test
    void dropLatestBindsAcrossMidnight() {
        LocalDateTime departure = LocalDateTime.of(2026, 10, 1, 22, 10);
        ShiftContext shift = new ShiftContext(Direction.DROP, departure, 20, 7);
        LocalTime latest = LocalTime.of(0, 30); // "by 00:30" after a 22:10 departure means the next day.

        // 5 km at 30 km/h: dropped at 22:20, comfortably before 00:30 the next day.
        Stop near = withWindow(stop(1, 5, 0), null, latest);
        // 200 km: dropped around 04:50 the next day, after the 00:30 deadline.
        Stop far = withWindow(stop(2, 200, 0), null, latest);

        Timetable nearTable = Timetable.of(TRAVEL, OFFICE, List.of(near), shift, 2);
        Timetable farTable = Timetable.of(TRAVEL, OFFICE, List.of(far), shift, 2);

        assertThat(nearTable.meetsWindows()).isTrue();
        assertThat(nearTable.stopsInDrivingOrder().get(0).windowMissed()).isFalse();

        assertThat(farTable.meetsWindows()).isFalse();
        assertThat(farTable.stopsInDrivingOrder().get(0).windowMissed()).isTrue();
    }

    @Test
    void roundingIsAppliedBeforeComparingToTheWindow() {
        LocalDateTime officeTime = LocalDateTime.of(2026, 10, 1, 9, 0);
        ShiftContext shift = new ShiftContext(Direction.PICKUP, officeTime, 20, 7);
        Stop point = stop(1, 5, 0);
        MatrixTravelModel travel = new MatrixTravelModel(
                List.of(OFFICE, point.location()),
                new double[][] {{0, 10}, {10, 0}},
                new double[][] {{0, 39.5}, {39.5, 0}});
        // ride = 39.5, + 2 min dwell = 41.5, rounds to 42: ETA = 09:00 - 42 min = 08:18.

        Stop exact = withWindow(point, LocalTime.of(8, 18), null);
        Timetable exactTable = Timetable.of(travel, OFFICE, List.of(exact), shift, 2);
        assertThat(exactTable.stopsInDrivingOrder().get(0).eta()).isEqualTo(officeTime.minusMinutes(42));
        assertThat(exactTable.meetsWindows()).isTrue();

        // The actual pickup is one minute earlier than this window demands.
        Stop oneMinuteLate = withWindow(point, LocalTime.of(8, 19), null);
        Timetable lateTable = Timetable.of(travel, OFFICE, List.of(oneMinuteLate), shift, 2);
        assertThat(lateTable.meetsWindows()).isFalse();
        assertThat(lateTable.stopsInDrivingOrder().get(0).windowMissed()).isTrue();
    }

    @Test
    void loneRiderWithAnImpossibleWindowStillGetsACabButIsFlaggedMissed() {
        // Natural pickup is around 08:18; nobody could honour an earliest of 08:59.
        Stop rider = withWindow(stop(1, 20, 0), LocalTime.of(8, 59), null);
        RoutingParams params = Fixtures.params(4, 1_000);

        List<PlannedCab> cabs = planner.plan(OFFICE, List.of(rider), params);

        assertThat(cabs).hasSize(1);
        PlannedCab cab = cabs.get(0);
        assertThat(cab.contains(1L)).isTrue();
        assertThat(cab.timetable().meetsWindows()).isFalse();
        assertThat(cab.timetable().stopsInDrivingOrder().get(0).windowMissed()).isTrue();
    }

    @Test
    void bestInsertionSkipsAReceiverThatWouldBreakAnExistingRidersWindow() {
        RoutingParams params = Fixtures.params(4, 1_000);
        // Alone, X's pickup is exactly 08:38; her earliest is set to match, so it just holds.
        Stop x = withWindow(stop(1, 10, 0), LocalTime.of(8, 38), null);
        PlannedCab xCab = planner.buildCab(OFFICE, cab(4), List.of(x), params);
        // Y is nearer the office: joining X's cab would push X's pickup two minutes earlier.
        Stop y = stop(2, 2, 0);

        RoutePlanner.Insertion insertion = planner.bestInsertion(OFFICE, List.of(xCab), y, params);

        assertThat(insertion.opensNewCab()).isTrue();
        assertThat(insertion.cab().stops()).extracting(Stop::employeeId).containsExactly(2L);
    }

    @Test
    void dissolveSkipsAReceiverThatWouldBreakItsRidersWindow() {
        RoutingParams params = Fixtures.params(4, 1_000);
        Stop farRider = withWindow(stop(1, 10, 0), LocalTime.of(8, 38), null);
        Stop nearRider = stop(2, 2, 0);
        ShiftPlan plan = ShiftPlan.create(OFFICE, params, new FixedTravel(), List.of(farRider, nearRider));
        // Sanity: the window already keeps them apart from the moment the plan is built.
        assertThat(plan.cabs()).hasSize(2);
        int nearCabNumber = plan.cabs().stream().filter(c -> c.carries(2L)).findFirst().orElseThrow().cabNumber();

        assertThat(plan.dissolveSuggestions()).isEmpty();
        assertThatThrownBy(() -> plan.dissolveCab(nearCabNumber)).isInstanceOf(CannotDissolveException.class);
    }

    @Test
    void escortReorderIsRejectedWhenItWouldBreakARidersWindowOnANightShift() {
        // Natural order (man, woman) drops the man at 23:16. The reorder that would spare
        // an escort (woman, man) drops him at 23:26, missing a 23:20 deadline.
        Stop man = withWindow(stop(1, 8, 0), null, LocalTime.of(23, 20));
        Stop woman = stop(2, 10, 0, true);
        RoutingParams params = new RoutingParams(Fleet.unlimited("CAB", 4), 1_000, 2, Fixtures.NIGHT, 0.25, 48, 0);

        PlannedCab cab = planner.buildCab(OFFICE, cab(4), List.of(man, woman), params);

        assertThat(cab.stops()).extracting(Stop::employeeId).containsExactly(1L, 2L);
        assertThat(cab.escortRequired()).isTrue();
        StopTiming manTiming = cab.timetable().stopsInDrivingOrder().stream()
                .filter(t -> t.stop().employeeId() == 1L).findFirst().orElseThrow();
        assertThat(manTiming.eta()).isEqualTo(LocalDateTime.of(2026, 10, 1, 23, 16));
        assertThat(manTiming.windowMissed()).isFalse();
    }

    @Test
    void fitsMatchesTheOldRideLimitCheckWhenNoStopHasAWindow() {
        Random rnd = new Random(21);
        double[] peak = new double[24];
        Arrays.fill(peak, 1.2);
        peak[8] = 2.4;
        peak[9] = 2.6;
        TrafficProfile traffic = new TrafficProfile(peak);
        double maxRideMinutes = 45;
        for (Direction direction : Direction.values()) {
            ShiftContext shift = new ShiftContext(direction, LocalDateTime.of(2026, 10, 1, 9, 0), 20, 7, traffic);
            for (int trial = 0; trial < 50; trial++) {
                List<Stop> outward = Fixtures.randomStops(rnd, 1 + rnd.nextInt(6), 15);
                boolean oldCheck = Timetable.maxRideMinutes(TRAVEL, OFFICE, outward, shift, 2) <= maxRideMinutes;
                boolean newCheck = Timetable.fits(TRAVEL, OFFICE, outward, shift, 2, maxRideMinutes);
                assertThat(newCheck).isEqualTo(oldCheck);
            }
        }
    }

    /** Always answers with {@link Fixtures#TRAVEL}. */
    private static final class FixedTravel implements TravelModelProvider {

        @Override
        public TravelEstimate forPoints(Collection<GeoPoint> points) {
            return new TravelEstimate(Fixtures.TRAVEL, TravelSource.HAVERSINE);
        }
    }
}
