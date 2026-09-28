package dev.dhyey.cabrouter.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.dhyey.cabrouter.routing.Direction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.List;

/**
 * Body of {@code POST /api/sandbox/plan}. Riders are planned on a fixed demo date against
 * the fixed office in {@code SandboxService}; nothing is persisted.
 *
 * @param shiftTime shift start for PICKUP, shift end for DROP, "HH:mm"
 */
public record SandboxPlanRequest(
        @NotNull Direction direction,
        @NotNull @JsonFormat(pattern = "HH:mm") LocalTime shiftTime,
        @NotNull SandboxFleet fleet,
        @NotEmpty List<@Valid @NotNull SandboxRiderRequest> riders) {
}
