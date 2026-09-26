package dev.dhyey.cabrouter.routing;

/**
 * A kind of vehicle and what it costs, for example a 4-seat SEDAN at 800 per trip plus
 * 14 per km. Seats exclude the driver. Costs are in whatever currency the fleet is
 * priced in.
 *
 * <p>When a fleet gives no prices, the defaults make one vehicle cost as much as about
 * 67 km of driving. The planner then behaves like "fewest vehicles first, then fewest
 * km", which is what it did before costs existed.
 */
public record VehicleType(String name, int seats, double costPerTrip, double costPerKm) {

    public static final double DEFAULT_COST_PER_TRIP = 1000;
    public static final double DEFAULT_COST_PER_KM = 15;

    public VehicleType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("vehicle type needs a name");
        }
        if (seats < 1) {
            throw new IllegalArgumentException("a vehicle needs at least one seat");
        }
        if (costPerTrip < 0 || costPerKm < 0) {
            throw new IllegalArgumentException("costs cannot be negative");
        }
    }

    public VehicleType(String name, int seats) {
        this(name, seats, DEFAULT_COST_PER_TRIP, DEFAULT_COST_PER_KM);
    }

    public double tripCost(double km) {
        return costPerTrip + costPerKm * km;
    }
}
