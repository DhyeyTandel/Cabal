package dev.dhyey.cabrouter.routing;

import java.time.LocalTime;

/**
 * One employee's home location.
 *
 * @param escortSensitive true if this employee must not be alone with the driver on a
 *                        night shift (first pickup or last drop), per the transport
 *                        safety rules Indian states apply to women employees
 * @param earliestPickup  standing preference, used on PICKUP plans: must not be picked
 *                        up before this time of day; null if none
 * @param latestDrop      standing preference, used on DROP plans: must be dropped by
 *                        this time of day; null if none
 */
public record Stop(long employeeId, GeoPoint location, boolean escortSensitive,
                   LocalTime earliestPickup, LocalTime latestDrop) {

    /** No time-window preferences. */
    public Stop(long employeeId, GeoPoint location, boolean escortSensitive) {
        this(employeeId, location, escortSensitive, null, null);
    }
}
