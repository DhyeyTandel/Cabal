package dev.dhyey.cabrouter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * @param available   how many of this vehicle exist; omit for as many as needed
 * @param costPerTrip fixed charge per trip; omit for the default (1000)
 * @param costPerKm   charge per km; omit for the default (15)
 */
public record VehicleSpec(
        @NotBlank @Size(max = 40) String name,
        @NotNull @Min(1) @Max(12) Integer seats,
        @Min(0) Integer available,
        @PositiveOrZero Double costPerTrip,
        @PositiveOrZero Double costPerKm) {
}
