package dev.dhyey.cabrouter.service;

import dev.dhyey.cabrouter.api.dto.CreatePlanRequest;
import dev.dhyey.cabrouter.api.dto.PlanResponse;
import dev.dhyey.cabrouter.config.RoutingProperties;
import dev.dhyey.cabrouter.domain.CabRoute;
import dev.dhyey.cabrouter.domain.Employee;
import dev.dhyey.cabrouter.domain.EmployeeRepository;
import dev.dhyey.cabrouter.domain.Office;
import dev.dhyey.cabrouter.domain.OfficeRepository;
import dev.dhyey.cabrouter.domain.RoutePlan;
import dev.dhyey.cabrouter.domain.RoutePlanRepository;
import dev.dhyey.cabrouter.domain.RouteStop;
import dev.dhyey.cabrouter.routing.Direction;
import dev.dhyey.cabrouter.api.dto.VehicleSpec;
import dev.dhyey.cabrouter.routing.EtaCalculator;
import dev.dhyey.cabrouter.routing.Fleet;
import dev.dhyey.cabrouter.routing.GeoPoint;
import dev.dhyey.cabrouter.routing.PlannedCab;
import dev.dhyey.cabrouter.routing.RoutePlanner;
import dev.dhyey.cabrouter.routing.RoutingParams;
import dev.dhyey.cabrouter.routing.ShiftContext;
import dev.dhyey.cabrouter.routing.Stop;
import dev.dhyey.cabrouter.routing.StopTiming;
import dev.dhyey.cabrouter.routing.TrafficProfile;
import dev.dhyey.cabrouter.routing.RouteMetrics;
import dev.dhyey.cabrouter.travel.TravelEstimate;
import dev.dhyey.cabrouter.travel.TravelModelProvider;
import dev.dhyey.cabrouter.routing.VehicleType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Glue between the JPA model and the routing core. It loads entities, turns them into
 * {@link Stop}s, calls {@link RoutePlanner}, and writes the result back as cabs and stops.
 *
 * <p>Every operation first fetches a travel model for exactly the points it will touch
 * (one OSRM table request when OSRM is on), then builds a planner over that model.
 */
@Service
@Transactional
public class PlanningService {

    private final OfficeRepository offices;
    private final EmployeeRepository employees;
    private final RoutePlanRepository plans;
    private final TravelModelProvider travelProvider;
    private final TrafficProfile traffic;
    private final RoutingProperties props;

    public PlanningService(OfficeRepository offices, EmployeeRepository employees, RoutePlanRepository plans,
                           TravelModelProvider travelProvider, RoutingProperties props) {
        this.offices = offices;
        this.employees = employees;
        this.plans = plans;
        this.travelProvider = travelProvider;
        this.traffic = new TrafficProfile(props.trafficHourlyFactors());
        this.props = props;
    }

    public PlanResponse create(CreatePlanRequest req) {
        Office office = offices.findById(req.officeId())
                .orElseThrow(() -> new NotFoundException("office " + req.officeId() + " not found"));

        Set<Long> ids = new HashSet<>(req.employeeIds());
        if (ids.size() != req.employeeIds().size()) {
            throw new InvalidRequestException("employeeIds contains duplicates");
        }
        Map<Long, Employee> staff = employees.findAllById(ids).stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        for (Long id : req.employeeIds()) {
            Employee e = staff.get(id);
            if (e == null) {
                throw new NotFoundException("employee " + id + " not found");
            }
            requireSameOffice(e, office);
        }

        int maxRide = req.maxRideMinutes() != null ? req.maxRideMinutes() : props.defaultMaxRideMinutes();
        RoutePlan plan = new RoutePlan(office, req.shiftTime(), req.direction(), fleetOf(req), maxRide);

        List<Stop> stops = req.employeeIds().stream().map(id -> toStop(staff.get(id))).toList();
        rebuildAll(plan, stops);
        return PlanResponse.from(plans.save(plan));
    }

