package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Local search across cabs, run after the sweep. Sweep groups people by angle only, so
 * someone 2 km from the office can share a cab with people 15 km out on the same
 * bearing. Two moves repair that:
 *
 * <ul>
 *   <li><b>Relocate</b>: move one employee into another cab with a free seat. If that
 *       empties the source cab, the plan needs one cab fewer.</li>
 *   <li><b>Swap</b>: exchange two employees between cabs.</li>
 * </ul>
 *
 * A move is accepted only if it reduces total kilometres, keeps every multi-person cab
 * within the ride limit, and does not add escort-flagged cabs. Only each cab's nearest
 * neighbours (by centroid) are tried, which keeps a pass roughly linear in the number
 * of cabs.
 */
final class InterRouteImprover {

    private static final double EPS = 1e-9;
    private static final int NEIGHBOUR_CABS = 6;
    private static final int MAX_PASSES = 50;

    private final TravelModel travel;
    private final BiFunction<List<Stop>, RoutingParams, PlannedCab> buildCab;

    InterRouteImprover(TravelModel travel, BiFunction<List<Stop>, RoutingParams, PlannedCab> buildCab) {
        this.travel = travel;
        this.buildCab = buildCab;
    }

    List<PlannedCab> improve(List<PlannedCab> initial, RoutingParams params) {
        List<PlannedCab> cabs = new ArrayList<>(initial);
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            if (!applyFirstImprovingMove(cabs, params)) {
                break;
            }
        }
        return cabs;
    }

    private boolean applyFirstImprovingMove(List<PlannedCab> cabs, RoutingParams params) {
        boolean changed = false;
        for (int a = 0; a < cabs.size(); a++) {
            for (int b : nearestCabs(cabs, a)) {
                if (a >= cabs.size() || b >= cabs.size()) {
                    break; // a relocate emptied a cab and the list shrank; start the next pass
                }
                if (tryRelocate(cabs, a, b, params) || tryRelocate(cabs, b, a, params) || trySwap(cabs, a, b, params)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean tryRelocate(List<PlannedCab> cabs, int from, int to, RoutingParams params) {
        PlannedCab src = cabs.get(from);
        PlannedCab dst = cabs.get(to);
        if (dst.stops().size() >= params.cabCapacity()) {
            return false;
        }
        for (Stop s : src.stops()) {
            List<Stop> srcRest = without(src.stops(), s);
            PlannedCab newSrc = srcRest.isEmpty() ? null : buildCab.apply(srcRest, params);
            PlannedCab newDst = buildCab.apply(with(dst.stops(), s), params);
            if (accept(src, dst, newSrc, newDst, params)) {
                cabs.set(to, newDst);
                if (newSrc == null) {
                    cabs.remove(from);
                } else {
                    cabs.set(from, newSrc);
                }
                return true;
            }
        }
        return false;
    }

    private boolean trySwap(List<PlannedCab> cabs, int a, int b, RoutingParams params) {
        PlannedCab ca = cabs.get(a);
        PlannedCab cb = cabs.get(b);
        for (Stop s : ca.stops()) {
            for (Stop t : cb.stops()) {
                PlannedCab na = buildCab.apply(with(without(ca.stops(), s), t), params);
                PlannedCab nb = buildCab.apply(with(without(cb.stops(), t), s), params);
                if (accept(ca, cb, na, nb, params)) {
                    cabs.set(a, na);
                    cabs.set(b, nb);
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code newA} may be null, meaning cab A was emptied and dropped. */
    private boolean accept(PlannedCab oldA, PlannedCab oldB, PlannedCab newA, PlannedCab newB, RoutingParams params) {
        if (!withinRideLimit(newA, params) || !withinRideLimit(newB, params)) {
            return false;
        }
        int oldEscorts = escorts(oldA) + escorts(oldB);
        int newEscorts = escorts(newA) + escorts(newB);
        if (newEscorts > oldEscorts) {
            return false;
        }
        double before = oldA.distanceKm() + oldB.distanceKm();
        double after = (newA == null ? 0 : newA.distanceKm()) + newB.distanceKm();
        return after < before - EPS || (newA == null && after <= before + EPS);
    }

    private static boolean withinRideLimit(PlannedCab cab, RoutingParams params) {
        return cab == null || cab.stops().size() == 1 || cab.maxRideMinutes() <= params.maxRideMinutes();
    }

    private static int escorts(PlannedCab cab) {
        return cab != null && cab.escortRequired() ? 1 : 0;
    }

    private List<Integer> nearestCabs(List<PlannedCab> cabs, int a) {
        GeoPoint ca = centroid(cabs.get(a));
        List<Integer> others = new ArrayList<>();
        for (int i = 0; i < cabs.size(); i++) {
            if (i != a) {
                others.add(i);
            }
        }
        others.sort(Comparator.comparingDouble(i -> travel.distanceKm(ca, centroid(cabs.get(i)))));
        return others.subList(0, Math.min(NEIGHBOUR_CABS, others.size()));
    }

    private static GeoPoint centroid(PlannedCab cab) {
        double lat = 0;
        double lng = 0;
        for (Stop s : cab.stops()) {
            lat += s.location().lat();
            lng += s.location().lng();
        }
        return new GeoPoint(lat / cab.stops().size(), lng / cab.stops().size());
    }

    private static List<Stop> without(List<Stop> stops, Stop s) {
        List<Stop> out = new ArrayList<>(stops);
        out.remove(s);
        return out;
    }

    private static List<Stop> with(List<Stop> stops, Stop s) {
        List<Stop> out = new ArrayList<>(stops);
        out.add(s);
        return out;
    }
}
