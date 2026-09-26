package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;
import static dev.dhyey.cabrouter.routing.Fixtures.TRAVEL;
import static dev.dhyey.cabrouter.routing.Fixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class EtaCalculatorTest {

    // Outward: 5 km, then 5 km more. At 30 km/h, that is 10 minutes per leg. Dwell 2.
    private final List<Stop> outward = List.of(stop(1, 5, 0), stop(2, 10, 0));
    private final LocalDateTime nine = LocalDateTime.of(2026, 10, 1, 9, 0);

    @Test
    void pickupWorksBackwardsFromOfficeArrivalAndVisitsTheFarthestStopFirst() {
        List<StopTiming> t = EtaCalculator.compute(TRAVEL, OFFICE, outward, new ShiftContext(Direction.PICKUP, nine, 20, 7), 2);

        assertThat(t).extracting(s -> s.stop().employeeId()).containsExactly(2L, 1L);
        // Stop 1: 2 min dwell + 10 min drive = 08:48.
        // Stop 2: 2 min dwell + 10 min to stop 1 + 2 min dwell + 10 min drive = 08:36.
        assertThat(t.get(0).eta()).isEqualTo(nine.minusMinutes(24));
        assertThat(t.get(1).eta()).isEqualTo(nine.minusMinutes(12));
        assertThat(t.get(0).rideMinutes()).isCloseTo(22, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void dropWorksForwardsFromOfficeDepartureAndVisitsTheNearestStopFirst() {
        List<StopTiming> t = EtaCalculator.compute(TRAVEL, OFFICE, outward, new ShiftContext(Direction.DROP, nine, 20, 7), 2);

        assertThat(t).extracting(s -> s.stop().employeeId()).containsExactly(1L, 2L);
        assertThat(t.get(0).eta()).isEqualTo(nine.plusMinutes(10));
        assertThat(t.get(1).eta()).isEqualTo(nine.plusMinutes(22));
    }
}
