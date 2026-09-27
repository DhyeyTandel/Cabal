package dev.dhyey.cabrouter.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PlanningPolicyTest {

    private static final LocalDateTime SHIFT_TIME = LocalDateTime.of(2026, 10, 1, 9, 0);

    /** Arrival buffer 15, departure buffer 10: distinct, so a swap between them would fail. */
    private static PlanningPolicy policy() {
        return new PlanningPolicy(2, 15, 10, 20, 7, TrafficProfile.flat(1.0), 0.25, 48, 100, 4, 60);
    }

    @Test
    void officeTimeIsShiftTimeMinusArrivalBufferForPickupAndPlusDepartureBufferForDrop() {
        PlanningPolicy policy = policy();

        RoutingParams pickup = policy.paramsFor(Direction.PICKUP, SHIFT_TIME, Fleet.unlimited("CAB", 4), 60);
        RoutingParams drop = policy.paramsFor(Direction.DROP, SHIFT_TIME, Fleet.unlimited("CAB", 4), 60);

        assertThat(pickup.shift().officeTime()).isEqualTo(SHIFT_TIME.minusMinutes(15));
        assertThat(drop.shift().officeTime()).isEqualTo(SHIFT_TIME.plusMinutes(10));
    }

    @Test
    void paramsForPassesThroughEveryValue() {
        TrafficProfile traffic = TrafficProfile.flat(1.5);
        PlanningPolicy policy = new PlanningPolicy(3, 15, 10, 22, 6, traffic, 0.3, 12, 250, 4, 60);
        Fleet fleet = Fleet.unlimited("CAB", 6);

        RoutingParams params = policy.paramsFor(Direction.DROP, SHIFT_TIME, fleet, 45);

        assertThat(params.fleet()).isSameAs(fleet);
        assertThat(params.maxRideMinutes()).isEqualTo(45);
        assertThat(params.dwellMinutes()).isEqualTo(3);
        assertThat(params.escortDetourTolerance()).isEqualTo(0.3);
        assertThat(params.sweepStarts()).isEqualTo(12);
        assertThat(params.escortCost()).isEqualTo(250);
        assertThat(params.shift().direction()).isEqualTo(Direction.DROP);
        assertThat(params.shift().nightStartHour()).isEqualTo(22);
        assertThat(params.shift().nightEndHour()).isEqualTo(6);
        assertThat(params.shift().traffic()).isSameAs(traffic);
    }

    @Test
    void defaultFleetUsesConfiguredSeatsWhenCapacityIsNull() {
        PlanningPolicy policy = policy();

        Fleet fleet = policy.defaultFleet(null);

        assertThat(fleet.entries()).hasSize(1);
        assertThat(fleet.entries().get(0).type().seats()).isEqualTo(4);
        assertThat(fleet.entries().get(0).type().name()).isEqualTo("CAB");
        assertThat(fleet.entries().get(0).available()).isNull();
    }

    @Test
    void defaultFleetUsesGivenCapacityWhenProvided() {
        PlanningPolicy policy = policy();

        Fleet fleet = policy.defaultFleet(6);

        assertThat(fleet.entries()).hasSize(1);
        assertThat(fleet.entries().get(0).type().seats()).isEqualTo(6);
        assertThat(fleet.entries().get(0).type().name()).isEqualTo("CAB");
        assertThat(fleet.entries().get(0).available()).isNull();
    }

    @Test
    void maxRideMinutesOrFallsBackToTheConfiguredDefaultOnlyWhenNull() {
        PlanningPolicy policy = policy();

        assertThat(policy.maxRideMinutesOr(null)).isEqualTo(60);
        assertThat(policy.maxRideMinutesOr(45)).isEqualTo(45);
    }

    @Test
    void rejectsANegativeBuffer() {
        assertThatThrownBy(() -> new PlanningPolicy(2, -1, 10, 20, 7, TrafficProfile.flat(1.0), 0.25, 48, 100, 4, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAZeroDefaultCapacity() {
        assertThatThrownBy(() -> new PlanningPolicy(2, 15, 10, 20, 7, TrafficProfile.flat(1.0), 0.25, 48, 100, 0, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
