package dev.dhyey.cabrouter.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import dev.dhyey.cabrouter.routing.GeoPoint;
import dev.dhyey.cabrouter.routing.HaversineTravelModel;
import dev.dhyey.cabrouter.routing.TravelModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OsrmProviderTest {

    private static final HaversineTravelModel HAVERSINE = new HaversineTravelModel(1.4, 22);

    private static List<GeoPoint> points(int n) {
        List<GeoPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new GeoPoint(13.0 + i * 0.01, 77.6 + (i % 4) * 0.005));
        }
        return out;
    }

    private static OsrmProvider provider(String baseUrl, int maxTableSize, double durationFactor) {
        return new OsrmProvider(new OsrmClient(baseUrl, maxTableSize, Duration.ofSeconds(2)), HAVERSINE, durationFactor);
    }

    @Test
    void largeShiftsAreSplitIntoBlocksAndStitchedBackExactly() throws Exception {
        try (StubOsrmServer osrm = new StubOsrmServer(6)) {
            List<GeoPoint> pts = points(11);

            TravelEstimate est = provider(osrm.baseUrl(), 6, 1.0).forPoints(pts);

            assertThat(est.source()).isEqualTo(TravelSource.OSRM);
            // 11 points in blocks of 3 gives 4 blocks, so 4 x 4 = 16 requests, each within the limit of 6.
            assertThat(osrm.requests.get()).isEqualTo(16);
            TravelModel m = est.model();
            for (GeoPoint a : pts) {
                for (GeoPoint b : pts) {
                    double mean = (StubOsrmServer.metres(a, b) + StubOsrmServer.metres(b, a)) / 2 / 1000;
                    assertThat(m.distanceKm(a, b)).isCloseTo(mean, offset(1e-3));
                    assertThat(m.minutes(a, b)).isCloseTo(StubOsrmServer.seconds(a, b) / 60, offset(1e-3));
                }
            }
        }
    }

    @Test
    void smallShiftsUseOneRequest() throws Exception {
        try (StubOsrmServer osrm = new StubOsrmServer(100)) {
            provider(osrm.baseUrl(), 100, 1.0).forPoints(points(20));

            assertThat(osrm.requests.get()).isEqualTo(1);
        }
    }

    @Test
    void durationsAreScaledForTrafficAndHeadingNorthIsSlower() throws Exception {
        try (StubOsrmServer osrm = new StubOsrmServer(100)) {
            List<GeoPoint> pts = points(2);
            TravelModel m = provider(osrm.baseUrl(), 100, 2.0).forPoints(pts).model();

            double northFreeFlow = StubOsrmServer.seconds(pts.get(0), pts.get(1)) / 60;
            assertThat(m.minutes(pts.get(0), pts.get(1))).isCloseTo(northFreeFlow * 2, offset(1e-3));
            assertThat(m.minutes(pts.get(0), pts.get(1))).isGreaterThan(m.minutes(pts.get(1), pts.get(0)));
        }
    }

    @Test
    void anUnroutablePairIsEstimatedInsteadOfFailingThePlan() throws Exception {
        try (StubOsrmServer osrm = new StubOsrmServer(100)) {
            List<GeoPoint> pts = points(3);
            osrm.unroutable.add(new double[] {pts.get(0).lat(), pts.get(2).lat()});

            TravelEstimate est = provider(osrm.baseUrl(), 100, 1.0).forPoints(pts);

            assertThat(est.source()).isEqualTo(TravelSource.OSRM);
            assertThat(est.model().minutes(pts.get(0), pts.get(2)))
                    .isCloseTo(HAVERSINE.minutes(pts.get(0), pts.get(2)), offset(1e-9));
        }
    }

    @Test
    void anUnreachableServerFallsBackToHaversineAndSaysSo() {
        // Port 9 (discard) on localhost: nothing listens, so the connection is refused.
        TravelEstimate est = provider("http://127.0.0.1:9", 100, 1.0).forPoints(points(5));

        assertThat(est.source()).isEqualTo(TravelSource.HAVERSINE_FALLBACK);
        assertThat(est.model()).isSameAs(HAVERSINE);
    }

    @Test
    void anOsrmErrorCodeIsAFailureNotAnEmptyMatrix() throws Exception {
        try (StubOsrmServer osrm = new StubOsrmServer(3)) {
            // Client believes it may send 10, server only accepts 3.
            OsrmClient client = new OsrmClient(osrm.baseUrl(), 10, Duration.ofSeconds(2));

            assertThatThrownBy(() -> client.table(points(5)))
                    .isInstanceOf(OsrmException.class)
                    .hasMessageContaining("TooBig");
        }
    }
}
