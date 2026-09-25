package dev.dhyey.cabrouter.routing;

import java.util.List;

/**
 * A cab's stops in <em>outward</em> order: nearest the office first, as a drop route
 * would visit them. A pickup route visits the same stops in reverse.
 */
public record PlannedCab(
        List<Stop> stops,
        double distanceKm,
        double maxRideMinutes,
        boolean escortRequired) {

    public PlannedCab {
        stops = List.copyOf(stops);
    }

    public boolean contains(long employeeId) {
        return stops.stream().anyMatch(s -> s.employeeId() == employeeId);
    }
}
