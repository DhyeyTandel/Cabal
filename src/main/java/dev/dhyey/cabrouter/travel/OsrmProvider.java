package dev.dhyey.cabrouter.travel;

import dev.dhyey.cabrouter.routing.GeoPoint;
import dev.dhyey.cabrouter.routing.HaversineTravelModel;
import dev.dhyey.cabrouter.routing.MatrixTravelModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Road-network travel from OSRM, with two corrections.
 *
 * <ul>
 *   <li><b>Traffic.</b> OSRM times are free-flow: an empty city at the speed limit. Shift
 *       changes in Bengaluru are anything but, so durations are multiplied by
 *       {@code durationFactor}.</li>
 *   <li><b>Gaps.</b> A pair OSRM cannot route is estimated by haversine and logged,
 *       rather than failing the whole plan.</li>
 * </ul>
 *
 * If OSRM is unreachable or errors, the whole call falls back to haversine and says so
 * in its {@link TravelSource}, so a routing outage degrades accuracy, not availability.
 */
public final class OsrmProvider implements TravelModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OsrmProvider.class);

    private final OsrmClient client;
    private final HaversineTravelModel fallback;
    private final double durationFactor;

    public OsrmProvider(OsrmClient client, HaversineTravelModel fallback, double durationFactor) {
        if (durationFactor <= 0) {
            throw new IllegalArgumentException("duration factor must be positive");
        }
        this.client = client;
        this.fallback = fallback;
        this.durationFactor = durationFactor;
    }

    @Override
    public TravelEstimate forPoints(Collection<GeoPoint> points) {
        List<GeoPoint> unique = new ArrayList<>(new LinkedHashSet<>(points));
        int n = unique.size();
        if (n < 2) {
            return new TravelEstimate(fallback, TravelSource.OSRM); // nothing to route between
        }
        OsrmClient.Matrices m;
        try {
            m = client.table(unique);
        } catch (OsrmException e) {
            log.warn("OSRM unavailable, falling back to haversine for {} points: {}", n, e.getMessage());
            return new TravelEstimate(fallback, TravelSource.HAVERSINE_FALLBACK);
        }

        double[][] km = new double[n][n];
        double[][] minutes = new double[n][n];
        int gaps = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Double metres = m.metres()[i][j];
                Double seconds = m.seconds()[i][j];
                if (i != j && (metres == null || seconds == null)) {
                    gaps++;
                    km[i][j] = fallback.distanceKm(unique.get(i), unique.get(j));
                    minutes[i][j] = fallback.minutes(unique.get(i), unique.get(j));
                } else {
                    km[i][j] = metres == null ? 0 : metres / 1000;
                    minutes[i][j] = seconds == null ? 0 : seconds / 60 * durationFactor;
                }
            }
        }
        if (gaps > 0) {
            log.warn("OSRM could not route {} of {} pairs; estimated them by haversine", gaps, n * (n - 1));
        }
        return new TravelEstimate(new MatrixTravelModel(unique, km, minutes), TravelSource.OSRM);
    }
}
