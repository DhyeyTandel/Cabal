package dev.dhyey.cabrouter.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.dhyey.cabrouter.domain.CabRoute;
import dev.dhyey.cabrouter.domain.RoutePlan;
import dev.dhyey.cabrouter.domain.RouteStop;
import dev.dhyey.cabrouter.routing.Direction;
import dev.dhyey.cabrouter.routing.TravelSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record PlanResponse(
        long id,
        long officeId,
        LocalDateTime shiftTime,
        Direction direction,
        int revision,
        Instant updatedAt,
        List<FleetLine> fleet,
        int maxRideMinutes,
        int cabCount,
        int employeeCount,
        double totalDistanceKm,
        double totalCost,
        List<Cab> cabs,
        int windowsMissed) {

    /** @param available null when unlimited */
    public record FleetLine(String name, int seats, double costPerTrip, double costPerKm, Integer available, long used) {
    }

    /**
     * @param officeTime   arrival at the office for PICKUP, departure from it for DROP
     * @param travelSource which travel model timed this cab
     */
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
            TravelSource travelSource,
            List<StopView> stops) {
    }

    /**
     * One stop, listed in the order the driver visits it.
     *
     * @param earliestPickup the employee's standing preference, relevant on PICKUP plans
     * @param latestDrop     the employee's standing preference, relevant on DROP plans
     * @param windowMissed   true if the relevant window is set but this ETA does not honour it
     */
    public record StopView(
            int sequence,
            long employeeId,
            String employeeName,
            double latitude,
            double longitude,
            LocalDateTime eta,
            double rideMinutes,
            @JsonFormat(pattern = "HH:mm") LocalTime earliestPickup,
            @JsonFormat(pattern = "HH:mm") LocalTime latestDrop,
            boolean windowMissed) {
    }

    public static PlanResponse from(RoutePlan plan) {
        List<Cab> cabs = plan.getCabs().stream().map(PlanResponse::cab).toList();
        List<FleetLine> fleet = plan.getFleet().stream()
                .map(v -> new FleetLine(v.getName(), v.getSeats(), v.getCostPerTrip(), v.getCostPerKm(), v.getAvailable(),
                        cabs.stream().filter(c -> c.vehicleType().equals(v.getName())).count()))
                .toList();
        return new PlanResponse(
                plan.getId() == null ? 0 : plan.getId(),
                plan.getOffice().getId(),
                plan.getShiftTime(),
                plan.getDirection(),
                plan.getRevision(),
                plan.getUpdatedAt(),
                fleet,
                plan.getMaxRideMinutes(),
                cabs.size(),
                cabs.stream().mapToInt(Cab::seatsUsed).sum(),
                round(plan.getCabs().stream().mapToDouble(CabRoute::getDistanceKm).sum()),
                round(plan.getCabs().stream().mapToDouble(CabRoute::getCost).sum()),
                cabs,
                (int) cabs.stream().flatMap(c -> c.stops().stream()).filter(StopView::windowMissed).count());
    }

    private static Cab cab(CabRoute c) {
        List<StopView> stops = c.getStops().stream().map(PlanResponse::stop).toList();
        return new Cab(c.getCabNumber(), c.getVehicleType(), c.getSeats(), stops.size(), round(c.getDistanceKm()), round(c.getMaxRideMinutes()),
                c.isEscortRequired(), round(c.getCost()), c.getOfficeTime(), c.getTravelSource(), stops);
    }

    private static StopView stop(RouteStop s) {
        return new StopView(s.getSequence(), s.getEmployee().getId(), s.getEmployee().getName(),
                s.location().lat(), s.location().lng(), s.getEta(), round(s.getRideMinutes()),
                s.getEmployee().getEarliestPickup(), s.getEmployee().getLatestDrop(), s.isWindowMissed());
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
