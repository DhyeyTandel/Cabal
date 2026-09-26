package dev.dhyey.cabrouter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the routing core, bound from {@code routing.*} in application.properties. */
@ConfigurationProperties("routing")
public record RoutingProperties(
        double circuityFactor,
        double freeFlowSpeedKmph,
        double[] trafficHourlyFactors,
        double dwellMinutes,
        int arrivalBufferMinutes,
        int departureBufferMinutes,
        int nightStartHour,
        int nightEndHour,
        double escortDetourTolerance,
        int sweepStarts,
        double escortCost,
        int defaultCabCapacity,
        int defaultMaxRideMinutes,
        TravelModelKind travelModel,
        Osrm osrm) {

    public enum TravelModelKind {
        HAVERSINE,
        OSRM
    }

    /**
     * @param baseUrl        OSRM server, for example http://localhost:5000
     * @param maxTableSize   coordinates per table request the server accepts
     * @param timeoutSeconds connect and read timeout per request
     */
    public record Osrm(String baseUrl, int maxTableSize, int timeoutSeconds) {
    }
}
