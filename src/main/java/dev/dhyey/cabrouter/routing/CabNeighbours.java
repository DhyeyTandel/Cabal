package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Centroid distance between cabs, and the nearest-neighbour lookup built on it. Shared by
 * {@link InterRouteImprover} (which searches moves only between nearby cabs) and the
 * dissolve-cab feature on {@link ShiftPlan} (which only offers a dissolved cab's riders to
 * nearby cabs).
 */
final class CabNeighbours {

    private CabNeighbours() {
    }

    /** The average of a cab's stop coordinates. Not a real stop, just a rough centre. */
    static GeoPoint centroid(PlannedCab cab) {
        double lat = 0;
        double lng = 0;
        for (Stop s : cab.stops()) {
            lat += s.location().lat();
            lng += s.location().lng();
        }
        return new GeoPoint(lat / cab.stops().size(), lng / cab.stops().size());
    }

    /**
     * Indices into {@code cabs} of the {@code count} cabs whose centroid is nearest cab
     * {@code a}'s, nearest first, excluding {@code a} itself.
     *
     * <p>Centroids are not real stops, so a matrix-backed travel model has no entry for
     * them. Straight-line distance is plenty for choosing which cabs to compare.
     */
    static List<Integer> nearest(List<PlannedCab> cabs, int a, int count) {
        GeoPoint centre = centroid(cabs.get(a));
        List<Integer> others = new ArrayList<>();
        for (int i = 0; i < cabs.size(); i++) {
            if (i != a) {
                others.add(i);
            }
        }
        others.sort(Comparator.comparingDouble(i -> HaversineTravelModel.greatCircleKm(centre, centroid(cabs.get(i)))));
        return others.subList(0, Math.min(count, others.size()));
    }
}
