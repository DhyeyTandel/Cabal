package dev.dhyey.cabrouter.service;

import dev.dhyey.cabrouter.api.dto.SandboxFleet;
import dev.dhyey.cabrouter.api.dto.SandboxPlanRequest;
import dev.dhyey.cabrouter.api.dto.SandboxPlanResponse;
import dev.dhyey.cabrouter.api.dto.SandboxRiderRequest;
import dev.dhyey.cabrouter.routing.Fleet;
import dev.dhyey.cabrouter.routing.GeoPoint;
import dev.dhyey.cabrouter.routing.HaversineTravelModel;
import dev.dhyey.cabrouter.routing.PlanningPolicy;
import dev.dhyey.cabrouter.routing.RoutingParams;
import dev.dhyey.cabrouter.routing.ShiftPlan;
import dev.dhyey.cabrouter.routing.Stop;
import dev.dhyey.cabrouter.routing.TravelModelProvider;
import dev.dhyey.cabrouter.routing.VehicleType;
import dev.dhyey.cabrouter.travel.RouteGeometryProvider;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Plans a small, throwaway shift for the public sandbox: real routing engine, fixed office,
 * nothing persisted. No entities, no repositories; the response is built straight from
 * {@link ShiftPlan#cabs()}.
 */
@Service
public class SandboxService {

    /** Manyata Tech Park, Bengaluru: the only office the sandbox ever plans against. */
    private static final GeoPoint OFFICE = new GeoPoint(13.0475, 77.6206);

    /** A fixed demo date so the sandbox never depends on today's date. */
    private static final LocalDate DEMO_DATE = LocalDate.of(2026, 10, 1);

    private final PlanningPolicy policy;
    private final TravelModelProvider travelProvider;
    private final RouteGeometryProvider geometry;
    private final int maxRiders;
    private final double maxRadiusKm;

    public SandboxService(PlanningPolicy policy, TravelModelProvider travelProvider,
                          RouteGeometryProvider geometry,
                          @Value("${routing.sandbox.max-riders:40}") int maxRiders,
                          @Value("${routing.sandbox.max-radius-km:25}") double maxRadiusKm) {
        this.policy = policy;
        this.travelProvider = travelProvider;
        this.geometry = geometry;
        this.maxRiders = maxRiders;
        this.maxRadiusKm = maxRadiusKm;
    }

    public SandboxPlanResponse plan(SandboxPlanRequest req) {
        List<SandboxRiderRequest> riders = req.riders();
        if (riders.size() > maxRiders) {
            throw new InvalidRequestException("the sandbox can plan at most " + maxRiders + " riders");
        }
        for (int i = 0; i < riders.size(); i++) {
            SandboxRiderRequest rider = riders.get(i);
            GeoPoint home = new GeoPoint(rider.latitude(), rider.longitude());
            if (HaversineTravelModel.greatCircleKm(OFFICE, home) > maxRadiusKm) {
                throw new InvalidRequestException(
                        "rider " + (i + 1) + " is more than " + trim(maxRadiusKm) + " km from the office");
            }
        }

        Fleet fleet = fleetFor(req.fleet());
        LocalDateTime shiftTime = LocalDateTime.of(DEMO_DATE, req.shiftTime());
        RoutingParams params = policy.paramsFor(req.direction(), shiftTime, fleet, policy.maxRideMinutesOr(null));

        List<Stop> stops = new ArrayList<>(riders.size());
        for (int i = 0; i < riders.size(); i++) {
            SandboxRiderRequest rider = riders.get(i);
            stops.add(new Stop(i + 1L, new GeoPoint(rider.latitude(), rider.longitude()), rider.woman(),
                    rider.earliestPickup(), rider.latestDrop()));
        }

        ShiftPlan plan = ShiftPlan.create(OFFICE, params, travelProvider, stops);
        Map<Integer, List<String>> legs = new HashMap<>();
        for (ShiftPlan.Cab cab : plan.cabs()) {
            geometry.legs(RouteGeometryProvider.drivingOrder(req.direction(), OFFICE, cab.stops()))
                    .ifPresent(l -> legs.put(cab.cabNumber(), l));
        }
        return SandboxPlanResponse.from(req.direction(), shiftTime, OFFICE, plan, legs);
    }

    /** SEDANS: unlimited 4-seat SEDANs. MIXED: the same SEDANs plus up to 2 6-seat SUVs. */
    private static Fleet fleetFor(SandboxFleet fleet) {
        return switch (fleet) {
            case SEDANS -> Fleet.unlimited("SEDAN", 4);
            case MIXED -> new Fleet(List.of(
                    new Fleet.Entry(new VehicleType("SEDAN", 4, 800, 14), null),
                    new Fleet.Entry(new VehicleType("SUV", 6, 1100, 18), 2)));
        };
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
