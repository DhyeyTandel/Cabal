package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits employees into cabs with the sweep heuristic (Gillett and Miller, 1974).
 *
 * <p>Picture a ray from the office rotating like a clock hand. We sort employees by
 * their polar angle and fill cabs in that order. A cab is closed when it runs out of
 * seats, or when adding the next employee would push someone past the ride-time limit
 * or break a rider's time-window preference. Each cab may grow to the largest vehicle
 * still free in the fleet; when it closes, it takes the smallest free vehicle that
 * seats its riders.
 *
 * <p>A single sweep depends heavily on where the ray starts, so we try several start
 * angles in both rotational directions. With a mixed fleet, filling every cab up to
 * the biggest vehicle is not always cheapest (an SUV may cost more than the sedan
 * trips it replaces), so the whole search also runs once per seat-size cap: "any
 * vehicle", "nothing bigger than a sedan", and so on. The cheapest result wins.
 */
public final class SweepClusterer {

    private final TravelModel travel;
    private final StopSequencer sequencer;

    public SweepClusterer(TravelModel travel, StopSequencer sequencer) {
        this.travel = travel;
        this.sequencer = sequencer;
    }

    /**
     * @return clusters, each already sequenced outward from the office
     * @throws FleetExhaustedException if no sweep fits everyone into the fleet
     */
    public List<List<Stop>> cluster(GeoPoint office, List<Stop> stops, RoutingParams params) {
        if (stops.isEmpty()) {
            return List.of();
        }
        List<Stop> byAngle = new ArrayList<>(stops);
        byAngle.sort(Comparator.comparingDouble((Stop s) -> polarAngle(office, s.location()))
                .thenComparingLong(Stop::employeeId));

        int n = byAngle.size();
        int starts = Math.min(n, params.sweepStarts());
        Solution best = null;
        for (int seatCap : params.fleet().seatSizes()) {
            for (int step : new int[] {1, -1}) {
                for (int s = 0; s < starts; s++) {
                    int start = (int) ((long) s * n / starts);
                    Solution candidate = sweep(office, byAngle, start, step, seatCap, params);
                    if (candidate != null && (best == null || candidate.isBetterThan(best))) {
                        best = candidate;
                    }
                }
            }
        }
        if (best == null) {
            throw new FleetExhaustedException("the fleet cannot carry " + n
                    + " employees within the ride limit; add vehicles or relax maxRideMinutes");
        }
        return best.clusters();
    }

    /**
     * @param seatCap only vehicles with at most this many seats may be used
     * @return the solution, or null if the fleet ran out before everyone was seated
     */
    private Solution sweep(GeoPoint office, List<Stop> byAngle, int start, int step, int seatCap,
                           RoutingParams params) {
        int n = byAngle.size();
        FleetInventory fleet = params.fleet().inventory();
        int seats = fleet.maxAvailableSeats(seatCap);
        List<List<Stop>> clusters = new ArrayList<>();
        double totalCost = 0;
        List<Stop> current = new ArrayList<>();
        List<Stop> currentRoute = List.of();

        for (int i = 0; i < n; i++) {
            Stop next = byAngle.get(Math.floorMod(start + step * i, n));
            if (!current.isEmpty()) {
                if (current.size() < seats) {
                    List<Stop> candidate = new ArrayList<>(current);
                    candidate.add(next);
                    List<Stop> route = sequencer.sequence(office, candidate);
                    if (Timetable.fits(travel, office, route, params.shift(), params.dwellMinutes(),
                            params.maxRideMinutes())) {
                        current = candidate;
                        currentRoute = route;
                        continue;
                    }
                }
                clusters.add(currentRoute);
                totalCost += close(office, currentRoute, fleet, seatCap);
                seats = fleet.maxAvailableSeats(seatCap);
                current = new ArrayList<>();
            }
            if (seats == 0) {
                return null;
            }
            // A lone employee always gets a cab, even if the trip alone breaks the limit or their window.
            current.add(next);
            currentRoute = List.of(next);
        }
        clusters.add(currentRoute);
        totalCost += close(office, currentRoute, fleet, seatCap);
        return new Solution(clusters, totalCost);
    }

    /** Assigns the cheapest free vehicle within the cap that seats this cluster; returns its trip cost. */
    private double close(GeoPoint office, List<Stop> route, FleetInventory fleet, int seatCap) {
        double km = RouteMetrics.pathKm(travel, office, route);
        VehicleType vehicle = fleet.free().stream()
                .filter(t -> t.seats() >= route.size() && t.seats() <= seatCap)
                .min(Comparator.comparingDouble((VehicleType t) -> t.tripCost(km)).thenComparingInt(VehicleType::seats))
                .orElseThrow(); // the cluster was opened only because a vehicle this size was free
        fleet.take(vehicle);
        return vehicle.tripCost(km);
    }

    /** Angle in radians, with longitude scaled so a degree east equals a degree north locally. */
    static double polarAngle(GeoPoint office, GeoPoint p) {
        double dx = (p.lng() - office.lng()) * Math.cos(Math.toRadians(office.lat()));
        double dy = p.lat() - office.lat();
        return Math.atan2(dy, dx);
    }

    private record Solution(List<List<Stop>> clusters, double totalCost) {

        boolean isBetterThan(Solution other) {
            if (Math.abs(totalCost - other.totalCost) > 1e-9) {
                return totalCost < other.totalCost;
            }
            return clusters.size() < other.clusters.size();
        }
    }
}
