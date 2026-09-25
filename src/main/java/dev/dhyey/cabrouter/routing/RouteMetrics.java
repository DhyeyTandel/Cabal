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
     * for every stop in between. The same number holds for pickup and drop, because a
     * pickup route is the drop route reversed.
     */
    public static double[] rideMinutes(TravelModel travel, GeoPoint office, List<Stop> outward, double dwell) {
        double[] ride = new double[outward.size()];
        double elapsed = 0;
        GeoPoint prev = office;
        for (int k = 0; k < outward.size(); k++) {
            elapsed += travel.minutes(prev, outward.get(k).location());
            ride[k] = elapsed + k * dwell;
            prev = outward.get(k).location();
        }
        return ride;
    }

    /** The farthest stop always rides longest, so this is the last entry. */
    public static double maxRideMinutes(TravelModel travel, GeoPoint office, List<Stop> outward, double dwell) {
        double[] ride = rideMinutes(travel, office, outward, dwell);
        return ride.length == 0 ? 0 : ride[ride.length - 1];
    }
}
