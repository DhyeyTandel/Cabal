package dev.dhyey.cabrouter.api;

import dev.dhyey.cabrouter.routing.AlreadyOnPlanException;
import dev.dhyey.cabrouter.routing.CannotDissolveException;
import dev.dhyey.cabrouter.routing.FleetExhaustedException;
import dev.dhyey.cabrouter.routing.NoSuchCabException;
import dev.dhyey.cabrouter.routing.NotOnPlanException;
import dev.dhyey.cabrouter.service.InvalidRequestException;
import dev.dhyey.cabrouter.service.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps service exceptions to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalid(InvalidRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }


    @ExceptionHandler(NotOnPlanException.class)
    ProblemDetail notOnPlan(NotOnPlanException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AlreadyOnPlanException.class)
    ProblemDetail alreadyOnPlan(AlreadyOnPlanException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NoSuchCabException.class)
    ProblemDetail noSuchCab(NoSuchCabException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CannotDissolveException.class)
    ProblemDetail cannotDissolve(CannotDissolveException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** The request is well formed but the fleet cannot serve it: 422. */
    @ExceptionHandler(FleetExhaustedException.class)
    ProblemDetail fleetExhausted(FleetExhaustedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentEdit(ObjectOptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the plan was changed by another request; reload it and retry");
    }
}
