package dev.dhyey.cabrouter.routing;

import java.util.List;

/** Distance and ride-time arithmetic over an outward path starting at the office. */
public final class RouteMetrics {

    private RouteMetrics() {
    }

    /** Length of office -> stops[0] -> ... -> stops[n-1], then on to {@code end} if given. */
    public static double pathKm(TravelModel travel, GeoPoint origin, List<Stop> stops, GeoPoint end) {
        double km = 0;
        GeoPoint prev = origin;
        for (Stop s : stops) {
            km += travel.distanceKm(prev, s.location());
            prev = s.location();
        }
        if (end != null) {
            km += travel.distanceKm(prev, end);
        }
        return km;
    }

    public static double pathKm(TravelModel travel, GeoPoint origin, List<Stop> stops) {
        return pathKm(travel, origin, stops, null);
    }

    /**
     * Ride minutes for each stop: drive time between the stop and the office plus a dwell
     * for every stop in between.
     *
     * <p>Two things make a leg's time depend on the shift:
     * <ul>
     *   <li><b>Direction.</b> Drive times may be directed (one-way streets), so legs are
     *       timed the way the cab drives them: outward for DROP, back towards the
     *       office for PICKUP.</li>
     *   <li><b>Clock time.</b> Each leg's free-flow time is scaled by the traffic factor
     *       at the moment the cab is at the leg's office-side end. For DROP that is when
     *       it sets off on the leg, walking forwards from departure. For PICKUP it is
     *       when it finishes the leg, walking backwards from the required arrival. Both
     *       are known at that point in the walk, so no iteration is needed.</li>
     * </ul>
     */
    public static double[] rideMinutes(TravelModel travel, GeoPoint office, List<Stop> outward, double dwell,
                                       ShiftContext shift) {
        double[] ride = new double[outward.size()];
        double elapsed = 0;
        GeoPoint prev = office;
        for (int k = 0; k < outward.size(); k++) {
            GeoPoint here = outward.get(k).location();
            // Minutes between the office time and the cab being at the office-side end of this leg.
            double offset = k == 0 ? 0 : ride[k - 1] + dwell;
            double factor;
            double freeFlow;
            if (shift.direction() == Direction.DROP) {
                factor = shift.traffic().factorAt(shift.officeTime().plusSeconds(Math.round(offset * 60)));
                freeFlow = travel.minutes(prev, here);
            } else {
                factor = shift.traffic().factorAt(shift.officeTime().minusSeconds(Math.round(offset * 60)));
                freeFlow = travel.minutes(here, prev);
            }
            elapsed += freeFlow * factor;
            ride[k] = elapsed + k * dwell;
            prev = here;
        }
        return ride;
    }

    /** The farthest stop always rides longest, so this is the last entry. */
    public static double maxRideMinutes(TravelModel travel, GeoPoint office, List<Stop> outward, double dwell,
                                        ShiftContext shift) {
        double[] ride = rideMinutes(travel, office, outward, dwell, shift);
        return ride.length == 0 ? 0 : ride[ride.length - 1];
    }
}
