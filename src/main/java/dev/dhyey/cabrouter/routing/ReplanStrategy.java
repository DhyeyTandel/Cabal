package dev.dhyey.cabrouter.routing;

public enum ReplanStrategy {
    /** Only the affected cab is re-sequenced. Every other driver's route stays as issued. */
    LOCAL,
    /** Re-cluster the whole shift. Often shorter overall, but may move people between cabs. */
    FULL
}
