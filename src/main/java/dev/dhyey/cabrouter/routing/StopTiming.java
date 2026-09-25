package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;

/** A stop in the order the driver visits it, with its ETA and the employee's time on board. */
public record StopTiming(Stop stop, LocalDateTime eta, double rideMinutes) {
}
