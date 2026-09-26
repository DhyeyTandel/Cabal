package dev.dhyey.cabrouter.travel;

public class OsrmException extends RuntimeException {

    public OsrmException(String message) {
        super(message);
    }

    public OsrmException(String message, Throwable cause) {
        super(message, cause);
    }
}
