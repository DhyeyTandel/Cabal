package dev.dhyey.cabrouter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the routing core, bound from {@code routing.*} in application.properties. */
@ConfigurationProperties("routing")
public record RoutingProperties(
        double circuityFactor,
        double averageSpeedKmph,
        double dwellMinutes,
        int arrivalBufferMinutes,
        int departureBufferMinutes,
        int nightStartHour,
        int nightEndHour,
        double escortDetourTolerance,
        int sweepStarts,
        int defaultCabCapacity,
        int defaultMaxRideMinutes) {
}
