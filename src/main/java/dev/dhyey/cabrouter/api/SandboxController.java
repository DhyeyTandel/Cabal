package dev.dhyey.cabrouter.api;

import dev.dhyey.cabrouter.api.dto.SandboxPlanRequest;
import dev.dhyey.cabrouter.api.dto.SandboxPlanResponse;
import dev.dhyey.cabrouter.service.SandboxService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public "try it" sandbox: anyone can plan a small demo shift with the real engine,
 * with nothing saved. This is the one write endpoint exempt from {@link ApiKeyFilter} (see
 * its Javadoc), so {@link SandboxGuard} is what keeps it from being abused.
 */
@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {

    private final SandboxService sandbox;
    private final SandboxGuard guard;

    public SandboxController(SandboxService sandbox, SandboxGuard guard) {
        this.sandbox = sandbox;
        this.guard = guard;
    }

    @PostMapping("/plan")
    public SandboxPlanResponse plan(@Valid @RequestBody SandboxPlanRequest req, HttpServletRequest request) {
        try (SandboxGuard.Ticket ticket = guard.tryAcquire(clientKey(request)).orElseThrow(SandboxBusyException::new)) {
            return sandbox.plan(req);
        }
    }

    /**
     * The app only listens on 127.0.0.1 in production, so only the Cloudflare tunnel can
     * connect and set this header; falls back to the socket address for local dev and tests.
     */
    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("CF-Connecting-IP");
        return forwarded != null && !forwarded.isBlank() ? forwarded : request.getRemoteAddr();
    }
}
