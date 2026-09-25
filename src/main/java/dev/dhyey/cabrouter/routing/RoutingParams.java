package dev.dhyey.cabrouter.routing;

/**
 * @param fleet                 vehicles the plan may use
 * @param maxRideMinutes        longest time any one employee may spend in the cab
 * @param dwellMinutes          time spent at each stop for boarding or alighting
 * @param shift                 direction, office time and night window; decides when
 *                              the escort rule applies
 * @param escortDetourTolerance extra distance (as a fraction, 0.25 = 25%) we accept to
 *                              reorder a route instead of sending a guard
 * @param sweepStarts           how many starting angles the sweep tries per direction
 */
public record RoutingParams(
        Fleet fleet,
        double maxRideMinutes,
        double dwellMinutes,
        ShiftContext shift,
        double escortDetourTolerance,
        int sweepStarts) {

    public RoutingParams {
        if (maxRideMinutes <= 0) {
            throw new IllegalArgumentException("max ride time must be positive");
        }
        if (sweepStarts < 1) {
            throw new IllegalArgumentException("sweep starts must be at least 1");
        }
    }
}
