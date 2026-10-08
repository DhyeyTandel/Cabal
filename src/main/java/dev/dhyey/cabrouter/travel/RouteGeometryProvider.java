package dev.dhyey.cabrouter.travel;

import dev.dhyey.cabrouter.routing.Direction;
import dev.dhyey.cabrouter.routing.GeoPoint;
import dev.dhyey.cabrouter.routing.StopTiming;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Road geometry for a cab's route, so the map can follow real streets. Purely
 * presentational: plans never depend on it, so a failure just means "no geometry".
 */
public interface RouteGeometryProvider {

    /**
     * One polyline6-encoded string per leg of {@code drivingOrder} (leg i runs from point i
     * to point i+1), or empty when no geometry is available.
     */
    Optional<List<String>> legs(List<GeoPoint> drivingOrder);

    /** The points a cab drives through: its stops then the office for PICKUP, the office then its stops for DROP. */
    static List<GeoPoint> drivingOrder(Direction direction, GeoPoint office, List<StopTiming> stops) {
        List<GeoPoint> points = new ArrayList<>(stops.size() + 1);
        if (direction == Direction.DROP) {
            points.add(office);
        }
        for (StopTiming t : stops) {
            points.add(t.stop().location());
        }
        if (direction == Direction.PICKUP) {
            points.add(office);
        }
        return points;
    }
}
