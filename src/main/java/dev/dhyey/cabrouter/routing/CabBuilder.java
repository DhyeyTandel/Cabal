package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds one cab: sequences its stops, applies the night escort rule, and times the
 * result. The office and routing params are bound once per plan, so callers pass only
 * what changes from cab to cab: the vehicle and its stops.
 */
final class CabBuilder {

    private final TravelModel travel;
    private final StopSequencer sequencer;
    private final GeoPoint office;
    private final RoutingParams params;

    CabBuilder(TravelModel travel, StopSequencer sequencer, GeoPoint office, RoutingParams params) {
        this.travel = travel;
        this.sequencer = sequencer;
        this.office = office;
        this.params = params;
    }

    /**
     * Sequences one cab and applies the night escort rule. The rule looks at the stop
     * farthest from the office (the first pickup, or the last drop). If the cab is there
     * at night and that rider is escort-sensitive, we try ending the route at each other
     * rider instead. We accept the cheapest reorder that stays within the detour
     * tolerance and does not lengthen anyone's ride past the limit. If no reorder
     * qualifies, the cab is flagged as needing a guard.
     */
    PlannedCab build(VehicleType vehicle, List<Stop> stops) {
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("a cab needs at least one stop");
        }
        if (stops.size() > vehicle.seats()) {
            throw new IllegalArgumentException(
                    vehicle.name() + " over capacity: " + stops.size() + " > " + vehicle.seats());
        }
        List<Stop> route = sequencer.sequence(office, stops);
        Timetable timetable = Timetable.of(travel, office, route, params.shift(), params.dwellMinutes());
        boolean escortRequired = false;

        if (route.get(route.size() - 1).escortSensitive() && params.shift().isNight(timetable.farEndTime())) {
            List<Stop> repaired = escortSafeRoute(stops, route);
            if (repaired != null) {
                route = repaired;
                timetable = Timetable.of(travel, office, route, params.shift(), params.dwellMinutes());
            } else {
                escortRequired = true;
            }
        }
        return new PlannedCab(vehicle, route, RouteMetrics.pathKm(travel, office, route), timetable, escortRequired);
    }

    private List<Stop> escortSafeRoute(List<Stop> stops, List<Stop> unconstrained) {
        double baseKm = RouteMetrics.pathKm(travel, office, unconstrained);
        double rideLimit = Math.max(params.maxRideMinutes(),
                Timetable.of(travel, office, unconstrained, params.shift(), params.dwellMinutes()).maxRideMinutes());

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
            double ride = Timetable.of(travel, office, candidate, params.shift(), params.dwellMinutes()).maxRideMinutes();
            if (km <= bestKm + 1e-9 && ride <= rideLimit) {
                best = candidate;
                bestKm = km;
            }
        }
        return best;
    }
}
