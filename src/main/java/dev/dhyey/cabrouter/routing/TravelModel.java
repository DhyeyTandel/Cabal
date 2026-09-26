package dev.dhyey.cabrouter.routing;

/**
 * Road distance and drive time between two points.
 *
 * <p>{@link #distanceKm} must be symmetric: {@code distanceKm(a, b) == distanceKm(b, a)}.
 * It is the optimisation objective, and 2-opt relies on symmetry when it reverses a
 * segment. {@link #minutes} may be directed, because real roads have one-way streets.
 * Ride times and ETAs always time each leg in the direction the cab drives it.
 */
public interface TravelModel {

    double distanceKm(GeoPoint a, GeoPoint b);

    double minutes(GeoPoint a, GeoPoint b);
}
