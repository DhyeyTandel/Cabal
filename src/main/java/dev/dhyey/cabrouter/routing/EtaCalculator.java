package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts an outward route into per-stop ETAs in the order the driver visits the stops.
 *
 * <p>PICKUP works backwards from the time the cab must reach the office. DROP works
 * forwards from the time it leaves the office. ETAs are rounded to the minute.
 */
public final class EtaCalculator {

    private EtaCalculator() {
    }

    /**
     * @param shift its office time is, for PICKUP, when the cab must arrive at the office
     *              and, for DROP, when it departs the office
     */
    public static List<StopTiming> compute(TravelModel travel, GeoPoint office, List<Stop> outward,
                                           ShiftContext shift, double dwellMinutes) {
        Direction direction = shift.direction();
        LocalDateTime officeTime = shift.officeTime();
        double[] ride = RouteMetrics.rideMinutes(travel, office, outward, dwellMinutes, shift);
        List<StopTiming> timings = new ArrayList<>(outward.size());

        if (direction == Direction.DROP) {
            for (int k = 0; k < outward.size(); k++) {
                timings.add(new StopTiming(outward.get(k), officeTime.plusMinutes(Math.round(ride[k])), ride[k]));
            }
        } else {
            // The cab reaches the stop, waits dwellMinutes for boarding, then the ride begins.
            for (int k = outward.size() - 1; k >= 0; k--) {
                LocalDateTime eta = officeTime.minusMinutes(Math.round(ride[k] + dwellMinutes));
                timings.add(new StopTiming(outward.get(k), eta, ride[k]));
            }
        }
        return timings;
    }
}