    @Transactional(readOnly = true)
    public PlanResponse get(long planId) {
        return PlanResponse.from(load(planId));
    }

    /** An employee cancels. LOCAL keeps every other cab exactly as issued. */
    public PlanResponse cancel(long planId, long employeeId, ReplanStrategy strategy) {
        RoutePlan plan = load(planId);
        CabRoute cab = plan.findCabOf(employeeId).orElseThrow(
                () -> new NotFoundException("employee " + employeeId + " is not on plan " + planId));

        if (strategy == ReplanStrategy.FULL) {
            List<Stop> remaining = allStops(plan).stream().filter(s -> s.employeeId() != employeeId).toList();
            rebuildAll(plan, remaining);
        } else {
            List<Stop> remaining = stopsOf(cab).stream().filter(s -> s.employeeId() != employeeId).toList();
            if (remaining.isEmpty()) {
                plan.getCabs().remove(cab);
            } else {
                Session session = session(plan, remaining);
                // The cab keeps its vehicle even if a smaller one would now do: it is already dispatched.
                PlannedCab rebuilt = session.planner().buildCab(
                        plan.getOffice().location(), plan.vehicleNamed(cab.getVehicleType()), remaining, params(plan));
                write(plan, cab, rebuilt, session);
            }
        }
        plan.touch();
        return PlanResponse.from(plan);
    }

    /** A late booking. The employee goes into whichever cab absorbs them most cheaply. */
    public PlanResponse add(long planId, long employeeId) {
        RoutePlan plan = load(planId);
        if (plan.findCabOf(employeeId).isPresent()) {
            throw new ConflictException("employee " + employeeId + " is already on plan " + planId);
        }
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new NotFoundException("employee " + employeeId + " not found"));
        requireSameOffice(employee, plan.getOffice());

        Stop newcomer = toStop(employee);
        List<Stop> everyone = new ArrayList<>(allStops(plan));
        everyone.add(newcomer);
        Session session = session(plan, everyone);

        List<PlannedCab> current = plan.getCabs().stream().map(c -> toPlanned(plan, c, session)).toList();
        RoutePlanner.Insertion insertion =
                session.planner().bestInsertion(plan.getOffice().location(), current, newcomer, params(plan));

