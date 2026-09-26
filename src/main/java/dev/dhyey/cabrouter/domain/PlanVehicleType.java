package dev.dhyey.cabrouter.domain;

import dev.dhyey.cabrouter.routing.Fleet;
import dev.dhyey.cabrouter.routing.VehicleType;
import jakarta.persistence.Embeddable;

/** One line of a plan's fleet, stored in {@code plan_vehicle_types}. */
@Embeddable
public class PlanVehicleType {

    private String name;
    private int seats;
    private double costPerTrip;
    private double costPerKm;

    /** Null means as many as needed. */
    private Integer available;

    protected PlanVehicleType() {
    }

    public PlanVehicleType(VehicleType type, Integer available) {
        this.name = type.name();
        this.seats = type.seats();
        this.costPerTrip = type.costPerTrip();
        this.costPerKm = type.costPerKm();
        this.available = available;
    }

    public VehicleType type() {
        return new VehicleType(name, seats, costPerTrip, costPerKm);
    }

    public Fleet.Entry toEntry() {
        return new Fleet.Entry(type(), available);
    }

    public String getName() {
        return name;
    }

    public int getSeats() {
        return seats;
    }

    public double getCostPerTrip() {
        return costPerTrip;
    }

    public double getCostPerKm() {
        return costPerKm;
    }

    public Integer getAvailable() {
        return available;
    }
}
