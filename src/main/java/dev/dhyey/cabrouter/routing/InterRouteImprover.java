package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Local search across cabs, run after the sweep. Sweep groups people by angle only, so
 * someone 2 km from the office can share a cab with people 15 km out on the same
 * bearing. Two moves repair that:
 *
 * <ul>
 *   <li><b>Relocate</b>: move one employee into another cab with a free seat. If that
 *       empties the source cab, the plan needs one cab fewer. Cabs keep their vehicle
 *       during the search; the planner right-sizes vehicles afterwards.</li>
 *   <li><b>Swap</b>: exchange two employees between cabs.</li>
 * </ul>
 *
 * A move is accepted only if it reduces total cost (vehicle trip and km charges, plus
 * guards), keeps every multi-person cab within the ride limit and every rider's
 * time-window preference, and does not add escort-flagged cabs. Emptying a cab saves its
 * whole trip charge. Only each cab's nearest
 * neighbours (by centroid) are tried, which keeps a pass roughly linear in the number
 * of cabs.
 */
final class InterRouteImprover {

    private static final double EPS = 1e-9;
    private static final int NEIGHBOUR_CABS = 6;
    /**
     * How much farther than their own cab's centre a rider may be from the other cab's
     * centre and still be tried there. Measured on 1,000 riders (see README):
     * 1.0 is fastest but loses 0.6 points of saving; 1.5 is the tightest value that
     * keeps the full saving.
     */
    private static final double FILTER_SLACK = 1.5;

    /**
     * Small shifts search every move: they take milliseconds anyway, and on the
     * 23-rider demo the filter cost 1.6% distance. Pruning starts above this many cabs
     * (about 200 riders), where the full search starts to take seconds.
     */
    private static final int PRUNE_ABOVE_CABS = 40;
    /** A safety net only; searches normally run out of dirty cabs long before this. */
    private static final int MAX_PASSES = 500;

    /** Sequences and times a cab's route for a new set of riders. */
    private final CabBuilder builder;

    InterRouteImprover(CabBuilder builder) {
        this.builder = builder;
    }

    /**
     * Runs passes until no move helps. Two things keep this fast on large shifts:
     *
     * <ul>
     *   <li><b>Only dirty pairs.</b> A pair is re-examined only if one of its cabs changed
     *       in the previous pass. If neither changed, every move between them was
     *       already rejected.</li>
     *   <li><b>Each pair once.</b> Neighbour lists are usually mutual, so pairs are
     *       collected without order before being tried.</li>
     * </ul>
     */
    List<PlannedCab> improve(List<PlannedCab> initial, RoutingParams params) {
        List<PlannedCab> cabs = new ArrayList<>(initial);
        boolean prune = cabs.size() > PRUNE_ABOVE_CABS;
        Set<PlannedCab> dirty = identitySet();
        dirty.addAll(cabs);
        for (int pass = 0; pass < MAX_PASSES && !dirty.isEmpty(); pass++) {
            Set<PlannedCab> changed = identitySet();
            for (PlannedCab[] pair : dirtyPairs(cabs, dirty)) {
                int a = indexOf(cabs, pair[0]);
                int b = indexOf(cabs, pair[1]);
                if (a < 0 || b < 0) {
                    continue; // one of them was replaced earlier in this pass
                }
                List<PlannedCab> created = tryRelocate(cabs, a, b, params, prune);
                if (created == null) {
                    created = tryRelocate(cabs, b, a, params, prune);
                }
                if (created == null) {
                    created = trySwap(cabs, a, b, params, prune);
                }
                if (created != null) {
                    changed.addAll(created);
                }
            }
            dirty = changed;
        }
        return cabs;
    }

    private List<PlannedCab[]> dirtyPairs(List<PlannedCab> cabs, Set<PlannedCab> dirty) {
        Set<Long> seen = new HashSet<>();
        List<PlannedCab[]> pairs = new ArrayList<>();
        for (int a = 0; a < cabs.size(); a++) {
            for (int b : nearestCabs(cabs, a)) {
                if (!dirty.contains(cabs.get(a)) && !dirty.contains(cabs.get(b))) {
                    continue;
                }
                long key = (long) Math.min(a, b) * cabs.size() + Math.max(a, b);
                if (seen.add(key)) {
                    pairs.add(new PlannedCab[] {cabs.get(a), cabs.get(b)});
                }
            }
        }
        return pairs;
    }