        CabRoute target = insertion.opensNewCab() ? plan.addCab() : plan.getCabs().get(insertion.cabIndex());
        write(plan, target, insertion.cab(), session);
        plan.touch();
        return PlanResponse.from(plan);
    }

    /** Re-optimise everything, e.g. after several local repairs have drifted from optimal. */
    public PlanResponse replan(long planId) {
        RoutePlan plan = load(planId);
        rebuildAll(plan, allStops(plan));
        plan.touch();
        return PlanResponse.from(plan);
    }

    private void rebuildAll(RoutePlan plan, List<Stop> stops) {
        Session session = session(plan, stops);
        List<PlannedCab> planned = session.planner().plan(plan.getOffice().location(), stops, params(plan));
        plan.getCabs().clear();
        for (PlannedCab pc : planned) {
            write(plan, plan.addCab(), pc, session);
        }
    }

    /** A travel model for the office plus these stops, and a planner that uses it. */
    private record Session(TravelEstimate travel, RoutePlanner planner) {
    }

    private Session session(RoutePlan plan, Collection<Stop> stops) {
        List<GeoPoint> points = new ArrayList<>(stops.size() + 1);
        points.add(plan.getOffice().location());
        stops.forEach(s -> points.add(s.location()));
        TravelEstimate travel = travelProvider.forPoints(points);
        return new Session(travel, new RoutePlanner(travel.model()));
    }

    private void write(RoutePlan plan, CabRoute cab, PlannedCab planned, Session session) {
        LocalDateTime officeTime = officeTime(plan);
        List<StopTiming> timings = EtaCalculator.compute(session.travel().model(), plan.getOffice().location(),
                planned.stops(), params(plan).shift(), props.dwellMinutes());

        cab.update(planned.vehicle(), planned.distanceKm(), planned.maxRideMinutes(), planned.escortRequired(),
                params(plan).cost(planned),
                officeTime, session.travel().source());
        cab.getStops().clear();
        int sequence = 1;
        for (StopTiming t : timings) {
            Employee e = employees.getReferenceById(t.stop().employeeId());
            cab.getStops().add(new RouteStop(cab, e, sequence++, t.stop().location(), t.eta(), t.rideMinutes()));
        }
    }

    /**
     * Rebuilds the routing view of a stored cab. Stored stops are in driving order.
     * Distance and ride time are re-measured with this call's travel model, so an
     * insertion compares like with like even if the cab was first timed another way.
     */
    private PlannedCab toPlanned(RoutePlan plan, CabRoute cab, Session session) {
        List<Stop> outward = new ArrayList<>(stopsOf(cab));
        if (plan.getDirection() == Direction.PICKUP) {
            Collections.reverse(outward);
        }
        GeoPoint office = plan.getOffice().location();
        return new PlannedCab(plan.vehicleNamed(cab.getVehicleType()), outward,
                RouteMetrics.pathKm(session.travel().model(), office, outward),
                RouteMetrics.maxRideMinutes(session.travel().model(), office, outward, props.dwellMinutes(),
                        params(plan).shift()),
                cab.isEscortRequired());
    }

    private List<Stop> allStops(RoutePlan plan) {
        return plan.getCabs().stream().flatMap(c -> stopsOf(c).stream()).toList();
    }

    private List<Stop> stopsOf(CabRoute cab) {
        return cab.getStops().stream()
                .map(rs -> new Stop(rs.getEmployee().getId(), rs.location(), rs.getEmployee().isEscortSensitive()))
                .toList();
    }

    private RoutingParams params(RoutePlan plan) {
        return new RoutingParams(
                plan.fleet(),
                plan.getMaxRideMinutes(),
                props.dwellMinutes(),
                new ShiftContext(plan.getDirection(), officeTime(plan), props.nightStartHour(), props.nightEndHour(),
                        traffic),
                props.escortDetourTolerance(),
                props.sweepStarts(),
                props.escortCost());
    }

    private Fleet fleetOf(CreatePlanRequest req) {
        if (req.fleet() != null && req.cabCapacity() != null) {
            throw new InvalidRequestException("give either cabCapacity or fleet, not both");
        }
        if (req.fleet() == null || req.fleet().isEmpty()) {
            int seats = req.cabCapacity() != null ? req.cabCapacity() : props.defaultCabCapacity();
            return Fleet.unlimited("CAB", seats);
        }
        try {
            return new Fleet(req.fleet().stream()
                    .map(v -> new Fleet.Entry(new VehicleType(v.name(), v.seats(),
                            v.costPerTrip() != null ? v.costPerTrip() : VehicleType.DEFAULT_COST_PER_TRIP,
                            v.costPerKm() != null ? v.costPerKm() : VehicleType.DEFAULT_COST_PER_KM), v.available()))
                    .toList());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    private LocalDateTime officeTime(RoutePlan plan) {
        return plan.getDirection() == Direction.PICKUP
                ? plan.getShiftTime().minusMinutes(props.arrivalBufferMinutes())
                : plan.getShiftTime().plusMinutes(props.departureBufferMinutes());
    }

    private RoutePlan load(long planId) {
        return plans.findById(planId).orElseThrow(() -> new NotFoundException("plan " + planId + " not found"));
    }

    private static Stop toStop(Employee e) {
        return new Stop(e.getId(), new GeoPoint(e.getLatitude(), e.getLongitude()),
                e.isEscortSensitive());
    }

    private static void requireSameOffice(Employee e, Office office) {
        if (!e.getOffice().getId().equals(office.getId())) {
            throw new InvalidRequestException("employee " + e.getId() + " belongs to a different office");
        }
    }
}
