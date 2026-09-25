package dev.dhyey.cabrouter.routing;

public enum Direction {
    /** Homes to office; the route must reach the office before the shift starts. */
    PICKUP,
    /** Office to homes; the route leaves the office after the shift ends. */
    DROP
}
