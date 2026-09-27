package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The routed cabs for one office, shift and direction, and the rules for editing them.
 *
 * <p>A ShiftPlan is immutable: {@link #cancel}, {@link #add} and {@link #replan} each
 * return a new plan, carrying over every cab that did not need to change. It has no
 * framework or persistence dependencies, so it can be tested without HTTP or a database;
 * {@code PlanningService} is the only caller, translating to and from JPA entities.
 */
public final class ShiftPlan {

    private final GeoPoint office;
    private final RoutingParams params;
    private final TravelModelProvider travel;
    private final List<Cab> cabs;

    private ShiftPlan(GeoPoint office, RoutingParams params, TravelModelProvider travel, List<Cab> cabs) {
        this.office = office;
        this.params = params;
        this.travel = travel;
        this.cabs = cabs.stream().sorted(Comparator.comparingInt(Cab::cabNumber)).toList();
    }

    /** One cab. Stops are in DRIVING order (the order the driver visits them), with ETAs. */
    public record Cab(int cabNumber, VehicleType vehicle, List<StopTiming> stops, double distanceKm,
                      double maxRideMinutes, boolean escortRequired, double cost,
                      LocalDateTime officeTime, TravelSource timedBy) {

        public Cab {
            stops = List.copyOf(stops);
        }

        public boolean carries(long employeeId) {
            return stops.stream().anyMatch(s -> s.stop().employeeId() == employeeId);
        }
    }

    /** Plans every stop from scratch: {@link RoutePlanner#plan}, cabs numbered 1..k. */
    public static ShiftPlan create(GeoPoint office, RoutingParams params, TravelModelProvider travel,
                                   List<Stop> stops) {
        return new ShiftPlan(office, params, travel, planFromScratch(office, params, travel, stops));
    }

    /** Rebuilds a plan from stored cabs, e.g. loaded from the database. */
    public static ShiftPlan restore(GeoPoint office, RoutingParams params, TravelModelProvider travel,
                                    List<Cab> cabs) {
        return new ShiftPlan(office, params, travel, cabs);
    }

    /**
     * An employee cancels. {@link ReplanStrategy#LOCAL} rebuilds only their cab, keeping
     * its vehicle and cab number (or dropping it if it becomes empty); every other cab is
     * carried over unchanged. {@link ReplanStrategy#FULL} re-plans everyone who remains,
     * numbered 1..k.
     *
     * @throws NotOnPlanException if the employee has no stop on this plan
     */
    public ShiftPlan cancel(long employeeId, ReplanStrategy strategy) {
        Cab cab = cabs.stream().filter(c -> c.carries(employeeId)).findFirst()
                .orElseThrow(() -> new NotOnPlanException(employeeId));

        if (strategy == ReplanStrategy.FULL) {
            List<Stop> remaining = allStops().stream().filter(s -> s.employeeId() != employeeId).toList();
            return new ShiftPlan(office, params, travel, planFromScratch(office, params, travel, remaining));
        }

        List<Stop> remaining = cab.stops().stream().map(StopTiming::stop)
                .filter(s -> s.employeeId() != employeeId).toList();
        List<Cab> result = new ArrayList<>();
        for (Cab c : cabs) {
            if (c.cabNumber() != cab.cabNumber()) {
                result.add(c);
            } else if (!remaining.isEmpty()) {
                // The cab keeps its vehicle even if a smaller one would now do: it is already dispatched.
                TravelEstimate estimate = travel.forPoints(points(office, remaining));
                PlannedCab rebuilt = new RoutePlanner(estimate.model()).buildCab(office, cab.vehicle(), remaining, params);
                result.add(toCab(cab.cabNumber(), rebuilt, estimate, office, params));
            }
            // else: emptied by the cancellation, so it is dropped and the gap is not filled.
        }
        return new ShiftPlan(office, params, travel, result);
    }

    /**
     * A late booking. Current cabs are converted back to outward order and re-measured
     * with this call's travel model (keeping their stored vehicle and escort flag), then
     * {@link RoutePlanner#bestInsertion} places the newcomer. An existing cab that
     * absorbs them keeps its number, even if its vehicle is upgraded; a new cab is
     * numbered one past the highest existing number, never reusing a gap.
     *
     * @throws AlreadyOnPlanException if the employee already has a stop on this plan
     * @throws FleetExhaustedException if no cab or vehicle can take them
     */
    public ShiftPlan add(Stop newcomer) {
        if (cabs.stream().anyMatch(c -> c.carries(newcomer.employeeId()))) {
            throw new AlreadyOnPlanException(newcomer.employeeId());
        }
        List<Stop> withNewcomer = new ArrayList<>(allStops());
        withNewcomer.add(newcomer);
        TravelEstimate estimate = travel.forPoints(points(office, withNewcomer));
        RoutePlanner planner = new RoutePlanner(estimate.model());

        List<PlannedCab> current = cabs.stream().map(c -> toPlanned(c, estimate)).toList();
        RoutePlanner.Insertion insertion = planner.bestInsertion(office, current, newcomer, params);

        List<Cab> result = new ArrayList<>(cabs);
        if (insertion.opensNewCab()) {
            int next = cabs.stream().mapToInt(Cab::cabNumber).max().orElse(0) + 1;
            result.add(toCab(next, insertion.cab(), estimate, office, params));
        } else {
            Cab target = cabs.get(insertion.cabIndex());
            result.set(insertion.cabIndex(), toCab(target.cabNumber(), insertion.cab(), estimate, office, params));
        }
        return new ShiftPlan(office, params, travel, result);
    }

    /** Re-optimises everything, e.g. after several local repairs have drifted from optimal. */
    public ShiftPlan replan() {
        return new ShiftPlan(office, params, travel, planFromScratch(office, params, travel, allStops()));
    }

    /** Every cab, ordered by cab number. */
    public List<Cab> cabs() {
        return cabs;
    }

    /** Plans every stop from scratch against a fresh, whole-shift travel request. */
    private static List<Cab> planFromScratch(GeoPoint office, RoutingParams params, TravelModelProvider travel,
                                             List<Stop> stops) {
        TravelEstimate estimate = travel.forPoints(points(office, stops));
        RoutePlanner planner = new RoutePlanner(estimate.model());
        List<PlannedCab> planned = planner.plan(office, stops, params);
        List<Cab> result = new ArrayList<>(planned.size());
        int number = 1;
        for (PlannedCab pc : planned) {
            result.add(toCab(number++, pc, estimate, office, params));
        }
        return result;
    }

    /** Every stop currently on the plan, cab by cab in driving order. */
    private List<Stop> allStops() {
        return cabs.stream().flatMap(c -> c.stops().stream()).map(StopTiming::stop).toList();
    }

    /**
     * A stored cab's stops, restored to outward order (nearest the office first, as a
     * drop route would visit them; a pickup route is the reverse) and re-measured with
     * the given estimate, so an insertion compares like with like.
     */
    private PlannedCab toPlanned(Cab cab, TravelEstimate estimate) {
        List<Stop> outward = new ArrayList<>(cab.stops().stream().map(StopTiming::stop).toList());
        if (params.shift().direction() == Direction.PICKUP) {
            Collections.reverse(outward);
        }
        return new PlannedCab(cab.vehicle(), outward,
                RouteMetrics.pathKm(estimate.model(), office, outward),
                RouteMetrics.maxRideMinutes(estimate.model(), office, outward, params.dwellMinutes(), params.shift()),
                cab.escortRequired());
    }

    /** Turns a freshly planned or rebuilt cab into the record this plan stores. */
    private static Cab toCab(int cabNumber, PlannedCab planned, TravelEstimate estimate, GeoPoint office,
                             RoutingParams params) {
        List<StopTiming> timings = EtaCalculator.compute(estimate.model(), office, planned.stops(),
                params.shift(), params.dwellMinutes());
        return new Cab(cabNumber, planned.vehicle(), timings, planned.distanceKm(), planned.maxRideMinutes(),
                planned.escortRequired(), params.cost(planned), params.shift().officeTime(), estimate.source());
    }

    private static List<GeoPoint> points(GeoPoint office, Collection<Stop> stops) {
        List<GeoPoint> points = new ArrayList<>(stops.size() + 1);
        points.add(office);
        stops.forEach(s -> points.add(s.location()));
        return points;
    }
}
