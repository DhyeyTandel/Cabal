package dev.dhyey.cabrouter.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.dhyey.cabrouter.routing.Direction;
import dev.dhyey.cabrouter.routing.GeoPoint;
import dev.dhyey.cabrouter.routing.ShiftPlan;
import dev.dhyey.cabrouter.routing.Stop;
import dev.dhyey.cabrouter.routing.StopTiming;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The result of one sandbox plan, built straight from {@link ShiftPlan#cabs()}: no entities, nothing stored. */
public record SandboxPlanResponse(
        Direction direction,
        LocalDateTime shiftTime,
        double officeLatitude,
        double officeLongitude,
        int cabCount,
        int riderCount,
        double totalDistanceKm,
        double totalCost,
        int windowsMissed,
        List<Cab> cabs) {

    public record Cab(
            int cabNumber,
            String vehicleType,
            int seats,
            int seatsUsed,
            double distanceKm,
            double maxRideMinutes,
            boolean escortRequired,
            double cost,
            LocalDateTime officeTime,
            List<String> legs,
            List<StopView> stops) {
    }

    /**
     * One stop, in the order the driver visits it.
     *
     * @param riderNumber the rider's 1-based position in the request
     * @param name        "Rider N"
     */
    public record StopView(
            int sequence,
            long riderNumber,
            String name,
            double latitude,
            double longitude,
            LocalDateTime eta,
            double rideMinutes,
            @JsonFormat(pattern = "HH:mm") LocalTime earliestPickup,
            @JsonFormat(pattern = "HH:mm") LocalTime latestDrop,
            boolean windowMissed,
            boolean woman) {
    }

    /**
     * @param legs road geometry per cab number, polyline6-encoded per route segment; a cab missing from the map has none
     */
    public static SandboxPlanResponse from(Direction direction, LocalDateTime shiftTime, GeoPoint office, ShiftPlan plan,
                                           Map<Integer, List<String>> legs) {
        List<Cab> cabs = plan.cabs().stream()
                .map(c -> cab(c, legs.getOrDefault(c.cabNumber(), List.of()))).toList();
        int riderCount = cabs.stream().mapToInt(Cab::seatsUsed).sum();
        double totalDistanceKm = round(plan.cabs().stream().mapToDouble(ShiftPlan.Cab::distanceKm).sum());
        double totalCost = round(plan.cabs().stream().mapToDouble(ShiftPlan.Cab::cost).sum());
        int windowsMissed = (int) cabs.stream().flatMap(c -> c.stops().stream()).filter(StopView::windowMissed).count();
        return new SandboxPlanResponse(direction, shiftTime, office.lat(), office.lng(),
                cabs.size(), riderCount, totalDistanceKm, totalCost, windowsMissed, cabs);
    }

    private static Cab cab(ShiftPlan.Cab c, List<String> legs) {
        List<StopView> stops = new ArrayList<>(c.stops().size());
        int sequence = 1;
        for (StopTiming t : c.stops()) {
            stops.add(stop(sequence++, t));
        }
        return new Cab(c.cabNumber(), c.vehicle().name(), c.vehicle().seats(), stops.size(), round(c.distanceKm()),
                round(c.maxRideMinutes()), c.escortRequired(), round(c.cost()), c.officeTime(), legs, stops);
    }

    private static StopView stop(int sequence, StopTiming t) {
        Stop s = t.stop();
        return new StopView(sequence, s.employeeId(), "Rider " + s.employeeId(), s.location().lat(), s.location().lng(),
                t.eta(), round(t.rideMinutes()), s.earliestPickup(), s.latestDrop(), t.windowMissed(), s.escortSensitive());
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
