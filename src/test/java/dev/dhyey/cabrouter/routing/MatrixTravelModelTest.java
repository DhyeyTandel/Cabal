package dev.dhyey.cabrouter.routing;

import static dev.dhyey.cabrouter.routing.Fixtures.OFFICE;
import static dev.dhyey.cabrouter.routing.Fixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MatrixTravelModelTest {

    private final Stop a = stop(1, 5, 0);
    private final Stop b = stop(2, 10, 0);

    /**
     * Office to A to B, where the outbound legs are slow (10 min each) and the inbound
     * legs fast (4 min each), as on a one-way system.
     */
    private final MatrixTravelModel model = new MatrixTravelModel(
            List.of(OFFICE, a.location(), b.location()),
            new double[][] {{0, 5, 10}, {6, 0, 5}, {12, 7, 0}},
            new double[][] {{0, 10, 20}, {4, 0, 10}, {8, 4, 0}});

    @Test
    void distanceIsTheMeanOfBothDirectionsSoTwoOptStaysValid() {
        assertThat(model.distanceKm(OFFICE, a.location())).isEqualTo(5.5);
        assertThat(model.distanceKm(a.location(), OFFICE)).isEqualTo(5.5);
        assertThat(model.distanceKm(OFFICE, b.location())).isEqualTo(11);
    }

    @Test
    void timeKeepsItsDirection() {
        assertThat(model.minutes(OFFICE, a.location())).isEqualTo(10);
        assertThat(model.minutes(a.location(), OFFICE)).isEqualTo(4);
    }

    @Test
    void askingAboutAPointOutsideTheMatrixFailsLoudly() {
        assertThatThrownBy(() -> model.minutes(OFFICE, stop(9, 1, 1).location()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the travel matrix");
    }
}
