package dev.dhyey.cabrouter.routing;

/**
 * Thrown when a cab cannot be dissolved: some rider had no room in a nearby cab, a
 * receiving cab would newly need an escort, or dissolving would not save money.
 */
public class CannotDissolveException extends RuntimeException {

    private final int cabNumber;
    private final String reason;

    public CannotDissolveException(int cabNumber, String reason) {
        super("cab " + cabNumber + " cannot be dissolved: " + reason);
        this.cabNumber = cabNumber;
        this.reason = reason;
    }

    public int cabNumber() {
        return cabNumber;
    }

    public String reason() {
        return reason;
    }
}
