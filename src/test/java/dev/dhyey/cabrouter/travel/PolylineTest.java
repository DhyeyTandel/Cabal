package dev.dhyey.cabrouter.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import dev.dhyey.cabrouter.routing.GeoPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolylineTest {

    /** The worked example from Google's polyline documentation. */
    private static final List<GeoPoint> GOOGLE_POINTS = List.of(
            new GeoPoint(38.5, -120.2), new GeoPoint(40.7, -120.95), new GeoPoint(43.252, -126.453));
    private static final String GOOGLE_ENCODED = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";

    @Test
    void encodesTheKnownVector() {
        assertThat(Polyline.encode(GOOGLE_POINTS, 5)).isEqualTo(GOOGLE_ENCODED);
    }

    @Test
    void decodesTheKnownVector() {
        assertThat(Polyline.decode(GOOGLE_ENCODED, 5)).isEqualTo(GOOGLE_POINTS);
    }

    @Test
    void roundTripsAtPrecisionSixIncludingNegativesAndRepeats() {
        List<GeoPoint> pts = List.of(
                new GeoPoint(13.047512, 77.620612), new GeoPoint(13.047512, 77.620612),
                new GeoPoint(12.971599, 77.594566), new GeoPoint(-33.868820, 151.209296),
                new GeoPoint(0, 0), new GeoPoint(-0.000001, -179.999999));

        List<GeoPoint> back = Polyline.decode(Polyline.encode(pts, 6), 6);

        assertThat(back).hasSameSizeAs(pts);
        for (int i = 0; i < pts.size(); i++) {
            assertThat(back.get(i).lat()).isCloseTo(pts.get(i).lat(), offset(1e-9));
            assertThat(back.get(i).lng()).isCloseTo(pts.get(i).lng(), offset(1e-9));
        }
    }

    @Test
    void emptyInputGivesEmptyOutput() {
        assertThat(Polyline.encode(List.of(), 6)).isEmpty();
        assertThat(Polyline.decode("", 6)).isEmpty();
    }

    @Test
    void aTruncatedPolylineIsRejected() {
        assertThatThrownBy(() -> Polyline.decode("_p~iF", 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
