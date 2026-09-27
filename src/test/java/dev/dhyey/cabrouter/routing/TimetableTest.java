package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;
import static dev.dhyey.cabrouter.routing.Fixtures.TRAVEL;
import static dev.dhyey.cabrouter.routing.Fixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TimetableTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 1, 0, 0);

    // Outward: 5 km, then 5 km more. At 30 km/h, that is 10 minutes per leg. Dwell 2.
    private final List<Stop> outward = List.of(stop(1, 5, 0), stop(2, 10, 0));
    private final LocalDateTime nine = LocalDateTime.of(2026, 10, 1, 9, 0);

    /** Free flow except 1.0 to 3.0 across 08:00 to 09:00, peaking at 3.0 at 09:00. */
    private static TrafficProfile morningPeak() {
        double[] h = new double[24];
        Arrays.fill(h, 1.0);
        h[9] = 3.0;
        return new TrafficProfile(h);
    }

    @Test
    void pickupWorksBackwardsFromOfficeArrivalAndVisitsTheFarthestStopFirst() {
        List<StopTiming> t = Timetable.of(TRAVEL, OFFICE, outward,
                new ShiftContext(Direction.PICKUP, nine, 20, 7), 2).stopsInDrivingOrder();

        assertThat(t).extracting(s -> s.stop().employeeId()).containsExactly(2L, 1L);
        // Stop 1: 2 min dwell + 10 min drive = 08:48.
        // Stop 2: 2 min dwell + 10 min to stop 1 + 2 min dwell + 10 min drive = 08:36.
        assertThat(t.get(0).eta()).isEqualTo(nine.minusMinutes(24));
        assertThat(t.get(1).eta()).isEqualTo(nine.minusMinutes(12));
        assertThat(t.get(0).rideMinutes()).isCloseTo(22, offset(0.1));
    }

    @Test
    void dropWorksForwardsFromOfficeDepartureAndVisitsTheNearestStopFirst() {
        List<StopTiming> t = Timetable.of(TRAVEL, OFFICE, outward,
                new ShiftContext(Direction.DROP, nine, 20, 7), 2).stopsInDrivingOrder();

        assertThat(t).extracting(s -> s.stop().employeeId()).containsExactly(1L, 2L);
        assertThat(t.get(0).eta()).isEqualTo(nine.plusMinutes(10));
        assertThat(t.get(1).eta()).isEqualTo(nine.plusMinutes(22));
    }

    /**
     * A drop leaving at 08:00: the first leg (10 min free flow) starts at 08:00, factor
     * 1.0. After a 2-minute dwell, the second leg starts at 08:12, factor 1.4, so it
     * takes 14 minutes.
     */
    @Test
    void eachDropLegIsTimedAtTheMomentItStarts() {
        List<Stop> legs = List.of(stop(1, 5, 0), stop(2, 10, 0)); // 10 min free flow per leg
        ShiftContext shift = new ShiftContext(Direction.DROP, DAY.withHour(8), 20, 7, morningPeak());

        List<StopTiming> t = Timetable.of(TRAVEL, OFFICE, legs, shift, 2).stopsInDrivingOrder();

        assertThat(t.get(0).rideMinutes()).isCloseTo(10, offset(0.05));
        assertThat(t.get(1).rideMinutes()).isCloseTo(10 + 2 + 10 * 1.4, offset(0.05));
    }

    /**
     * A pickup that must arrive at 09:00: the leg into the office finishes at 09:00,
     * factor 3.0, so it takes 30 minutes. The cab reached stop 1 at 08:28 (30 min drive
     * plus 2 min dwell earlier), so the leg from stop 2 finishes at 08:28, factor about
     * 1.93.
     */
    @Test
    void eachPickupLegIsTimedAtTheMomentItReachesTheOfficeSide() {
        List<Stop> legs = List.of(stop(1, 5, 0), stop(2, 10, 0));
        ShiftContext shift = new ShiftContext(Direction.PICKUP, DAY.withHour(9), 20, 7, morningPeak());

        Timetable timetable = Timetable.of(TRAVEL, OFFICE, legs, shift, 2);

        // Pickup driving order is reversed: stop 2 (farthest) first, then stop 1.
        List<StopTiming> t = timetable.stopsInDrivingOrder();
        assertThat(t.get(1).rideMinutes()).isCloseTo(30, offset(0.05));
        double factorAt0828 = 1.0 + 2.0 * 28 / 60;
        assertThat(t.get(0).rideMinutes()).isCloseTo(30 + 2 + 10 * factorAt0828, offset(0.05));
        assertThat(timetable.maxRideMinutes()).isCloseTo(30 + 2 + 10 * factorAt0828, offset(0.05));
    }

    @Test
    void rideTimesUseTheDirectionTheCabActuallyDrives() {
        Stop a = stop(1, 5, 0);
        Stop b = stop(2, 10, 0);
        List<Stop> legs = List.of(a, b);
        MatrixTravelModel model = new MatrixTravelModel(
                List.of(OFFICE, a.location(), b.location()),
                new double[][] {{0, 5, 10}, {6, 0, 5}, {12, 7, 0}},
                new double[][] {{0, 10, 20}, {4, 0, 10}, {8, 4, 0}});

        // DROP drives outbound: 10 + 10 min, plus one dwell at A.
        assertThat(Timetable.of(model, OFFICE, legs, new ShiftContext(Direction.DROP, nine, 20, 7), 2)
                .maxRideMinutes()).isEqualTo(22);
        // PICKUP drives inbound, B to A to office: 4 + 4 min, plus one dwell at A.
        assertThat(Timetable.of(model, OFFICE, legs, new ShiftContext(Direction.PICKUP, nine, 20, 7), 2)
                .maxRideMinutes()).isEqualTo(10);
    }

    @Test
    void farEndTimeIsTheEtaOfTheFarthestStopForDrop() {
        ShiftContext shift = new ShiftContext(Direction.DROP, DAY.withHour(8), 20, 7, morningPeak());
        Timetable timetable = Timetable.of(TRAVEL, OFFICE, List.of(stop(1, 5, 0), stop(2, 10, 0), stop(3, 15, 0)),
                shift, 2);

        List<StopTiming> t = timetable.stopsInDrivingOrder();
        assertThat(timetable.farEndTime()).isEqualTo(t.get(t.size() - 1).eta());
    }

    @Test
    void farEndTimeIsTheEtaOfTheFarthestStopForPickup() {
        ShiftContext shift = new ShiftContext(Direction.PICKUP, DAY.withHour(9), 20, 7, morningPeak());
        Timetable timetable = Timetable.of(TRAVEL, OFFICE, List.of(stop(1, 5, 0), stop(2, 10, 0), stop(3, 15, 0)),
                shift, 2);

        List<StopTiming> t = timetable.stopsInDrivingOrder();
        assertThat(timetable.farEndTime()).isEqualTo(t.get(0).eta());
    }

    @Test
    void emptyStopListHasNoRideAndThrowsOnFarEndTime() {
        Timetable timetable = Timetable.of(TRAVEL, OFFICE, List.of(), new ShiftContext(Direction.DROP, nine, 20, 7), 2);

        assertThat(timetable.maxRideMinutes()).isEqualTo(0);
        assertThatThrownBy(timetable::farEndTime).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theAllocationFreeMaxRideMatchesTheFullTimetable() {
        Random rnd = new Random(9);
        double[] peak = new double[24];
        Arrays.fill(peak, 1.2);
        peak[8] = 2.4;
        peak[9] = 2.6;
        TrafficProfile traffic = new TrafficProfile(peak);
        for (Direction direction : Direction.values()) {
            ShiftContext shift = new ShiftContext(direction, LocalDateTime.of(2026, 10, 1, 9, 0), 20, 7, traffic);
            for (int trial = 0; trial < 50; trial++) {
                List<Stop> outward = Fixtures.randomStops(rnd, 1 + rnd.nextInt(6), 15);
                assertThat(Timetable.maxRideMinutes(TRAVEL, OFFICE, outward, shift, 2))
                        .isEqualTo(Timetable.of(TRAVEL, OFFICE, outward, shift, 2).maxRideMinutes());
            }
        }
    }
}
