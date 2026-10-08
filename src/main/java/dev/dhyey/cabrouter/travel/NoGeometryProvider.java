package dev.dhyey.cabrouter.travel;

import dev.dhyey.cabrouter.routing.GeoPoint;
import java.util.List;
import java.util.Optional;

/** Used with haversine travel, which has no roads to draw: the map falls back to straight lines. */
public final class NoGeometryProvider implements RouteGeometryProvider {

    @Override
    public Optional<List<String>> legs(List<GeoPoint> drivingOrder) {
        return Optional.empty();
    }
}
