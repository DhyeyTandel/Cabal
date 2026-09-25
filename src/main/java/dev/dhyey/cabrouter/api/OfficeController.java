package dev.dhyey.cabrouter.api;

import dev.dhyey.cabrouter.api.dto.CreateOfficeRequest;
import dev.dhyey.cabrouter.api.dto.EmployeeResponse;
import dev.dhyey.cabrouter.api.dto.OfficeResponse;
import dev.dhyey.cabrouter.service.DirectoryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/offices")
public class OfficeController {

    private final DirectoryService directory;

    public OfficeController(DirectoryService directory) {
        this.directory = directory;
    }

    @PostMapping
    public ResponseEntity<OfficeResponse> create(@Valid @RequestBody CreateOfficeRequest req) {
        OfficeResponse office = directory.createOffice(req);
        return ResponseEntity.created(URI.create("/api/offices/" + office.id())).body(office);
    }

    @GetMapping("/{id}")
    public OfficeResponse get(@PathVariable long id) {
        return directory.getOffice(id);
    }

    @GetMapping("/{id}/employees")
    public List<EmployeeResponse> employees(@PathVariable long id) {
        return directory.listEmployees(id);
    }
}
