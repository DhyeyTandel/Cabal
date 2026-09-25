package dev.dhyey.cabrouter.config;

import java.time.LocalTime;
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

    /** Night window may wrap midnight (20 to 7) or not (0 to 6). */
    public boolean isNight(LocalTime time) {
        int h = time.getHour();
        return nightStartHour > nightEndHour
                ? h >= nightStartHour || h < nightEndHour
                : h >= nightStartHour && h < nightEndHour;
    }
}
