package dev.dhyey.cabrouter.routing;

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

    /** Seats in the largest vehicle still free, or 0 if none are. */
    public int maxAvailableSeats() {
        int max = 0;
        for (Map.Entry<VehicleType, Integer> e : remaining.entrySet()) {
            if (e.getValue() > 0) {
                max = Math.max(max, e.getKey().seats());
            }
        }
        return max;
    }

    /** The smallest free vehicle that seats {@code riders}. Smaller cabs cost less to run. */
    public Optional<VehicleType> smallestFitting(int riders) {
        return remaining.entrySet().stream()
                .filter(e -> e.getValue() > 0 && e.getKey().seats() >= riders)
                .map(Map.Entry::getKey)
                .findFirst();
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
}
