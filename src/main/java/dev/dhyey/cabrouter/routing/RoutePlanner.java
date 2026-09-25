package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point for the routing core. It has no framework dependencies and holds no state
 * between calls.
 *
 * <ul>
 *   <li>{@link #plan}: sweep-cluster a whole shift, sequence each cab, then improve
 *       across cabs.</li>
 *   <li>{@link #buildCab}: re-sequence one cab after its members change.</li>
 *   <li>{@link #bestInsertion}: slot a late booking into an existing plan.</li>
 * </ul>
 */
public final class RoutePlanner {

    private final TravelModel travel;
    private final StopSequencer sequencer;
    private final SweepClusterer clusterer;

    public RoutePlanner(TravelModel travel) {
        this.travel = travel;
        this.sequencer = new StopSequencer(travel);
        this.clusterer = new SweepClusterer(travel, sequencer);
    }

    public List<PlannedCab> plan(GeoPoint office, List<Stop> stops, RoutingParams params) {
        List<PlannedCab> swept = sweepOnly(office, stops, params);
        return new InterRouteImprover(travel, (members, p) -> buildCab(office, members, p)).improve(swept, params);
    }

    /** The plan before inter-route improvement. Exposed so tests can measure what it adds. */
    List<PlannedCab> sweepOnly(GeoPoint office, List<Stop> stops, RoutingParams params) {
        Set<Long> seen = new HashSet<>();
        for (Stop s : stops) {
            if (!seen.add(s.employeeId())) {
                throw new IllegalArgumentException("employee " + s.employeeId() + " appears twice");
            }
        }
        return clusterer.cluster(office, stops, params).stream()
                .map(cluster -> buildCab(office, cluster, params))
                .toList();
    }

    /**
     * Sequences one cab and applies the night escort rule. On a night shift the stop
     * farthest from the office (the first pickup, or the last drop) must not be an
     * escort-sensitive employee. If it is, we try ending the route at each other
     * employee instead. We accept the cheapest reorder that stays within the detour
     * tolerance and does not lengthen anyone's ride past the limit. If no reorder
     * qualifies, the cab is flagged as needing a guard.
     */
    public PlannedCab buildCab(GeoPoint office, List<Stop> stops, RoutingParams params) {
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("a cab needs at least one stop");
        }
        if (stops.size() > params.cabCapacity()) {
            throw new IllegalArgumentException("cab over capacity: " + stops.size() + " > " + params.cabCapacity());
        }
        List<Stop> route = sequencer.sequence(office, stops);
        boolean escortRequired = false;

        if (params.nightShift() && route.get(route.size() - 1).escortSensitive()) {
            List<Stop> repaired = escortSafeRoute(office, stops, route, params);
            if (repaired != null) {
                route = repaired;
            } else {
                escortRequired = true;
            }
        }
        return new PlannedCab(
                route,
                RouteMetrics.pathKm(travel, office, route),
                RouteMetrics.maxRideMinutes(travel, office, route, params.dwellMinutes()),
                escortRequired);
    }

    private List<Stop> escortSafeRoute(GeoPoint office, List<Stop> stops, List<Stop> unconstrained, RoutingParams params) {
        double baseKm = RouteMetrics.pathKm(travel, office, unconstrained);
        double rideLimit = Math.max(params.maxRideMinutes(),
                RouteMetrics.maxRideMinutes(travel, office, unconstrained, params.dwellMinutes()));

        List<Stop> best = null;
        double bestKm = baseKm * (1 + params.escortDetourTolerance());
        for (Stop anchor : stops) {
            if (anchor.escortSensitive()) {
                continue;
            }
            List<Stop> rest = new ArrayList<>(stops);
            rest.remove(anchor);
            List<Stop> candidate = new ArrayList<>(sequencer.sequence(office, rest, anchor.location()));
            candidate.add(anchor);

            double km = RouteMetrics.pathKm(travel, office, candidate);
            double ride = RouteMetrics.maxRideMinutes(travel, office, candidate, params.dwellMinutes());
            if (km <= bestKm + 1e-9 && ride <= rideLimit) {
                best = candidate;
                bestKm = km;
            }
        }
        return best;
    }

    /**
     * Cheapest insertion. Among cabs with a free seat, pick the one whose route grows
     * the least once the new stop is added, as long as the result respects the ride
     * limit. A cab that stays escort-free beats one that would need a guard. If no cab
     * qualifies, open a new one.
     */
    public Insertion bestInsertion(GeoPoint office, List<PlannedCab> cabs, Stop stop, RoutingParams params) {
        for (PlannedCab cab : cabs) {
            if (cab.contains(stop.employeeId())) {
                throw new IllegalArgumentException("employee " + stop.employeeId() + " is already routed");
            }
        }
        int bestIndex = -1;
        PlannedCab bestCab = null;
        double bestAddedKm = Double.MAX_VALUE;

        for (int i = 0; i < cabs.size(); i++) {
            PlannedCab cab = cabs.get(i);
            if (cab.stops().size() >= params.cabCapacity()) {
                continue;
            }
            List<Stop> members = new ArrayList<>(cab.stops());
            members.add(stop);
            PlannedCab candidate = buildCab(office, members, params);
            if (candidate.maxRideMinutes() > params.maxRideMinutes()) {
                continue;
            }
            // Never turn an escort-free cab into one that needs a guard when an alternative exists.
            boolean worsensEscort = candidate.escortRequired() && !cab.escortRequired();
            double addedKm = candidate.distanceKm() - cab.distanceKm() + (worsensEscort ? 1_000 : 0);
            if (addedKm < bestAddedKm) {
                bestAddedKm = addedKm;
                bestIndex = i;
                bestCab = candidate;
            }
        }
        if (bestCab == null) {
            return new Insertion(-1, buildCab(office, List.of(stop), params));
        }
        return new Insertion(bestIndex, bestCab);
    }

    /** @param cabIndex index into the cab list that was passed in, or -1 when a new cab is needed */
    public record Insertion(int cabIndex, PlannedCab cab) {

        public boolean opensNewCab() {
            return cabIndex < 0;
        }
    }
}
