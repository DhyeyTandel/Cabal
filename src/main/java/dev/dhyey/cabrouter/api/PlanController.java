package dev.dhyey.cabrouter.api;

import dev.dhyey.cabrouter.api.dto.CreatePlanRequest;
import dev.dhyey.cabrouter.api.dto.PlanResponse;
import dev.dhyey.cabrouter.routing.ReplanStrategy;
import dev.dhyey.cabrouter.service.PlanningService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanningService planning;

    public PlanController(PlanningService planning) {
        this.planning = planning;
    }

    @PostMapping
    public ResponseEntity<PlanResponse> create(@Valid @RequestBody CreatePlanRequest req) {
        PlanResponse plan = planning.create(req);
        return ResponseEntity.created(URI.create("/api/plans/" + plan.id())).body(plan);
    }

    @GetMapping("/{id}")
    public PlanResponse get(@PathVariable long id) {
        return planning.get(id);
    }

    /** Cancellation. {@code strategy=LOCAL} (default) touches only the affected cab. */
    @DeleteMapping("/{id}/employees/{employeeId}")
    public PlanResponse cancel(@PathVariable long id, @PathVariable long employeeId,
                               @RequestParam(defaultValue = "LOCAL") ReplanStrategy strategy) {
        return planning.cancel(id, employeeId, strategy);
    }

    /** Late booking, placed by cheapest insertion. */
    @PostMapping("/{id}/employees/{employeeId}")
    public PlanResponse add(@PathVariable long id, @PathVariable long employeeId) {
        return planning.add(id, employeeId);
    }

    @PostMapping("/{id}/replan")
    public PlanResponse replan(@PathVariable long id) {
        return planning.replan(id);
    }
}
