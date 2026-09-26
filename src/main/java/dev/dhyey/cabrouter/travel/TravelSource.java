package dev.dhyey.cabrouter.travel;

/** Which model timed a cab's route. Stored per cab and returned in the API. */
public enum TravelSource {
    /** Straight-line distance times a road factor, at a flat speed. The default. */
    HAVERSINE,
    /** Road-network distances and times from OSRM. */
    OSRM,
    /** OSRM was configured but failed, so the route was timed by haversine instead. */
    HAVERSINE_FALLBACK
}
