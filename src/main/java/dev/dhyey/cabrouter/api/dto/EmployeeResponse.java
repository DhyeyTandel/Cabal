package dev.dhyey.cabrouter.api.dto;

import dev.dhyey.cabrouter.domain.Employee;
import dev.dhyey.cabrouter.domain.Gender;

public record EmployeeResponse(long id, String name, Gender gender, double latitude, double longitude, long officeId) {

    public static EmployeeResponse from(Employee e) {
        return new EmployeeResponse(e.getId(), e.getName(), e.getGender(), e.getLatitude(), e.getLongitude(),
                e.getOffice().getId());
    }
}
