package dev.dhyey.cabrouter.routing;

import java.util.Collection;

/**
 * Supplies the travel model for one planning call. A road-network provider fetches a
 * matrix for exactly these points, so the model only answers questions about them.
 */
public interface TravelModelProvider {

    TravelEstimate forPoints(Collection<GeoPoint> points);
}
