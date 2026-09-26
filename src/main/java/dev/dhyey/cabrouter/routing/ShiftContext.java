package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;

/**
 * When and which way a shift's cabs run, the night window used by the escort rule, and
 * the traffic that legs are timed against.
 *
 * <p>Night is judged per cab by the time of its <em>far-end</em> stop (the first pickup
 * or the last drop), not by the shift time. A 07:30 shift is a day shift, but a cab
 * that starts collecting people at 06:10 is out in the dark.
 *
 * @param officeTime     PICKUP: when cabs must reach the office; DROP: when they leave it
 * @param nightStartHour inclusive, 0 to 23; the window may wrap midnight (20 to 7)
 * @param nightEndHour   exclusive
 */
public record ShiftContext(Direction direction, LocalDateTime officeTime, int nightStartHour, int nightEndHour,
                           TrafficProfile traffic) {

    public ShiftContext {
        if (nightStartHour < 0 || nightStartHour > 23 || nightEndHour < 0 || nightEndHour > 23) {
            throw new IllegalArgumentException("night hours must be 0 to 23");
        }
        if (traffic == null) {
            throw new IllegalArgumentException("a shift needs a traffic profile");
        }
    }

    /** Free-flow traffic all day: drive times are exactly what the travel model says. */
    public ShiftContext(Direction direction, LocalDateTime officeTime, int nightStartHour, int nightEndHour) {
        this(direction, officeTime, nightStartHour, nightEndHour, TrafficProfile.flat(1.0));
    }

    /**
     * Time the cab is at the far-end stop, given that stop's ride minutes. Uses the same
     * arithmetic as {@link EtaCalculator}.
     */
    public LocalDateTime farEndTime(double farEndRideMinutes, double dwellMinutes) {
        return direction == Direction.PICKUP
                ? officeTime.minusMinutes(Math.round(farEndRideMinutes + dwellMinutes))
                : officeTime.plusMinutes(Math.round(farEndRideMinutes));
    }

    public boolean isNight(LocalDateTime time) {
        int h = time.getHour();
        if (nightStartHour == nightEndHour) {
            return false;
        }
        return nightStartHour > nightEndHour
                ? h >= nightStartHour || h < nightEndHour
                : h >= nightStartHour && h < nightEndHour;
    }
}
