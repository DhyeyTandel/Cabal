package dev.dhyey.cabrouter.service;

import dev.dhyey.cabrouter.api.dto.CreateEmployeeRequest;
import dev.dhyey.cabrouter.api.dto.CreateOfficeRequest;
import dev.dhyey.cabrouter.api.dto.EmployeeResponse;
import dev.dhyey.cabrouter.api.dto.OfficeResponse;
import dev.dhyey.cabrouter.domain.Employee;
import dev.dhyey.cabrouter.domain.EmployeeRepository;
import dev.dhyey.cabrouter.domain.Office;
import dev.dhyey.cabrouter.domain.OfficeRepository;
import java.time.LocalTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Offices and employees: the reference data that plans are built from. */
@Service
@Transactional
public class DirectoryService {

    private final OfficeRepository offices;
    private final EmployeeRepository employees;

    public DirectoryService(OfficeRepository offices, EmployeeRepository employees) {
        this.offices = offices;
        this.employees = employees;
    }

    public OfficeResponse createOffice(CreateOfficeRequest req) {
        return OfficeResponse.from(offices.save(new Office(req.name(), req.latitude(), req.longitude())));
    }

    @Transactional(readOnly = true)
    public OfficeResponse getOffice(long id) {
        return OfficeResponse.from(findOffice(id));
    }

    public EmployeeResponse createEmployee(CreateEmployeeRequest req) {
        Office office = findOffice(req.officeId());
        Employee employee = new Employee(req.name(), req.gender(), req.latitude(), req.longitude(), office);
        employee.setTimeWindow(req.earliestPickup(), req.latestDrop());
        return EmployeeResponse.from(employees.save(employee));
    }

    @Transactional(readOnly = true)
    public EmployeeResponse getEmployee(long id) {
        return employees.findById(id)
                .map(EmployeeResponse::from)
                .orElseThrow(() -> new NotFoundException("employee " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> listEmployees(long officeId) {
        findOffice(officeId);
        return employees.findByOfficeIdOrderById(officeId).stream().map(EmployeeResponse::from).toList();
    }

    /** Sets an employee's standing time-window preferences; either may be null to clear it. */
    public EmployeeResponse setTimeWindow(long id, LocalTime earliestPickup, LocalTime latestDrop) {
        Employee employee = findEmployee(id);
        employee.setTimeWindow(earliestPickup, latestDrop);
        return EmployeeResponse.from(employee);
    }

    private Office findOffice(long id) {
        return offices.findById(id).orElseThrow(() -> new NotFoundException("office " + id + " not found"));
    }

    private Employee findEmployee(long id) {
        return employees.findById(id).orElseThrow(() -> new NotFoundException("employee " + id + " not found"));
    }
}