    private static int indexOf(List<PlannedCab> cabs, PlannedCab cab) {
        for (int i = 0; i < cabs.size(); i++) {
            if (cabs.get(i) == cab) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Pruning filter, in the spirit of granular local search (Toth and Vigo, 2003): a
     * rider is only worth moving to cab B if they sit roughly as close to B's centre as
     * to their own cab's. Riders deep inside their own cluster would only lengthen B's
     * route, so their moves are skipped without rebuilding any route.
     */
    private static boolean fitsBetterIn(Stop s, GeoPoint ownCentre, GeoPoint otherCentre) {
        return HaversineTravelModel.greatCircleKm(s.location(), otherCentre)
                < FILTER_SLACK * HaversineTravelModel.greatCircleKm(s.location(), ownCentre);
    }

    /** @return the cabs that replaced the old ones, or null if no improving relocate exists */
    private List<PlannedCab> tryRelocate(List<PlannedCab> cabs, int from, int to, RoutingParams params,
                                         boolean prune) {
        PlannedCab src = cabs.get(from);
        PlannedCab dst = cabs.get(to);
        if (!dst.hasFreeSeat()) {
            return null;
        }
        GeoPoint srcCentre = CabNeighbours.centroid(src);
        GeoPoint dstCentre = CabNeighbours.centroid(dst);
        for (Stop s : src.stops()) {
            // A one-rider cab is always worth trying to empty: it saves a vehicle.
            if (prune && src.stops().size() > 1 && !fitsBetterIn(s, srcCentre, dstCentre)) {
                continue;
            }
            List<Stop> srcRest = without(src.stops(), s);
            PlannedCab newSrc = srcRest.isEmpty() ? null : builder.build(src.vehicle(), srcRest);
            PlannedCab newDst = builder.build(dst.vehicle(), with(dst.stops(), s));
            if (accept(src, dst, newSrc, newDst, params)) {
                cabs.set(to, newDst);
                if (newSrc == null) {
                    cabs.remove(from);
                    return List.of(newDst);
                }
                cabs.set(from, newSrc);
                return List.of(newSrc, newDst);
            }
        }
        return null;
    }

    /** @return the two rebuilt cabs, or null if no improving swap exists */
    private List<PlannedCab> trySwap(List<PlannedCab> cabs, int a, int b, RoutingParams params, boolean prune) {
        PlannedCab ca = cabs.get(a);
        PlannedCab cb = cabs.get(b);
        GeoPoint centreA = CabNeighbours.centroid(ca);
        GeoPoint centreB = CabNeighbours.centroid(cb);
        for (Stop s : ca.stops()) {
            for (Stop t : cb.stops()) {
                if (prune && !fitsBetterIn(s, centreA, centreB) && !fitsBetterIn(t, centreB, centreA)) {
                    continue;
                }
                PlannedCab na = builder.build(ca.vehicle(), with(without(ca.stops(), s), t));
                PlannedCab nb = builder.build(cb.vehicle(), with(without(cb.stops(), t), s));
                if (accept(ca, cb, na, nb, params)) {
                    cabs.set(a, na);
                    cabs.set(b, nb);
                    return List.of(na, nb);
                }
            }
        }
        return null;
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
        double before = params.cost(oldA) + params.cost(oldB);
        double after = (newA == null ? 0 : params.cost(newA)) + params.cost(newB);
        return after < before - EPS;
    }

    private static boolean withinRideLimit(PlannedCab cab, RoutingParams params) {
        return cab == null || cab.stops().size() == 1
                || (cab.maxRideMinutes() <= params.maxRideMinutes() && cab.timetable().meetsWindows());
    }

    private static int escorts(PlannedCab cab) {
        return cab != null && cab.escortRequired() ? 1 : 0;
    }

    private List<Integer> nearestCabs(List<PlannedCab> cabs, int a) {
        return CabNeighbours.nearest(cabs, a, NEIGHBOUR_CABS);
    }

    private static Set<PlannedCab> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
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
