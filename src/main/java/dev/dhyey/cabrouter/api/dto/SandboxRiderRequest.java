package dev.dhyey.cabrouter.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

/**
 * One rider dropped on the sandbox map. Nothing here is persisted.
 *
 * @param woman          true if this rider needs an escort on a night shift, same rule as
 *                        {@code Employee.escortSensitive}
 * @param earliestPickup standing preference, used on PICKUP plans; optional
 * @param latestDrop     standing preference, used on DROP plans; optional
 */
public record SandboxRiderRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        boolean woman,
        @JsonFormat(pattern = "HH:mm") LocalTime earliestPickup,
        @JsonFormat(pattern = "HH:mm") LocalTime latestDrop) {
}
