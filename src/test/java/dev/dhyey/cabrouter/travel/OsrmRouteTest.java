package dev.dhyey.cabrouter.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import dev.dhyey.cabrouter.routing.Direction;
import dev.dhyey.cabrouter.routing.GeoPoint;
import dev.dhyey.cabrouter.routing.Stop;
import dev.dhyey.cabrouter.routing.StopTiming;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OsrmRouteTest {

    private static final GeoPoint A = new GeoPoint(13.10, 77.60);
    private static final GeoPoint B = new GeoPoint(13.05, 77.62);
    private static final GeoPoint C = new GeoPoint(13.00, 77.58);
    private static final GeoPoint OFFICE = new GeoPoint(13.0475, 77.6206);

    private static OsrmClient client(String baseUrl) {
        return new OsrmClient(baseUrl, 100, Duration.ofSeconds(2));
    }

    @Test
    void routeSplitsIntoLegsAndJoinsStepsWithoutRepeatedPoints() throws Exception {
        try (StubOsrmServer osrm = new StubOsrmServer(100)) {
            List<List<GeoPoint>> legs = client(osrm.baseUrl()).route(List.of(A, B, C));

            assertThat(legs).hasSize(2);
            assertSame(legs.get(0), StubOsrmServer.legPoints(A, B));
            assertSame(legs.get(1), StubOsrmServer.legPoints(B, C));
            // Three steps of 3, 3 and 2 points join into 5 distinct points: no step seams, no arrive repeat.
            assertThat(legs.get(0)).hasSize(5).startsWith(A).endsWith(B);
            assertThat(osrm.routeRequests.get()).isEqualTo(1);
        }
    }

    @Test
    void routeFailureIsAnOsrmExceptionThatDoesNotLeakCoordinates() throws Exception {
        try (StubOsrmServer osrm = new StubOsrmServer(100)) {
            osrm.failRoutes = true;

            assertThatThrownBy(() -> client(osrm.baseUrl()).route(List.of(A, B)))
                    .isInstanceOf(OsrmException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("13.1").doesNotContain("77.6"));
        }
    }

    @Test
    void geometryProviderEncodesEachLegAsPolyline6() throws Exception {
        try (StubOsrmServer osrm = new StubOsrmServer(100)) {
            Optional<List<String>> legs = new OsrmGeometryProvider(client(osrm.baseUrl())).legs(List.of(A, B, C));

            assertThat(legs).isPresent();
            assertThat(legs.get()).hasSize(2);
            assertSame(Polyline.decode(legs.get().get(1), 6), StubOsrmServer.legPoints(B, C));
        }
    }

    @Test
    void geometryProviderIsEmptyWhenTheServerFails() throws Exception {
        try (StubOsrmServer osrm = new StubOsrmServer(100)) {
            osrm.failRoutes = true;

            assertThat(new OsrmGeometryProvider(client(osrm.baseUrl())).legs(List.of(A, B))).isEmpty();
        }
        assertThat(new OsrmGeometryProvider(client("http://127.0.0.1:9")).legs(List.of(A, B))).isEmpty();
    }

    @Test
    void noGeometryProviderIsAlwaysEmpty() {
        assertThat(new NoGeometryProvider().legs(List.of(A, B))).isEmpty();
    }

    @Test
    void drivingOrderPutsTheOfficeLastForPickupAndFirstForDrop() {
        List<StopTiming> stops = List.of(timing(1, A), timing(2, B));

        assertThat(RouteGeometryProvider.drivingOrder(Direction.PICKUP, OFFICE, stops)).containsExactly(A, B, OFFICE);
        assertThat(RouteGeometryProvider.drivingOrder(Direction.DROP, OFFICE, stops)).containsExactly(OFFICE, A, B);
    }

    private static StopTiming timing(long id, GeoPoint p) {
        return new StopTiming(new Stop(id, p, false, null, null), LocalDateTime.of(2026, 10, 1, 8, 0), 10, false);
    }

    private static void assertSame(List<GeoPoint> actual, List<GeoPoint> expected) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i).lat()).isCloseTo(expected.get(i).lat(), offset(1e-9));
            assertThat(actual.get(i).lng()).isCloseTo(expected.get(i).lng(), offset(1e-9));
        }
    }
}
