package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;
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

    /** A 09:00 pickup; every stop in these tests is reached well after dawn. */
    static final ShiftContext DAY = new ShiftContext(Direction.PICKUP, LocalDateTime.of(2026, 10, 1, 9, 0), 20, 7);

    /** A 23:00 drop; every stop is reached at night. */
    static final ShiftContext NIGHT = new ShiftContext(Direction.DROP, LocalDateTime.of(2026, 10, 1, 23, 0), 20, 7);

    static VehicleType cab(int seats) {
        return new VehicleType("CAB", seats);
    }

    static RoutingParams params(int capacity, double maxRide) {
        return params(Fleet.unlimited("CAB", capacity), maxRide);
    }

    static RoutingParams params(Fleet fleet, double maxRide) {
        return new RoutingParams(fleet, maxRide, 2, DAY, 0.25, 48, 0);
    }
}
