package dev.dhyey.cabrouter.api.dto;

import dev.dhyey.cabrouter.routing.Direction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @param shiftTime      shift start for PICKUP, shift end for DROP, in the office's local time
 * @param cabCapacity    shorthand for a fleet of identical, unlimited cabs; defaults to
 *                       routing.default-cab-capacity
 * @param fleet          vehicle types and counts; use this or cabCapacity, not both
 * @param maxRideMinutes optional, defaults to routing.default-max-ride-minutes
 */
public record CreatePlanRequest(
        @NotNull Long officeId,
        @NotNull LocalDateTime shiftTime,
        @NotNull Direction direction,
        @NotEmpty List<@NotNull Long> employeeIds,
        @Min(1) @Max(12) Integer cabCapacity,
        List<@Valid @NotNull VehicleSpec> fleet,
        @Min(10) @Max(240) Integer maxRideMinutes) {
}
