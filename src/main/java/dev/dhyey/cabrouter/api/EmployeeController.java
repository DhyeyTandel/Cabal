package dev.dhyey.cabrouter.api;

import dev.dhyey.cabrouter.api.dto.CreateEmployeeRequest;
import dev.dhyey.cabrouter.api.dto.EmployeeResponse;
import dev.dhyey.cabrouter.service.DirectoryService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final DirectoryService directory;

    public EmployeeController(DirectoryService directory) {
        this.directory = directory;
    }

    @PostMapping
    public ResponseEntity<EmployeeResponse> create(@Valid @RequestBody CreateEmployeeRequest req) {
        EmployeeResponse employee = directory.createEmployee(req);
        return ResponseEntity.created(URI.create("/api/employees/" + employee.id())).body(employee);
    }

    @GetMapping("/{id}")
    public EmployeeResponse get(@PathVariable long id) {
        return directory.getEmployee(id);
    }
}
