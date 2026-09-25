package dev.dhyey.cabrouter.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cab_routes")
public class CabRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id")
    private RoutePlan plan;

    private int cabNumber;
    private double distanceKm;
    private double maxRideMinutes;
    private boolean escortRequired;

    /** Office arrival for pickups, office departure for drops. */
    private LocalDateTime officeTime;

    /** Stops in the order the driver visits them. */
    @OneToMany(mappedBy = "cab", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence")
    private List<RouteStop> stops = new ArrayList<>();

    protected CabRoute() {
    }

    CabRoute(RoutePlan plan, int cabNumber) {
        this.plan = plan;
        this.cabNumber = cabNumber;
    }

    public void update(double distanceKm, double maxRideMinutes, boolean escortRequired, LocalDateTime officeTime) {
        this.distanceKm = distanceKm;
        this.maxRideMinutes = maxRideMinutes;
        this.escortRequired = escortRequired;
        this.officeTime = officeTime;
    }

    public boolean carries(long employeeId) {
        return stops.stream().anyMatch(s -> s.getEmployee().getId() == employeeId);
    }

    public Long getId() {
        return id;
    }

    public int getCabNumber() {
        return cabNumber;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public double getMaxRideMinutes() {
        return maxRideMinutes;
    }

    public boolean isEscortRequired() {
        return escortRequired;
    }

    public LocalDateTime getOfficeTime() {
        return officeTime;
    }

    public List<RouteStop> getStops() {
        return stops;
    }
}
