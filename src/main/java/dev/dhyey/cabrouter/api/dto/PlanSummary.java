package dev.dhyey.cabrouter.api.dto;

import dev.dhyey.cabrouter.domain.CabRoute;
import dev.dhyey.cabrouter.domain.RoutePlan;
import dev.dhyey.cabrouter.routing.Direction;
import java.time.LocalDateTime;

/** One row of the plan list shown on the website: {@code GET /api/plans}. */
public record PlanSummary(
        long id,
        long officeId,
        String officeName,
        LocalDateTime shiftTime,
        Direction direction,
        int revision,
        int cabCount,
        int employeeCount,
        double totalDistanceKm,
        double totalCost) {

    public static PlanSummary from(RoutePlan plan) {
        int employeeCount = plan.getCabs().stream().mapToInt(c -> c.getStops().size()).sum();
        double totalDistanceKm = plan.getCabs().stream().mapToDouble(CabRoute::getDistanceKm).sum();
        double totalCost = plan.getCabs().stream().mapToDouble(CabRoute::getCost).sum();
        return new PlanSummary(
                plan.getId(),
                plan.getOffice().getId(),
                plan.getOffice().getName(),
                plan.getShiftTime(),
                plan.getDirection(),
                plan.getRevision(),
                plan.getCabs().size(),
                employeeCount,
                round(totalDistanceKm),
                round(totalCost));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
