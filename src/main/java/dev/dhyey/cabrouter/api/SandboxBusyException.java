package dev.dhyey.cabrouter.api;

/** Thrown when {@link SandboxGuard} rejects a sandbox request over its rate or concurrency limit. */
class SandboxBusyException extends RuntimeException {
}
