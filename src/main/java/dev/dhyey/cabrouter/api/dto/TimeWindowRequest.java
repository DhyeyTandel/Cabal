package dev.dhyey.cabrouter.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalTime;

/**
 * Body of {@code PUT /api/employees/{id}/time-window}. Both fields are optional; a
 * missing or null value clears that preference.
 */
public record TimeWindowRequest(
        @JsonFormat(pattern = "HH:mm") LocalTime earliestPickup,
        @JsonFormat(pattern = "HH:mm") LocalTime latestDrop) {
}
