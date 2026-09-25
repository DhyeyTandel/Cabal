package dev.dhyey.cabrouter.domain;

import dev.dhyey.cabrouter.routing.GeoPoint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "route_stops")
public class RouteStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cab_id")
    private CabRoute cab;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "stop_sequence")
    private int sequence;

    private double latitude;
    private double longitude;
    private LocalDateTime eta;
    private double rideMinutes;

    protected RouteStop() {
    }

    public RouteStop(CabRoute cab, Employee employee, int sequence, GeoPoint location, LocalDateTime eta, double rideMinutes) {
        this.cab = cab;
        this.employee = employee;
        this.sequence = sequence;
        this.latitude = location.lat();
        this.longitude = location.lng();
        this.eta = eta;
        this.rideMinutes = rideMinutes;
    }

    public GeoPoint location() {
        return new GeoPoint(latitude, longitude);
    }

    public Employee getEmployee() {
        return employee;
    }

    public int getSequence() {
        return sequence;
    }

    public LocalDateTime getEta() {
        return eta;
    }

    public double getRideMinutes() {
        return rideMinutes;
    }
}
