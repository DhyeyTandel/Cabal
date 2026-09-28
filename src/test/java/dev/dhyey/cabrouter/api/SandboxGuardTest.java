package dev.dhyey.cabrouter.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** {@link SandboxGuard} in isolation, with a controllable clock so the rolling window is exact. */
class SandboxGuardTest {

    @Test
    void anEleventhRequestInAMinuteIsRejectedButAnotherClientIsNot() {
        long[] now = {0L};
        SandboxGuard guard = new SandboxGuard(10, 100, 2000, 20, () -> now[0]);
        for (int i = 0; i < 10; i++) {
            guard.tryAcquire("client-a").orElseThrow().close();
        }

        assertThat(guard.tryAcquire("client-a")).isEmpty();
        assertThat(guard.tryAcquire("client-b")).isPresent();
    }

    @Test
    void aThirdConcurrentAcquireIsRejectedUntilOneIsReleased() {
        SandboxGuard guard = new SandboxGuard(100, 2, 0, 20, System::currentTimeMillis);
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
        SandboxGuard guard = new SandboxGuard(10, 2, 2000, 20, () -> now[0]);
        for (int i = 0; i < 10_001; i++) {
            guard.tryAcquire("client-" + i).orElseThrow().close();
        }

        now[0] += 61_000; // past every entry's 60s window
        guard.tryAcquire("client-trigger").orElseThrow().close();

        assertThat(guard.trackedClients()).isLessThan(10_001);
    }

    @Test
    void aFourthConcurrentAcquireWaitsAndSucceedsWhenASlotFreesWithinTheTimeout() throws Exception {
        SandboxGuard guard = new SandboxGuard(100, 3, 2000, 20, System::currentTimeMillis);
        SandboxGuard.Ticket first = guard.tryAcquire("a").orElseThrow();
        guard.tryAcquire("b").orElseThrow();
        guard.tryAcquire("c").orElseThrow();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<SandboxGuard.Ticket>> waiter = pool.submit(() -> guard.tryAcquire("d"));
            Thread.sleep(200); // give the waiter time to actually block on the semaphore
            first.close(); // frees a slot well inside the 2s timeout

            Optional<SandboxGuard.Ticket> result = waiter.get(2, TimeUnit.SECONDS);
            assertThat(result).isPresent();
            result.get().close();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aWaiterThatNeverGetsASlotTimesOutAndIsRejected() throws Exception {
        SandboxGuard guard = new SandboxGuard(100, 1, 100, 20, System::currentTimeMillis);
        SandboxGuard.Ticket first = guard.tryAcquire("a").orElseThrow();
        try {
            assertThat(guard.tryAcquire("b")).isEmpty();
        } finally {
            first.close();
        }
    }

    @Test
    void theTwentyFirstWaiterIsRejectedImmediatelyWithoutWaiting() throws Exception {
        SandboxGuard guard = new SandboxGuard(1000, 1, 5000, 20, System::currentTimeMillis);
        SandboxGuard.Ticket held = guard.tryAcquire("holder").orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch aboutToWait = new CountDownLatch(20);
        try {
            List<Future<Optional<SandboxGuard.Ticket>>> waiters = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String client = "waiter-" + i;
                waiters.add(pool.submit(() -> {
                    aboutToWait.countDown();
                    return guard.tryAcquire(client);
                }));
            }
            // Give the 20 waiters a moment to actually enter the wait, then the 21st must be
            // rejected immediately rather than queueing behind them.
            aboutToWait.await(2, TimeUnit.SECONDS);
            Thread.sleep(200);

            long start = System.nanoTime();
            assertThat(guard.tryAcquire("waiter-21")).isEmpty();
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMillis).isLessThan(500);

            held.close();
            for (Future<Optional<SandboxGuard.Ticket>> waiter : waiters) {
                waiter.get(5, TimeUnit.SECONDS).ifPresent(SandboxGuard.Ticket::close);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anInterruptedWaitIsRejectedAndRestoresTheInterruptFlag() throws Exception {
        SandboxGuard guard = new SandboxGuard(100, 1, 5000, 20, System::currentTimeMillis);
        SandboxGuard.Ticket held = guard.tryAcquire("holder").orElseThrow();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            boolean[] interruptedAfterward = {false};
            Future<Optional<SandboxGuard.Ticket>> waiter = pool.submit(() -> {
                Optional<SandboxGuard.Ticket> result = guard.tryAcquire("interrupted-client");
                interruptedAfterward[0] = Thread.currentThread().isInterrupted();
                return result;
            });

            Thread.sleep(200);
            // Interrupt the pool's single worker thread while it is blocked in tryAcquire.
            pool.shutdownNow();

            Optional<SandboxGuard.Ticket> result = waiter.get(2, TimeUnit.SECONDS);
            assertThat(result).isEmpty();
            assertThat(interruptedAfterward[0]).isTrue();
        } finally {
            held.close();
            pool.shutdownNow();
        }
    }
}
