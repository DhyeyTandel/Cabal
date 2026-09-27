package dev.dhyey.cabrouter.service;

import dev.dhyey.cabrouter.api.dto.CreatePlanRequest;
import dev.dhyey.cabrouter.api.dto.PlanResponse;
import dev.dhyey.cabrouter.api.dto.VehicleSpec;
import dev.dhyey.cabrouter.domain.CabRoute;
import dev.dhyey.cabrouter.domain.Employee;
import dev.dhyey.cabrouter.domain.EmployeeRepository;
import dev.dhyey.cabrouter.domain.Office;
import dev.dhyey.cabrouter.domain.OfficeRepository;
import dev.dhyey.cabrouter.domain.RoutePlan;
import dev.dhyey.cabrouter.domain.RoutePlanRepository;
import dev.dhyey.cabrouter.domain.RouteStop;
import dev.dhyey.cabrouter.routing.Fleet;
import dev.dhyey.cabrouter.routing.GeoPoint;
import dev.dhyey.cabrouter.routing.PlanningPolicy;
import dev.dhyey.cabrouter.routing.ReplanStrategy;
import dev.dhyey.cabrouter.routing.RoutingParams;
import dev.dhyey.cabrouter.routing.ShiftPlan;
import dev.dhyey.cabrouter.routing.Stop;
import dev.dhyey.cabrouter.routing.StopTiming;
import dev.dhyey.cabrouter.routing.TravelModelProvider;
import dev.dhyey.cabrouter.routing.VehicleType;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Glue between the JPA model and the routing core. It loads entities, restores a
 * {@link ShiftPlan} from them, asks the plan to do the actual editing, and writes back
 * whichever cabs changed.
 */
@Service
@Transactional
public class PlanningService {

    private final OfficeRepository offices;
    private final EmployeeRepository employees;
    private final RoutePlanRepository plans;
    private final TravelModelProvider travelProvider;
    private final PlanningPolicy policy;

    public PlanningService(OfficeRepository offices, EmployeeRepository employees, RoutePlanRepository plans,
                           TravelModelProvider travelProvider, PlanningPolicy policy) {
        this.offices = offices;
        this.employees = employees;
        this.plans = plans;
        this.travelProvider = travelProvider;
        this.policy = policy;
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

        int maxRide = policy.maxRideMinutesOr(req.maxRideMinutes());
        RoutePlan plan = new RoutePlan(office, req.shiftTime(), req.direction(), fleetOf(req), maxRide);

        List<Stop> stops = req.employeeIds().stream().map(id -> toStop(staff.get(id))).toList();
        RoutingParams params = params(plan);
        ShiftPlan shiftPlan = ShiftPlan.create(office.location(), params, travelProvider, stops);
        save(plan, ShiftPlan.restore(office.location(), params, travelProvider, List.of()), shiftPlan);
        return PlanResponse.from(plans.save(plan));
    }

    @Transactional(readOnly = true)
    public PlanResponse get(long planId) {
        return PlanResponse.from(load(planId));
    }

    /** An employee cancels. LOCAL keeps every other cab exactly as issued. */
    public PlanResponse cancel(long planId, long employeeId, ReplanStrategy strategy) {
        RoutePlan plan = load(planId);
        RoutingParams params = params(plan);
        ShiftPlan before = restore(plan, params);
        ShiftPlan after = before.cancel(employeeId, strategy);
        save(plan, before, after);
        plan.touch();
        return PlanResponse.from(plan);
    }

    /** A late booking. The employee goes into whichever cab absorbs them most cheaply. */
    public PlanResponse add(long planId, long employeeId) {
        RoutePlan plan = load(planId);
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new NotFoundException("employee " + employeeId + " not found"));
        requireSameOffice(employee, plan.getOffice());

