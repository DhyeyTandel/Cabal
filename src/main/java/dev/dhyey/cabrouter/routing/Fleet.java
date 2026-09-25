package dev.dhyey.cabrouter.routing;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The vehicles a shift may use. Each entry is a type and how many are available;
 * a null count means as many as needed.
 */
public record Fleet(List<Entry> entries) {

    public record Entry(VehicleType type, Integer available) {

        public Entry {
            if (available != null && available < 0) {
                throw new IllegalArgumentException("available count cannot be negative");
            }
        }
    }

    public Fleet {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("a fleet needs at least one vehicle type");
        }
        Set<String> names = new HashSet<>();
        for (Entry e : entries) {
            if (!names.add(e.type().name())) {
                throw new IllegalArgumentException("vehicle type " + e.type().name() + " listed twice");
            }
        }
        entries = entries.stream().sorted(Comparator.comparingInt(e -> e.type().seats())).toList();
    }

    /** Any number of identical vehicles. This is what a plain {@code cabCapacity} means. */
    public static Fleet unlimited(String name, int seats) {
        return new Fleet(List.of(new Entry(new VehicleType(name, seats), null)));
    }

    public FleetInventory inventory() {
        return new FleetInventory(this);
    }
}
