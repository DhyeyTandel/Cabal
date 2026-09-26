package dev.dhyey.cabrouter.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Mutable count of vehicles still free while a plan is being built or edited. */
public final class FleetInventory {

    private static final int UNLIMITED = Integer.MAX_VALUE;

    /** Ordered smallest vehicle first, because {@link Fleet} sorts its entries. */
    private final Map<VehicleType, Integer> remaining = new LinkedHashMap<>();

    FleetInventory(Fleet fleet) {
        for (Fleet.Entry e : fleet.entries()) {
            remaining.put(e.type(), e.available() == null ? UNLIMITED : e.available());
        }
    }

    /** Inventory left after the vehicles these cabs already use. */
    public static FleetInventory after(Fleet fleet, List<PlannedCab> inUse) {
        FleetInventory inv = fleet.inventory();
        for (PlannedCab cab : inUse) {
            inv.take(cab.vehicle());
        }
        return inv;
    }

    /** Seats in the largest free vehicle with at most {@code seatCap} seats, or 0 if none. */
    public int maxAvailableSeats(int seatCap) {
        int max = 0;
        for (Map.Entry<VehicleType, Integer> e : remaining.entrySet()) {
            if (e.getValue() > 0 && e.getKey().seats() <= seatCap) {
                max = Math.max(max, e.getKey().seats());
            }
        }
        return max;
    }

    public int maxAvailableSeats() {
        return maxAvailableSeats(Integer.MAX_VALUE);
    }

    /** The smallest free vehicle that seats {@code riders}. */
    public Optional<VehicleType> smallestFitting(int riders) {
        return free().stream().filter(t -> t.seats() >= riders).findFirst();
    }

    /** The free vehicle that seats {@code riders} and costs least for a trip of {@code km}; ties go to fewer seats. */
    public Optional<VehicleType> cheapestFitting(int riders, double km) {
        return free().stream()
                .filter(t -> t.seats() >= riders)
                .min(Comparator.comparingDouble((VehicleType t) -> t.tripCost(km)).thenComparingInt(VehicleType::seats));
    }

    /** Vehicle types with at least one vehicle left, smallest first. */
    public List<VehicleType> free() {
        List<VehicleType> out = new ArrayList<>();
        remaining.forEach((type, left) -> {
            if (left > 0) {
                out.add(type);
            }
        });
        return out;
    }

    public void take(VehicleType type) {
        Integer left = remaining.get(type);
        if (left == null) {
            throw new IllegalArgumentException("vehicle type " + type.name() + " is not in this fleet");
        }
        if (left == 0) {
            throw new FleetExhaustedException("no " + type.name() + " left in the fleet");
        }
        if (left != UNLIMITED) {
            remaining.put(type, left - 1);
        }
    }

    public void release(VehicleType type) {
        Integer left = remaining.get(type);
        if (left == null) {
            throw new IllegalArgumentException("vehicle type " + type.name() + " is not in this fleet");
        }
        if (left != UNLIMITED) {
            remaining.put(type, left + 1);
        }
    }
}