        RoutingParams params = params(plan);
        ShiftPlan before = restore(plan, params);
        ShiftPlan after = before.add(toStop(employee));
        save(plan, before, after);
        plan.touch();
        return PlanResponse.from(plan);
    }

    /** Re-optimise everything, e.g. after several local repairs have drifted from optimal. */
    public PlanResponse replan(long planId) {
        RoutePlan plan = load(planId);
        RoutingParams params = params(plan);
        ShiftPlan before = restore(plan, params);
        ShiftPlan after = before.replan();
        save(plan, before, after);
        plan.touch();
        return PlanResponse.from(plan);
    }

    /** Rebuilds the routing view of a stored plan. Stored stops are in driving order. */
    private ShiftPlan restore(RoutePlan plan, RoutingParams params) {
        List<ShiftPlan.Cab> cabs = plan.getCabs().stream().map(c -> toCab(plan, c)).toList();
        return ShiftPlan.restore(plan.getOffice().location(), params, travelProvider, cabs);
    }

    private ShiftPlan.Cab toCab(RoutePlan plan, CabRoute cab) {
        List<StopTiming> stops = cab.getStops().stream()
                .map(rs -> new StopTiming(
                        new Stop(rs.getEmployee().getId(), rs.location(), rs.getEmployee().isEscortSensitive()),
                        rs.getEta(), rs.getRideMinutes()))
                .toList();
        return new ShiftPlan.Cab(cab.getCabNumber(), plan.vehicleNamed(cab.getVehicleType()), stops,
                cab.getDistanceKm(), cab.getMaxRideMinutes(), cab.isEscortRequired(), cab.getCost(),
                cab.getOfficeTime(), cab.getTravelSource());
    }

    /**
     * Writes every cab that differs between {@code before} and {@code after}, creating or
     * updating entities as needed, and removes any entity whose cab number no longer
     * appears in {@code after}.
     */
    private void save(RoutePlan plan, ShiftPlan before, ShiftPlan after) {
        Map<Integer, CabRoute> existing = plan.getCabs().stream()
                .collect(Collectors.toMap(CabRoute::getCabNumber, Function.identity()));
        Map<Integer, ShiftPlan.Cab> beforeCabs = before.cabs().stream()
                .collect(Collectors.toMap(ShiftPlan.Cab::cabNumber, Function.identity()));
        Set<Integer> keep = new HashSet<>();

        for (ShiftPlan.Cab cab : after.cabs()) {
            keep.add(cab.cabNumber());
            if (cab.equals(beforeCabs.get(cab.cabNumber()))) {
                continue;
            }
            CabRoute entity = existing.get(cab.cabNumber());
            if (entity == null) {
                entity = plan.addCab(cab.cabNumber());
            }
            writeCab(entity, cab);
        }
        plan.getCabs().removeIf(c -> !keep.contains(c.getCabNumber()));
        // A cab created in a numbering gap is appended last; keep this response in cab order too.
        plan.getCabs().sort(Comparator.comparingInt(CabRoute::getCabNumber));
    }

    private void writeCab(CabRoute entity, ShiftPlan.Cab cab) {
        entity.update(cab.vehicle(), cab.distanceKm(), cab.maxRideMinutes(), cab.escortRequired(), cab.cost(),
                cab.officeTime(), cab.timedBy());
        entity.getStops().clear();
        int sequence = 1;
        for (StopTiming t : cab.stops()) {
            Employee e = employees.getReferenceById(t.stop().employeeId());
            entity.getStops().add(new RouteStop(entity, e, sequence++, t.stop().location(), t.eta(), t.rideMinutes()));
        }
    }

    private RoutingParams params(RoutePlan plan) {
        return policy.paramsFor(plan.getDirection(), plan.getShiftTime(), plan.fleet(), plan.getMaxRideMinutes());
    }

    private Fleet fleetOf(CreatePlanRequest req) {
        if (req.fleet() != null && req.cabCapacity() != null) {
            throw new InvalidRequestException("give either cabCapacity or fleet, not both");
        }
        if (req.fleet() == null || req.fleet().isEmpty()) {
            return policy.defaultFleet(req.cabCapacity());
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
