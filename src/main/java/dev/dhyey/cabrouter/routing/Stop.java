package dev.dhyey.cabrouter.routing;

/**
 * One employee's home location.
 *
 * @param escortSensitive true if this employee must not be alone with the driver on a
 *                        night shift (first pickup or last drop), per the transport
 *                        safety rules Indian states apply to women employees
 */
public record Stop(long employeeId, GeoPoint location, boolean escortSensitive) {
}
