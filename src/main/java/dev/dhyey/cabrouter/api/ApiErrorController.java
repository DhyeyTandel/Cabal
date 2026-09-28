package dev.dhyey.cabrouter.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's default {@code /error} handling. Implementing {@link
 * ErrorController} makes Boot back off its own {@code BasicErrorController} (it only
 * registers one when no {@code ErrorController} bean already exists), so this is the sole
 * handler for the container's error dispatch as well as for anyone who requests
 * {@code /error} directly.
 *
 * <p>The response is always a generic {@link ProblemDetail}: the real status if the
 * container set one when forwarding here after an error, 404 if it did not (a direct
 * request to {@code /error}, previously answered with a bogus 500/status-999 body). Neither
 * branch ever surfaces the underlying exception's message, class name or stack trace.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ProblemDetail handleError(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (!(statusAttribute instanceof Integer statusCode)) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "not found");
        }
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ProblemDetail.forStatusAndDetail(status, "an unexpected error occurred");
    }
}
