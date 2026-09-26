package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point for the routing core. It has no framework dependencies and holds no state
 * between calls.
 *
 * <ul>
 *   <li>{@link #plan}: sweep-cluster a whole shift, sequence each cab, improve across
 *       cabs, then give each cab the smallest vehicle that fits.</li>
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

    /** @throws FleetExhaustedException if the fleet cannot seat everyone */
    public List<PlannedCab> plan(GeoPoint office, List<Stop> stops, RoutingParams params) {
        List<PlannedCab> swept = sweepOnly(office, stops, params);
        List<PlannedCab> improved = new InterRouteImprover(
                (cab, members) -> buildCab(office, cab.vehicle(), members, params)).improve(swept, params);
        return rightSize(improved, params.fleet());
    }

    /** The plan before inter-route improvement. Exposed so tests can measure what it adds. */
    List<PlannedCab> sweepOnly(GeoPoint office, List<Stop> stops, RoutingParams params) {
        Set<Long> seen = new HashSet<>();
        for (Stop s : stops) {
            if (!seen.add(s.employeeId())) {
                throw new IllegalArgumentException("employee " + s.employeeId() + " appears twice");
            }
        }
        List<List<Stop>> clusters = clusterer.cluster(office, stops, params);
        // Sweep only checked that some vehicle fits each cluster; hand out the real ones here.
        List<Fleet.Entry> types = params.fleet().entries();
        VehicleType largest = types.get(types.size() - 1).type();
        List<PlannedCab> cabs = new ArrayList<>();
        for (List<Stop> cluster : clusters) {
            cabs.add(buildCab(office, largest, cluster, params));
        }
        return rightSize(cabs, params.fleet());
    }

    /**
     * Gives every cab the smallest vehicle that seats its riders, subject to how many of
     * each type exist. Cabs are served fullest first. The vehicles that fit a full cab
     * also fit every emptier one, so taking the smallest fit never blocks a later cab.
     * If any valid assignment exists, this greedy pass finds one.
     */
    List<PlannedCab> rightSize(List<PlannedCab> cabs, Fleet fleet) {
        FleetInventory inventory = fleet.inventory();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < cabs.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> cabs.get(i).stops().size()).reversed());

        List<PlannedCab> result = new ArrayList<>(cabs);
        for (int i : order) {
            PlannedCab cab = cabs.get(i);
            VehicleType vehicle = inventory.smallestFitting(cab.stops().size()).orElseThrow(
                    () -> new FleetExhaustedException("not enough vehicles for " + cabs.size() + " cabs"));
            inventory.take(vehicle);
            result.set(i, cab.withVehicle(vehicle));
        }
        return result;
    }

    /**
     * Sequences one cab and applies the night escort rule. The rule looks at the stop
     * farthest from the office (the first pickup, or the last drop). If the cab is there
     * at night and that rider is escort-sensitive, we try ending the route at each other
     * rider instead. We accept the cheapest reorder that stays within the detour
     * tolerance and does not lengthen anyone's ride past the limit. If no reorder
     * qualifies, the cab is flagged as needing a guard.
     */
    public PlannedCab buildCab(GeoPoint office, VehicleType vehicle, List<Stop> stops, RoutingParams params) {
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("a cab needs at least one stop");
        }
        if (stops.size() > vehicle.seats()) {
            throw new IllegalArgumentException(
                    vehicle.name() + " over capacity: " + stops.size() + " > " + vehicle.seats());
        }
        List<Stop> route = sequencer.sequence(office, stops);
        boolean escortRequired = false;

        if (route.get(route.size() - 1).escortSensitive() && farEndAtNight(office, route, params)) {
            List<Stop> repaired = escortSafeRoute(office, stops, route, params);
            if (repaired != null) {
                route = repaired;
            } else {
                escortRequired = true;
            }
        }
        return new PlannedCab(
                vehicle,
                route,
                RouteMetrics.pathKm(travel, office, route),
                RouteMetrics.maxRideMinutes(travel, office, route, params.dwellMinutes(), params.shift().direction()),
                escortRequired);
    }

    private boolean farEndAtNight(GeoPoint office, List<Stop> route, RoutingParams params) {
        double ride = RouteMetrics.maxRideMinutes(travel, office, route, params.dwellMinutes(), params.shift().direction());
        LocalDateTime when = params.shift().farEndTime(ride, params.dwellMinutes());
        return params.shift().isNight(when);
    }

    private List<Stop> escortSafeRoute(GeoPoint office, List<Stop> stops, List<Stop> unconstrained, RoutingParams params) {
        double baseKm = RouteMetrics.pathKm(travel, office, unconstrained);
        double rideLimit = Math.max(params.maxRideMinutes(),
                RouteMetrics.maxRideMinutes(travel, office, unconstrained, params.dwellMinutes(), params.shift().direction()));

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
            double ride = RouteMetrics.maxRideMinutes(travel, office, candidate, params.dwellMinutes(), params.shift().direction());
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
     * qualifies, open a new one in the smallest free vehicle.
     *
     * @throws FleetExhaustedException if a new cab is needed and no vehicle is free
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
            if (!cab.hasFreeSeat()) {
                continue;
            }
            List<Stop> members = new ArrayList<>(cab.stops());
            members.add(stop);
            PlannedCab candidate = buildCab(office, cab.vehicle(), members, params);
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
        if (bestCab != null) {
            return new Insertion(bestIndex, bestCab);
        }
        VehicleType vehicle = FleetInventory.after(params.fleet(), cabs).smallestFitting(1).orElseThrow(
                () -> new FleetExhaustedException("every cab is full or too far, and no vehicle is free for a new one"));
        return new Insertion(-1, buildCab(office, vehicle, List.of(stop), params));
    }

    /** @param cabIndex index into the cab list that was passed in, or -1 when a new cab is needed */
    public record Insertion(int cabIndex, PlannedCab cab) {

        public boolean opensNewCab() {
            return cabIndex < 0;
        }
    }
}
