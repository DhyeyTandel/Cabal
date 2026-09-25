package dev.dhyey.cabrouter.routing;

/** A kind of vehicle, for example SEDAN with 4 seats. Seats exclude the driver. */
public record VehicleType(String name, int seats) {

    public VehicleType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("vehicle type needs a name");
        }
        if (seats < 1) {
            throw new IllegalArgumentException("a vehicle needs at least one seat");
        }
    }
}
