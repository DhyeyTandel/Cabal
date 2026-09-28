package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;

/**
 * A stop in the order the driver visits it, with its ETA and the employee's time on
 * board.
 *
 * @param windowMissed true if the stop has a time-window preference that this ETA does
 *                      not honour
 */
public record StopTiming(Stop stop, LocalDateTime eta, double rideMinutes, boolean windowMissed) {

    /** No window to miss. */
    public StopTiming(Stop stop, LocalDateTime eta, double rideMinutes) {
        this(stop, eta, rideMinutes, false);
    }
}
