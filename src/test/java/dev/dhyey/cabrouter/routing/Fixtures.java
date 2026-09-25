package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class Fixtures {

    /** Straight-line distance at 30 km/h, so 1 km takes 2 minutes. */
    static final TravelModel TRAVEL = new HaversineTravelModel(1.0, 30);

    /** Roughly Manyata Tech Park, Bengaluru. */
    static final GeoPoint OFFICE = new GeoPoint(13.0475, 77.6206);

    /** About 1.11 km per 0.01 degree of latitude. */
    static final double KM_PER_DEG_LAT = 111.2;

    private Fixtures() {
    }

    static Stop stop(long id, double kmNorth, double kmEast) {
        return stop(id, kmNorth, kmEast, false);
    }

    static Stop stop(long id, double kmNorth, double kmEast, boolean escortSensitive) {
        double lat = OFFICE.lat() + kmNorth / KM_PER_DEG_LAT;
        double lng = OFFICE.lng() + kmEast / (KM_PER_DEG_LAT * Math.cos(Math.toRadians(OFFICE.lat())));
        return new Stop(id, new GeoPoint(lat, lng), escortSensitive);
    }

    /** Uniform in a square of +/- radiusKm around the office. */
    static List<Stop> randomStops(Random rnd, int n, double radiusKm) {
        List<Stop> stops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            stops.add(stop(i + 1, (rnd.nextDouble() * 2 - 1) * radiusKm, (rnd.nextDouble() * 2 - 1) * radiusKm));
        }
        return stops;
    }

    static RoutingParams params(int capacity, double maxRide) {
        return new RoutingParams(capacity, maxRide, 2, false, 0.25, 48);
    }
}
