package dev.dhyey.cabrouter.api.dto;

import dev.dhyey.cabrouter.domain.CabRoute;
import dev.dhyey.cabrouter.domain.RoutePlan;
import dev.dhyey.cabrouter.domain.RouteStop;
import dev.dhyey.cabrouter.routing.Direction;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record PlanResponse(
        long id,
        long officeId,
        LocalDateTime shiftTime,
        Direction direction,
        int revision,
        Instant updatedAt,
        int cabCapacity,
        int maxRideMinutes,
        int cabCount,
        int employeeCount,
        double totalDistanceKm,
        List<Cab> cabs) {

    /**
     * @param officeTime arrival at the office for PICKUP, departure from it for DROP
     */
    public record Cab(
            int cabNumber,
            int seatsUsed,
            double distanceKm,
            double maxRideMinutes,
            boolean escortRequired,
            LocalDateTime officeTime,
            List<StopView> stops) {
    }

    /** One stop, listed in the order the driver visits it. */
    public record StopView(
            int sequence,
            long employeeId,
            String employeeName,
            double latitude,
            double longitude,
            LocalDateTime eta,
            double rideMinutes) {
    }

    public static PlanResponse from(RoutePlan plan) {
        List<Cab> cabs = plan.getCabs().stream().map(PlanResponse::cab).toList();
        return new PlanResponse(
                plan.getId() == null ? 0 : plan.getId(),
                plan.getOffice().getId(),
                plan.getShiftTime(),
                plan.getDirection(),
                plan.getRevision(),
                plan.getUpdatedAt(),
                plan.getCabCapacity(),
                plan.getMaxRideMinutes(),
                cabs.size(),
                cabs.stream().mapToInt(Cab::seatsUsed).sum(),
                round(plan.getCabs().stream().mapToDouble(CabRoute::getDistanceKm).sum()),
                cabs);
    }

    private static Cab cab(CabRoute c) {
        List<StopView> stops = c.getStops().stream().map(PlanResponse::stop).toList();
        return new Cab(c.getCabNumber(), stops.size(), round(c.getDistanceKm()), round(c.getMaxRideMinutes()),
                c.isEscortRequired(), c.getOfficeTime(), stops);
    }

    private static StopView stop(RouteStop s) {
        return new StopView(s.getSequence(), s.getEmployee().getId(), s.getEmployee().getName(),
                s.location().lat(), s.location().lng(), s.getEta(), round(s.getRideMinutes()));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
