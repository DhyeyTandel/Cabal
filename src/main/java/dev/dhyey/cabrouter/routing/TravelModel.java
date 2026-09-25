package dev.dhyey.cabrouter.routing;

/**
 * Road distance and drive time between two points.
 *
 * <p>Implementations must be symmetric: {@code distanceKm(a, b) == distanceKm(b, a)}.
 * 2-opt relies on this when it reverses a segment, and the planner relies on it to
 * treat a pickup route as a drop route driven backwards.
 */
public interface TravelModel {

    double distanceKm(GeoPoint a, GeoPoint b);

    double minutes(GeoPoint a, GeoPoint b);
}
