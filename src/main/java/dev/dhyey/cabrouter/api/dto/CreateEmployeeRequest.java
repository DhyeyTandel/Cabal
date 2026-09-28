package dev.dhyey.cabrouter.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.dhyey.cabrouter.domain.Gender;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/**
 * @param earliestPickup standing preference, used on PICKUP plans: must not be picked
 *                        up before this time; optional
 * @param latestDrop      standing preference, used on DROP plans: must be dropped by
 *                        this time; optional
 */
public record CreateEmployeeRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull Gender gender,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull Long officeId,
        @JsonFormat(pattern = "HH:mm") LocalTime earliestPickup,
        @JsonFormat(pattern = "HH:mm") LocalTime latestDrop) {
}
