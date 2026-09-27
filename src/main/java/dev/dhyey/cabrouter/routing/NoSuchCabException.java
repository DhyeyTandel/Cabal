package dev.dhyey.cabrouter.routing;

/** Thrown when an operation names a cab number that is not on the plan. */
public class NoSuchCabException extends RuntimeException {

    private final int cabNumber;

    public NoSuchCabException(int cabNumber) {
        super("cab " + cabNumber + " is not on this plan");
        this.cabNumber = cabNumber;
    }

    public int cabNumber() {
        return cabNumber;
    }
}
