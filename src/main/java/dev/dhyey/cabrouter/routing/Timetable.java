package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;
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
        return new Timetable(outward, shift, dwellMinutes, ride);
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
                    timings.add(new StopTiming(outward.get(k), etaFor(k), ride[k]));
                }
            } else {
                for (int k = outward.size() - 1; k >= 0; k--) {
                    timings.add(new StopTiming(outward.get(k), etaFor(k), ride[k]));
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

    /** DROP stop k ETA = officeTime + round(ride[k]); PICKUP stop k ETA = officeTime - round(ride[k] + dwell). */
    private LocalDateTime etaFor(int k) {
        return shift.direction() == Direction.DROP
                ? shift.officeTime().plusMinutes(Math.round(ride[k]))
                : shift.officeTime().minusMinutes(Math.round(ride[k] + dwellMinutes));
    }
}
