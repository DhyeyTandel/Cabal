package dev.dhyey.cabrouter.api.dto;

/**
 * The two fleets the public sandbox offers. {@link #SEDANS} is unlimited 4-seat SEDANs
 * at default prices; {@link #MIXED} adds up to 2 SUVs on top. See {@code SandboxService}.
 */
public enum SandboxFleet {
    SEDANS,
    MIXED
}
