package dev.dhyey.cabrouter.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link SandboxGuard} in isolation, with a controllable clock so the rolling window is exact. */
class SandboxGuardTest {

    @Test
    void anEleventhRequestInAMinuteIsRejectedButAnotherClientIsNot() {
        long[] now = {0L};
        SandboxGuard guard = new SandboxGuard(10, 100, () -> now[0]);
        for (int i = 0; i < 10; i++) {
            guard.tryAcquire("client-a").orElseThrow().close();
        }

        assertThat(guard.tryAcquire("client-a")).isEmpty();
        assertThat(guard.tryAcquire("client-b")).isPresent();
    }

    @Test
    void aThirdConcurrentAcquireIsRejectedUntilOneIsReleased() {
        SandboxGuard guard = new SandboxGuard(100, 2, System::currentTimeMillis);
        SandboxGuard.Ticket first = guard.tryAcquire("a").orElseThrow();
        SandboxGuard.Ticket second = guard.tryAcquire("b").orElseThrow();

        assertThat(guard.tryAcquire("c")).isEmpty();

        first.close();
        assertThat(guard.tryAcquire("d")).isPresent();
        second.close();
    }

    @Test
    void entriesArePrunedWhenTheClientMapGrowsLarge() {
        long[] now = {0L};
        SandboxGuard guard = new SandboxGuard(10, 2, () -> now[0]);
        for (int i = 0; i < 10_001; i++) {
            guard.tryAcquire("client-" + i).orElseThrow().close();
        }

        now[0] += 61_000; // past every entry's 60s window
        guard.tryAcquire("client-trigger").orElseThrow().close();

        assertThat(guard.trackedClients()).isLessThan(10_001);
    }
}
