package dev.dhyey.cabrouter.travel;

import dev.dhyey.cabrouter.routing.GeoPoint;
import dev.dhyey.cabrouter.routing.HaversineTravelModel;
import dev.dhyey.cabrouter.routing.TravelEstimate;
import dev.dhyey.cabrouter.routing.TravelModelProvider;
import dev.dhyey.cabrouter.routing.TravelSource;
import java.util.Collection;

public final class HaversineProvider implements TravelModelProvider {

    private final TravelEstimate estimate;

    public HaversineProvider(HaversineTravelModel model) {
        this.estimate = new TravelEstimate(model, TravelSource.HAVERSINE);
    }

    @Override
    public TravelEstimate forPoints(Collection<GeoPoint> points) {
        return estimate;
    }
}
