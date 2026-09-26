package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * How much slower than free-flow traffic is at each hour of the day. A factor of 2.0
 * means a leg takes twice as long as on empty roads.
 *
 * <p>Factors are given for each whole hour and interpolated linearly in between, so a
 * cab leaving at 08:30 sees a factor halfway between the 08:00 and 09:00 values, rather
 * than a jump on the hour. 23:30 interpolates towards 00:00.
 */
public final class TrafficProfile {

    private final double[] hourly;

    /** @param hourly 24 factors, for 00:00 to 23:00; each must be at least 1 */
    public TrafficProfile(double[] hourly) {
        if (hourly.length != 24) {
            throw new IllegalArgumentException("a traffic profile needs 24 hourly factors, got " + hourly.length);
        }
        for (double f : hourly) {
            if (f < 1.0) {
                throw new IllegalArgumentException("traffic factors must be at least 1 (free flow), got " + f);
            }
        }
        this.hourly = hourly.clone();
    }

    /** The same factor all day. */
    public static TrafficProfile flat(double factor) {
        double[] h = new double[24];
        Arrays.fill(h, factor);
        return new TrafficProfile(h);
    }

    public double factorAt(LocalDateTime time) {
        int h = time.getHour();
        double frac = (time.getMinute() * 60 + time.getSecond()) / 3600.0;
        return hourly[h] * (1 - frac) + hourly[(h + 1) % 24] * frac;
    }
}
