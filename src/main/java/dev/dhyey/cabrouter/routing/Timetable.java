package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Ride minutes and ETAs for an outward route, computed once and shared by every caller
 * that needs either.
 *
 * <p>Two things make a leg's time depend on the shift:
 * <ul>
 *   <li><b>Direction.</b> Drive times may be directed (one-way streets), so legs are
 *       timed the way the cab drives them: outward for DROP, back towards the
 *       office for PICKUP.</li>
 *   <li><b>Clock time.</b> Each leg's free-flow time is scaled by the traffic factor
 *       at the moment the cab is at the leg's office-side end. For DROP that is when
 *       it sets off on the leg, walking forwards from departure. For PICKUP it is
 *       when it finishes the leg, walking backwards from the required arrival. Both
 *       are known at that point in the walk, so no iteration is needed.</li>
 * </ul>
 *
 * <p>{@link #of} computes only the ride-minutes array eagerly; {@link #stopsInDrivingOrder()}
 * builds {@link StopTiming} and {@link LocalDateTime} objects lazily, on first call, since
 * the sweep calls {@link #maxRideMinutes()} thousands of times and must not allocate ETAs.
 */
public final class Timetable {

    private final List<Stop> outward;
    private final ShiftContext shift;
    private final double dwellMinutes;
    private final double[] ride;

    private List<StopTiming> drivingOrder;

    private Timetable(List<Stop> outward, ShiftContext shift, double dwellMinutes, double[] ride) {
        this.outward = outward;
        this.shift = shift;
        this.dwellMinutes = dwellMinutes;
        this.ride = ride;
    }

    /**
     * @param outwardStops stops nearest the office first; timed in the direction the cab
     *                      drives (shift.direction())
     */
    public static Timetable of(TravelModel travel, GeoPoint office, List<Stop> outwardStops,
                               ShiftContext shift, double dwellMinutes) {
        List<Stop> outward = List.copyOf(outwardStops);
        return new Timetable(outward, shift, dwellMinutes, rideMinutes(travel, office, outward, shift, dwellMinutes));
    }

    /**
     * The longest ride on this route, without building a Timetable. The sweep asks this
     * thousands of times per plan, so it skips the defensive copy and the object; it runs
     * the same {@link #rideMinutes} walk, so it always equals {@code of(...).maxRideMinutes()}.
     */
    public static double maxRideMinutes(TravelModel travel, GeoPoint office, List<Stop> outwardStops,
                                        ShiftContext shift, double dwellMinutes) {
        double[] ride = rideMinutes(travel, office, outwardStops, shift, dwellMinutes);
        return ride.length == 0 ? 0 : ride[ride.length - 1];
    }

    /**
     * Whether a route both stays within the ride limit and honours every stop's time
     * window, without building a Timetable. Like {@link #maxRideMinutes}, this runs a
     * single {@link #rideMinutes} walk; a route with no windowed stops does exactly that
     * walk plus the ride-limit comparison, nothing more.
     */
    public static boolean fits(TravelModel travel, GeoPoint office, List<Stop> outward, ShiftContext shift,
                               double dwell, double maxRideMinutes) {
        double[] ride = rideMinutes(travel, office, outward, shift, dwell);
        if (ride.length > 0 && ride[ride.length - 1] > maxRideMinutes) {
            return false;
        }
        for (int k = 0; k < outward.size(); k++) {
            if (!windowHolds(outward.get(k), ride, k, shift, dwell)) {
                return false;
            }
        }
        return true;
    }

    private static double[] rideMinutes(TravelModel travel, GeoPoint office, List<Stop> outward,
                                        ShiftContext shift, double dwellMinutes) {
        double[] ride = new double[outward.size()];
        double elapsed = 0;
        GeoPoint prev = office;
        for (int k = 0; k < outward.size(); k++) {
            GeoPoint here = outward.get(k).location();
            // Minutes between the office time and the cab being at the office-side end of this leg.
            double offset = k == 0 ? 0 : ride[k - 1] + dwellMinutes;
            double factor;
            double freeFlow;
            if (shift.direction() == Direction.DROP) {
                factor = shift.traffic().factorAt(shift.officeTime().plusSeconds(Math.round(offset * 60)));
                freeFlow = travel.minutes(prev, here);
            } else {
                factor = shift.traffic().factorAt(shift.officeTime().minusSeconds(Math.round(offset * 60)));
                freeFlow = travel.minutes(here, prev);
            }
            elapsed += freeFlow * factor;
            ride[k] = elapsed + k * dwellMinutes;
            prev = here;
        }
        return ride;
    }

    /**
     * Stops in driving order, with ETA and ride minutes: PICKUP visits them outward
     * reversed (farthest stop first, working backwards from the office arrival time);
     * DROP visits them in outward order (nearest stop first). The cab reaches a pickup
     * stop, waits {@code dwellMinutes} for boarding, then the ride begins.
     */
    public List<StopTiming> stopsInDrivingOrder() {
        if (drivingOrder == null) {
            List<StopTiming> timings = new ArrayList<>(outward.size());
            if (shift.direction() == Direction.DROP) {
                for (int k = 0; k < outward.size(); k++) {
                    timings.add(new StopTiming(outward.get(k), etaFor(k), ride[k], !windowHolds(k)));
                }
            } else {
                for (int k = outward.size() - 1; k >= 0; k--) {
                    timings.add(new StopTiming(outward.get(k), etaFor(k), ride[k], !windowHolds(k)));
                }
            }
            drivingOrder = List.copyOf(timings);
        }
        return drivingOrder;
    }

    /** The farthest stop always rides longest, so this is the last entry; 0 if no stops. */
    public double maxRideMinutes() {
        return ride.length == 0 ? 0 : ride[ride.length - 1];
    }

    /**
     * True if every stop with a time-window preference is honoured by this route's ETAs.
     * A stop with no window is skipped without doing any date maths.
     */
    public boolean meetsWindows() {
        for (int k = 0; k < outward.size(); k++) {
            if (!windowHolds(k)) {
                return false;
            }
        }
        return true;
    }

    /**
     * When the cab is at the farthest stop: the first pickup (PICKUP) or last drop
     * (DROP). This is the ETA of that stop computed by the same code path as
     * {@link #stopsInDrivingOrder()}, so it cannot drift from the ETAs.
     *
     * @throws IllegalStateException if there are no stops
     */
    public LocalDateTime farEndTime() {
        if (ride.length == 0) {
            throw new IllegalStateException("no stops to time");
        }
        return etaFor(ride.length - 1);
    }

    private LocalDateTime etaFor(int k) {
        return etaFor(ride, k, shift, dwellMinutes);
    }

    private boolean windowHolds(int k) {
        return windowHolds(outward.get(k), ride, k, shift, dwellMinutes);
    }

    /** DROP stop k ETA = officeTime + round(ride[k]); PICKUP stop k ETA = officeTime - round(ride[k] + dwell). */
    private static LocalDateTime etaFor(double[] ride, int k, ShiftContext shift, double dwellMinutes) {
        return shift.direction() == Direction.DROP
                ? shift.officeTime().plusMinutes(roundedOffsetMinutes(ride, k, shift, dwellMinutes))
                : shift.officeTime().minusMinutes(roundedOffsetMinutes(ride, k, shift, dwellMinutes));
    }

    /**
     * Stop k's minute offset from officeTime, rounded the same way an ETA is: DROP rounds
     * the ride minutes alone, PICKUP rounds them together with the boarding dwell. Shared
     * by {@link #etaFor} and the window check so a stop shown at, say, 06:00 is never
     * judged against an unrounded 05:59.6.
     */
    private static long roundedOffsetMinutes(double[] ride, int k, ShiftContext shift, double dwellMinutes) {
        return shift.direction() == Direction.DROP
                ? Math.round(ride[k])
                : Math.round(ride[k] + dwellMinutes);
    }

    /**
     * Whether stop k's own time window, if it has one for this shift's direction, is
     * honoured by its ETA. A stop without the relevant window always holds, and is
     * checked with no date maths: the null check short-circuits before {@link #etaFor}
     * is ever called.
     */
    private static boolean windowHolds(Stop stop, double[] ride, int k, ShiftContext shift, double dwellMinutes) {
        if (shift.direction() == Direction.PICKUP) {
            LocalTime earliest = stop.earliestPickup();
            return earliest == null
                    || !etaFor(ride, k, shift, dwellMinutes).isBefore(resolvePickupEarliest(earliest, shift.officeTime()));
        }
        LocalTime latest = stop.latestDrop();
        return latest == null
                || !etaFor(ride, k, shift, dwellMinutes).isAfter(resolveDropLatest(latest, shift.officeTime()));
    }

    /**
     * Places a PICKUP earliest-time preference on the calendar: that time on the office
     * time's date, or the day before if that would put it after the office time (a
     * pickup happens before the office is reached, so the preference must too).
     */
    private static LocalDateTime resolvePickupEarliest(LocalTime earliest, LocalDateTime officeTime) {
        LocalDateTime candidate = officeTime.toLocalDate().atTime(earliest);
        return candidate.isAfter(officeTime) ? candidate.minusDays(1) : candidate;
    }

    /**
     * Places a DROP latest-time preference on the calendar: that time on the office
     * time's date, or the day after if that would put it before the office time (a drop
     * happens after the cab leaves the office, so the deadline must too) -- e.g. a 22:10
     * departure with "by 00:30" means 00:30 the next day.
     */
    private static LocalDateTime resolveDropLatest(LocalTime latest, LocalDateTime officeTime) {
        LocalDateTime candidate = officeTime.toLocalDate().atTime(latest);
        return candidate.isBefore(officeTime) ? candidate.plusDays(1) : candidate;
    }
}
