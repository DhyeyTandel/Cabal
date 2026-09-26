package dev.dhyey.cabrouter.domain;

import dev.dhyey.cabrouter.routing.Direction;
import dev.dhyey.cabrouter.routing.Fleet;
import dev.dhyey.cabrouter.routing.VehicleType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The routed plan for one office, shift and direction. Every change bumps
 * {@code revision}, and {@code @Version} turns two concurrent edits into a 409
 * instead of a lost update.
 */
@Entity
@Table(name = "route_plans")
public class RoutePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "office_id")
    private Office office;

    private LocalDateTime shiftTime;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    @ElementCollection
    @CollectionTable(name = "plan_vehicle_types", joinColumns = @JoinColumn(name = "plan_id"))
    @OrderBy("seats")
    private List<PlanVehicleType> fleet = new ArrayList<>();

    private int maxRideMinutes;
    private int revision;

    @Version
    private long version;

    private Instant createdAt;
    private Instant updatedAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("cabNumber")
    private List<CabRoute> cabs = new ArrayList<>();

    protected RoutePlan() {
    }

    public RoutePlan(Office office, LocalDateTime shiftTime, Direction direction, Fleet fleet, int maxRideMinutes) {
        this.office = office;
        this.shiftTime = shiftTime;
        this.direction = direction;
        for (Fleet.Entry e : fleet.entries()) {
            this.fleet.add(new PlanVehicleType(e.type(), e.available()));
        }
        this.maxRideMinutes = maxRideMinutes;
        this.revision = 1;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void touch() {
        revision++;
        updatedAt = Instant.now();
    }

    public Optional<CabRoute> findCabOf(long employeeId) {
        return cabs.stream().filter(c -> c.carries(employeeId)).findFirst();
    }

    public CabRoute addCab() {
        int next = cabs.stream().mapToInt(CabRoute::getCabNumber).max().orElse(0) + 1;
        CabRoute cab = new CabRoute(this, next);
        cabs.add(cab);
        return cab;
    }

    public Long getId() {
        return id;
    }

    public Office getOffice() {
        return office;
    }

    public LocalDateTime getShiftTime() {
        return shiftTime;
    }

    public Direction getDirection() {
        return direction;
    }

    public Fleet fleet() {
        return new Fleet(fleet.stream().map(PlanVehicleType::toEntry).toList());
    }

    /** The fleet's vehicle type with this name, prices included. */
    public VehicleType vehicleNamed(String name) {
        return fleet.stream().filter(v -> v.getName().equals(name)).findFirst()
                .map(PlanVehicleType::type)
                .orElseThrow(() -> new IllegalStateException("plan " + id + " has no vehicle type " + name));
    }

    public List<PlanVehicleType> getFleet() {
        return fleet;
    }

    public int getMaxRideMinutes() {
        return maxRideMinutes;
    }

    public int getRevision() {
        return revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<CabRoute> getCabs() {
        return cabs;
    }
}
