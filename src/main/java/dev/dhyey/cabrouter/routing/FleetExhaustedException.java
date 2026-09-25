package dev.dhyey.cabrouter.routing;

/** The fleet has too few vehicles or seats to route everyone within the ride limit. */
public class FleetExhaustedException extends RuntimeException {

    public FleetExhaustedException(String message) {
        super(message);
    }
}
