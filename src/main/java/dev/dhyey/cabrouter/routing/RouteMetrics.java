package dev.dhyey.cabrouter.routing;

import java.util.List;

/** Distance arithmetic over an outward path starting at the office. */
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
}
