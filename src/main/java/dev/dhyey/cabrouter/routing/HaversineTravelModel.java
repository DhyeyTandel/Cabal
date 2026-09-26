package dev.dhyey.cabrouter.routing;

/**
 * Great-circle distance scaled by a circuity factor (roads are not straight lines),
 * with a flat average speed. Cheap and dependency-free; a real deployment would swap
 * in a road-network matrix (OSRM, Google Distance Matrix) behind {@link TravelModel}.
 */
public final class HaversineTravelModel implements TravelModel {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final double circuityFactor;
    private final double averageSpeedKmph;

    public HaversineTravelModel(double circuityFactor, double averageSpeedKmph) {
        if (circuityFactor < 1.0) {
            throw new IllegalArgumentException("circuity factor must be >= 1");
        }
        if (averageSpeedKmph <= 0) {
            throw new IllegalArgumentException("average speed must be positive");
        }
        this.circuityFactor = circuityFactor;
        this.averageSpeedKmph = averageSpeedKmph;
    }

    @Override
    public double distanceKm(GeoPoint a, GeoPoint b) {
        return greatCircleKm(a, b) * circuityFactor;
    }

    /** Straight-line distance over the Earth's surface, with no road factor. */
    public static double greatCircleKm(GeoPoint a, GeoPoint b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    @Override
    public double minutes(GeoPoint a, GeoPoint b) {
        return distanceKm(a, b) / averageSpeedKmph * 60;
    }
}
