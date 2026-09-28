package dev.dhyey.cabrouter.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalTime;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private double latitude;
    private double longitude;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "office_id")
    private Office office;

    /** Standing preference, used on PICKUP plans: must not be picked up before this time. */
    private LocalTime earliestPickup;

    /** Standing preference, used on DROP plans: must be dropped by this time. */
    private LocalTime latestDrop;

    protected Employee() {
    }

    public Employee(String name, Gender gender, double latitude, double longitude, Office office) {
        this.name = name;
        this.gender = gender;
        this.latitude = latitude;
        this.longitude = longitude;
        this.office = office;
    }

    public boolean isEscortSensitive() {
        return gender == Gender.FEMALE;
    }

    /** Sets both standing time-window preferences at once; either may be null to clear it. */
    public void setTimeWindow(LocalTime earliestPickup, LocalTime latestDrop) {
        this.earliestPickup = earliestPickup;
        this.latestDrop = latestDrop;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Gender getGender() {
        return gender;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public Office getOffice() {
        return office;
    }

    public LocalTime getEarliestPickup() {
        return earliestPickup;
    }

    public LocalTime getLatestDrop() {
        return latestDrop;
    }
}
