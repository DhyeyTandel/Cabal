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
import dev.dhyey.cabrouter.routing.EtaCalculator;
import dev.dhyey.cabrouter.routing.GeoPoint;
import dev.dhyey.cabrouter.routing.PlannedCab;
import dev.dhyey.cabrouter.routing.RoutePlanner;
import dev.dhyey.cabrouter.routing.RoutingParams;
import dev.dhyey.cabrouter.routing.Stop;
import dev.dhyey.cabrouter.routing.StopTiming;
import dev.dhyey.cabrouter.routing.TravelModel;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 */
@Service
@Transactional
public class PlanningService {

    private final OfficeRepository offices;
    private final EmployeeRepository employees;
    private final RoutePlanRepository plans;
    private final RoutePlanner planner;
    private final TravelModel travel;
    private final RoutingProperties props;

    public PlanningService(OfficeRepository offices, EmployeeRepository employees, RoutePlanRepository plans,
                           RoutePlanner planner, TravelModel travel, RoutingProperties props) {
        this.offices = offices;
        this.employees = employees;
        this.plans = plans;
        this.planner = planner;
        this.travel = travel;
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

        int capacity = req.cabCapacity() != null ? req.cabCapacity() : props.defaultCabCapacity();
        int maxRide = req.maxRideMinutes() != null ? req.maxRideMinutes() : props.defaultMaxRideMinutes();
        RoutePlan plan = new RoutePlan(office, req.shiftTime(), req.direction(), capacity, maxRide);

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
            List<Stop> remaining = plan.getCabs().stream()
                    .flatMap(c -> stopsOf(c).stream())
                    .filter(s -> s.employeeId() != employeeId)
                    .toList();
            rebuildAll(plan, remaining);
        } else {
            List<Stop> remaining = stopsOf(cab).stream().filter(s -> s.employeeId() != employeeId).toList();
            if (remaining.isEmpty()) {
                plan.getCabs().remove(cab);
            } else {
                write(plan, cab, planner.buildCab(plan.getOffice().location(), remaining, params(plan)));
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

        List<PlannedCab> current = plan.getCabs().stream().map(c -> toPlanned(plan, c)).toList();
        RoutePlanner.Insertion insertion =
                planner.bestInsertion(plan.getOffice().location(), current, toStop(employee), params(plan));

        CabRoute target = insertion.opensNewCab() ? plan.addCab() : plan.getCabs().get(insertion.cabIndex());
        write(plan, target, insertion.cab());
        plan.touch();
        return PlanResponse.from(plan);
    }

    /** Re-optimise everything, e.g. after several local repairs have drifted from optimal. */
    public PlanResponse replan(long planId) {
        RoutePlan plan = load(planId);
        List<Stop> all = plan.getCabs().stream().flatMap(c -> stopsOf(c).stream()).toList();
        rebuildAll(plan, all);
        plan.touch();
        return PlanResponse.from(plan);
    }

    private void rebuildAll(RoutePlan plan, List<Stop> stops) {
        List<PlannedCab> planned = planner.plan(plan.getOffice().location(), stops, params(plan));
        plan.getCabs().clear();
        for (PlannedCab pc : planned) {
            write(plan, plan.addCab(), pc);
        }
    }

    private void write(RoutePlan plan, CabRoute cab, PlannedCab planned) {
        LocalDateTime officeTime = officeTime(plan);
        List<StopTiming> timings = EtaCalculator.compute(travel, plan.getOffice().location(), planned.stops(),
                plan.getDirection(), officeTime, props.dwellMinutes());

        cab.update(planned.distanceKm(), planned.maxRideMinutes(), planned.escortRequired(), officeTime);
        cab.getStops().clear();
        int sequence = 1;
        for (StopTiming t : timings) {
            Employee e = employees.getReferenceById(t.stop().employeeId());
            cab.getStops().add(new RouteStop(cab, e, sequence++, t.stop().location(), t.eta(), t.rideMinutes()));
        }
    }

    /** Rebuilds the routing view of a stored cab. Stored stops are in driving order. */
    private PlannedCab toPlanned(RoutePlan plan, CabRoute cab) {
        List<Stop> outward = new ArrayList<>(stopsOf(cab));
        if (plan.getDirection() == Direction.PICKUP) {
            Collections.reverse(outward);
        }
        return new PlannedCab(outward, cab.getDistanceKm(), cab.getMaxRideMinutes(), cab.isEscortRequired());
    }

    private List<Stop> stopsOf(CabRoute cab) {
        return cab.getStops().stream()
                .map(rs -> new Stop(rs.getEmployee().getId(), rs.location(), rs.getEmployee().isEscortSensitive()))
                .toList();
    }

    private RoutingParams params(RoutePlan plan) {
        return new RoutingParams(
                plan.getCabCapacity(),
                plan.getMaxRideMinutes(),
                props.dwellMinutes(),
                props.isNight(plan.getShiftTime().toLocalTime()),
                props.escortDetourTolerance(),
                props.sweepStarts());
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
