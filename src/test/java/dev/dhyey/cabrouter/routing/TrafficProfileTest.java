package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;
import static dev.dhyey.cabrouter.routing.Fixtures.TRAVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TrafficProfileTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 1, 0, 0);

    /** Free flow except 1.0 to 3.0 across 08:00 to 09:00, peaking at 3.0 at 09:00. */
    private static TrafficProfile morningPeak() {
        double[] h = new double[24];
        Arrays.fill(h, 1.0);
        h[9] = 3.0;
        return new TrafficProfile(h);
    }

    @Test
    void factorsAreInterpolatedBetweenHoursAndWrapAtMidnight() {
        TrafficProfile p = morningPeak();
        assertThat(p.factorAt(DAY.withHour(8))).isEqualTo(1.0);
        assertThat(p.factorAt(DAY.withHour(8).withMinute(30))).isCloseTo(2.0, offset(1e-9));
        assertThat(p.factorAt(DAY.withHour(9))).isEqualTo(3.0);

        double[] h = new double[24];
        Arrays.fill(h, 1.0);
        h[0] = 2.0;
        assertThat(new TrafficProfile(h).factorAt(DAY.withHour(23).withMinute(30))).isCloseTo(1.5, offset(1e-9));
    }

    @Test
    void rejectsProfilesThatAreNotTwentyFourFactorsOfAtLeastOne() {
        assertThatThrownBy(() -> new TrafficProfile(new double[23])).isInstanceOf(IllegalArgumentException.class);
        double[] h = new double[24];
        Arrays.fill(h, 0.9);
        assertThatThrownBy(() -> new TrafficProfile(h)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The same riders need more cabs at rush hour, because the ride limit binds sooner.
     * The radius matters: within 12 km seats bind at any hour (15 cabs both times); at
     * 15 km the limit binds only at rush hour (21 cabs against 15).
     */
    @Test
    void rushHourNeedsMoreCabsThanLateNight() {
        double[] h = new double[24];
        Arrays.fill(h, 1.2);
        for (int hour = 8; hour <= 10; hour++) {
            h[hour] = 2.0;
        }
        TrafficProfile bengaluruish = new TrafficProfile(h);
        List<Stop> riders = Fixtures.randomStops(new Random(5), 60, 15);

        int rush = cabsFor(riders, new ShiftContext(Direction.PICKUP, DAY.withHour(9).withMinute(30), 20, 7, bengaluruish));
        int night = cabsFor(riders, new ShiftContext(Direction.DROP, DAY.withHour(23), 20, 7, bengaluruish));

        System.out.printf("60 riders, 75-min ride limit: %d cabs at 09:30, %d at 23:00%n", rush, night);
        assertThat(rush).isGreaterThan(night);
    }

    private static int cabsFor(List<Stop> riders, ShiftContext shift) {
        RoutingParams params = new RoutingParams(Fleet.unlimited("CAB", 4), 75, 2, shift, 0.25, 48, 0);
        List<PlannedCab> cabs = new RoutePlanner(TRAVEL).plan(OFFICE, riders, params);
        assertThat(cabs).filteredOn(c -> c.stops().size() > 1)
                .allSatisfy(c -> assertThat(c.maxRideMinutes()).isLessThanOrEqualTo(75));
        return cabs.size();
    }
}
