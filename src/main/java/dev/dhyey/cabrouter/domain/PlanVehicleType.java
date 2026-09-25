package dev.dhyey.cabrouter.domain;

import dev.dhyey.cabrouter.routing.Fleet;
import dev.dhyey.cabrouter.routing.VehicleType;
import jakarta.persistence.Embeddable;

/** One line of a plan's fleet, stored in {@code plan_vehicle_types}. */
@Embeddable
public class PlanVehicleType {

    private String name;
    private int seats;

    /** Null means as many as needed. */
    private Integer available;

    protected PlanVehicleType() {
    }

    public PlanVehicleType(String name, int seats, Integer available) {
        this.name = name;
        this.seats = seats;
        this.available = available;
    }

    public Fleet.Entry toEntry() {
        return new Fleet.Entry(new VehicleType(name, seats), available);
    }

    public String getName() {
        return name;
    }

    public int getSeats() {
        return seats;
    }

    public Integer getAvailable() {
        return available;
    }
}
