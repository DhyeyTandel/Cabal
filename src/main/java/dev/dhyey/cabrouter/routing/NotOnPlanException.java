package dev.dhyey.cabrouter.routing;

/** Thrown when an operation is asked to act on an employee who is not on the plan. */
public class NotOnPlanException extends RuntimeException {

    private final long employeeId;

    public NotOnPlanException(long employeeId) {
        super("employee " + employeeId + " is not on this plan");
        this.employeeId = employeeId;
    }

    public long employeeId() {
        return employeeId;
    }
}
