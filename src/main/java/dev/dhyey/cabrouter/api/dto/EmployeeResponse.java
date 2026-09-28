package dev.dhyey.cabrouter.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.dhyey.cabrouter.domain.Employee;
import dev.dhyey.cabrouter.domain.Gender;
import java.time.LocalTime;

public record EmployeeResponse(long id, String name, Gender gender, double latitude, double longitude, long officeId,
                               @JsonFormat(pattern = "HH:mm") LocalTime earliestPickup,
                               @JsonFormat(pattern = "HH:mm") LocalTime latestDrop) {

    public static EmployeeResponse from(Employee e) {
        return new EmployeeResponse(e.getId(), e.getName(), e.getGender(), e.getLatitude(), e.getLongitude(),
                e.getOffice().getId(), e.getEarliestPickup(), e.getLatestDrop());
    }
}
