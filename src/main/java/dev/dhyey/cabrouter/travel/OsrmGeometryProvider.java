package dev.dhyey.cabrouter.travel;

import dev.dhyey.cabrouter.routing.GeoPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Road geometry from OSRM's route service. An OSRM failure is logged and yields no geometry. */
public final class OsrmGeometryProvider implements RouteGeometryProvider {

    private static final Logger log = LoggerFactory.getLogger(OsrmGeometryProvider.class);
    static final int PRECISION = 6;

    private final OsrmClient client;

    public OsrmGeometryProvider(OsrmClient client) {
        this.client = client;
    }

    @Override
    public Optional<List<String>> legs(List<GeoPoint> drivingOrder) {
        if (drivingOrder.size() < 2) {
            return Optional.empty();
        }
        try {
            List<String> out = new ArrayList<>();
            for (List<GeoPoint> leg : client.route(drivingOrder)) {
                out.add(Polyline.encode(leg, PRECISION));
            }
            return Optional.of(out);
        } catch (OsrmException e) {
            // Not the coordinates: they are home locations.
            log.warn("OSRM route geometry unavailable for a {}-point route: {}", drivingOrder.size(), e.getMessage());
            return Optional.empty();
        }
    }
}
