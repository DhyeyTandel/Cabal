package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Entry point for the routing core. It has no framework dependencies and holds no state
 * between calls.
 *
 * <ul>
 *   <li>{@link #plan}: sweep-cluster a whole shift, sequence each cab, improve across
 *       cabs, then give each cab the cheapest vehicle that fits.</li>
 *   <li>{@link #buildCab}: re-sequence one cab after its members change.</li>
 *   <li>{@link #bestInsertion}: slot a late booking into an existing plan.</li>
 * </ul>
 */
public final class RoutePlanner {

    /** Large enough to outweigh any realistic trip cost, so a new escort flag is a last resort. */
    private static final double ESCORT_WORSENING_PENALTY = 1e9;

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
        CabBuilder builder = new CabBuilder(travel, sequencer, office, params);
        List<PlannedCab> improved = new InterRouteImprover(builder).improve(swept, params);
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
        CabBuilder builder = new CabBuilder(travel, sequencer, office, params);
        List<PlannedCab> cabs = new ArrayList<>();
        for (List<Stop> cluster : clusters) {
            cabs.add(builder.build(largest, cluster));
        }
        return rightSize(cabs, params.fleet());
    }

    /**
     * Gives every cab the cheapest vehicle that seats its riders, subject to how many of
     * each type exist. Cabs are served fullest first.
     *
     * <p>Why this never paints itself into a corner: every vehicle that fits the fullest
     * cab also fits every emptier cab. So whichever of those vehicles the fullest cab
     * takes, each later cab loses exactly one option it could equally have used. If any
     * valid assignment exists, this greedy pass finds one. It is not guaranteed to find
     * the cheapest assignment (that is a min-cost matching), but with two or three
     * vehicle types the difference is small.
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
            VehicleType vehicle = inventory.cheapestFitting(cab.stops().size(), cab.distanceKm()).orElseThrow(
                    () -> new FleetExhaustedException("not enough vehicles for " + cabs.size() + " cabs"));
            inventory.take(vehicle);
            result.set(i, cab.withVehicle(vehicle));
        }
        return result;
    }

    /**
     * Sequences one cab and applies the night escort rule. Delegates to {@link CabBuilder};
     * see there for what the rule does.
     */
    public PlannedCab buildCab(GeoPoint office, VehicleType vehicle, List<Stop> stops, RoutingParams params) {
        return new CabBuilder(travel, sequencer, office, params).build(vehicle, stops);
    }

    /**
     * Cheapest insertion, by cost. Three kinds of option are compared:
     *
     * <ul>
     *   <li>add the rider to a cab with a free seat;</li>
     *   <li>add the rider to a full cab by swapping its vehicle for a bigger free one;</li>
     *   <li>send a new cab in the cheapest free vehicle.</li>
     * </ul>
     *
     * Options that break the ride limit are skipped. An option that turns an escort-free
     * cab into one needing a guard loses to any option that does not.
     *
     * @throws FleetExhaustedException if every option is infeasible or no vehicle is free
     */
    public Insertion bestInsertion(GeoPoint office, List<PlannedCab> cabs, Stop stop, RoutingParams params) {
        for (PlannedCab cab : cabs) {
            if (cab.contains(stop.employeeId())) {
                throw new IllegalArgumentException("employee " + stop.employeeId() + " is already routed");
            }
        }
        FleetInventory free = FleetInventory.after(params.fleet(), cabs);
        CabBuilder builder = new CabBuilder(travel, sequencer, office, params);
        int bestIndex = -1;
        PlannedCab bestCab = null;
        double bestScore = Double.MAX_VALUE;

        for (int i = 0; i < cabs.size(); i++) {
            PlannedCab cab = cabs.get(i);
            List<Stop> members = new ArrayList<>(cab.stops());
            members.add(stop);
            List<VehicleType> vehicles = new ArrayList<>();
            if (cab.hasFreeSeat()) {
                vehicles.add(cab.vehicle());
            } else {
                free.free().stream().filter(t -> t.seats() >= members.size()).forEach(vehicles::add);
            }
            for (VehicleType vehicle : vehicles) {
                PlannedCab candidate = builder.build(vehicle, members);
                if (candidate.maxRideMinutes() > params.maxRideMinutes()) {
                    continue;
                }
                double score = params.cost(candidate) - params.cost(cab)
                        + (candidate.escortRequired() && !cab.escortRequired() ? ESCORT_WORSENING_PENALTY : 0);
                if (score < bestScore) {
                    bestScore = score;
                    bestIndex = i;
                    bestCab = candidate;
                }
            }
        }

        // A solo trip's distance does not depend on the vehicle, so price the options on it directly.
        double soloKm = RouteMetrics.pathKm(travel, office, List.of(stop));
        Optional<VehicleType> fresh = free.cheapestFitting(1, soloKm);
        if (fresh.isPresent()) {
            PlannedCab solo = builder.build(fresh.get(), List.of(stop));
            double score = params.cost(solo) + (solo.escortRequired() ? ESCORT_WORSENING_PENALTY : 0);
            if (score < bestScore) {
                return new Insertion(-1, solo);
            }
        }
        if (bestCab == null) {
            throw new FleetExhaustedException("every cab is full or too far, and no vehicle is free for a new one");
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
