package dev.dhyey.cabrouter.routing;

import java.util.List;

/**
 * A cab's vehicle and its stops in <em>outward</em> order: nearest the office first,
 * as a drop route would visit them. A pickup route visits the same stops in reverse.
 */
public record PlannedCab(
        VehicleType vehicle,
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

    public boolean hasFreeSeat() {
        return stops.size() < vehicle.seats();
    }

    public PlannedCab withVehicle(VehicleType other) {
        return new PlannedCab(other, stops, distanceKm, maxRideMinutes, escortRequired);
    }
}
