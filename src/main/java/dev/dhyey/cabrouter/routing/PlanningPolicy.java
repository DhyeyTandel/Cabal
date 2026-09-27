package dev.dhyey.cabrouter.routing;

import java.time.LocalDateTime;

/**
 * The routing policy for the whole application: every tunable that used to live scattered
 * across {@code PlanningService}, assembled once at startup. Adding or changing a routing
 * rule means changing this class (or the properties it is built from), not the service that
 * calls it.
 */
public final class PlanningPolicy {

    private final double dwellMinutes;
    private final int arrivalBufferMinutes;
    private final int departureBufferMinutes;
    private final int nightStartHour;
    private final int nightEndHour;
    private final TrafficProfile traffic;
    private final double escortDetourTolerance;
    private final int sweepStarts;
    private final double escortCost;
    private final int defaultCabCapacity;
    private final int defaultMaxRideMinutes;
    private final int maxRidersPerPlan;

    public PlanningPolicy(double dwellMinutes, int arrivalBufferMinutes, int departureBufferMinutes,
                          int nightStartHour, int nightEndHour, TrafficProfile traffic,
                          double escortDetourTolerance, int sweepStarts, double escortCost,
                          int defaultCabCapacity, int defaultMaxRideMinutes, int maxRidersPerPlan) {
        if (arrivalBufferMinutes < 0 || departureBufferMinutes < 0) {
            throw new IllegalArgumentException("buffers cannot be negative");
        }
        if (defaultCabCapacity <= 0) {
            throw new IllegalArgumentException("default cab capacity must be positive");
        }
        if (defaultMaxRideMinutes <= 0) {
            throw new IllegalArgumentException("default max ride minutes must be positive");
        }
        if (maxRidersPerPlan <= 0) {
            throw new IllegalArgumentException("max riders per plan must be positive");
        }
        if (traffic == null) {
            throw new IllegalArgumentException("a policy needs a traffic profile");
        }
        this.dwellMinutes = dwellMinutes;
        this.arrivalBufferMinutes = arrivalBufferMinutes;
        this.departureBufferMinutes = departureBufferMinutes;
        this.nightStartHour = nightStartHour;
        this.nightEndHour = nightEndHour;
        this.traffic = traffic;
        this.escortDetourTolerance = escortDetourTolerance;
        this.sweepStarts = sweepStarts;
        this.escortCost = escortCost;
        this.defaultCabCapacity = defaultCabCapacity;
        this.defaultMaxRideMinutes = defaultMaxRideMinutes;
        this.maxRidersPerPlan = maxRidersPerPlan;
    }

    /**
     * Everything the routing core needs for one plan. Office time: PICKUP = shiftTime minus
     * the arrival buffer; DROP = shiftTime plus the departure buffer.
     */
    public RoutingParams paramsFor(Direction direction, LocalDateTime shiftTime, Fleet fleet, int maxRideMinutes) {
        LocalDateTime officeTime = direction == Direction.PICKUP
                ? shiftTime.minusMinutes(arrivalBufferMinutes)
                : shiftTime.plusMinutes(departureBufferMinutes);
        return new RoutingParams(fleet, maxRideMinutes, dwellMinutes,
                new ShiftContext(direction, officeTime, nightStartHour, nightEndHour, traffic),
                escortDetourTolerance, sweepStarts, escortCost);
    }

    /** An unlimited fleet of identical "CAB" vehicles at default prices; null means the configured default seats. */
    public Fleet defaultFleet(Integer cabCapacity) {
        int seats = cabCapacity != null ? cabCapacity : defaultCabCapacity;
        return Fleet.unlimited("CAB", seats);
    }

    /** The requested max ride, or the configured default when null. */
    public int maxRideMinutesOr(Integer requested) {
        return requested != null ? requested : defaultMaxRideMinutes;
    }

    /** The most riders a single plan may carry, from {@code routing.max-riders-per-plan}. */
    public int maxRidersPerPlan() {
        return maxRidersPerPlan;
    }
}
