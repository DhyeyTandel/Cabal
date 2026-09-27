package dev.dhyey.cabrouter.routing;

/** Thrown when a late booking names an employee who already has a stop on the plan. */
public class AlreadyOnPlanException extends RuntimeException {

    private final long employeeId;

    public AlreadyOnPlanException(long employeeId) {
        super("employee " + employeeId + " is already on this plan");
        this.employeeId = employeeId;
    }

    public long employeeId() {
        return employeeId;
    }
}
