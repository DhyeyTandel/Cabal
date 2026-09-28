package dev.dhyey.cabrouter.api;

import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Abuse limits for the public sandbox endpoint: at most {@code perMinute} requests in any
 * rolling 60 seconds per client key, checked before anything waits, and at most
 * {@code maxConcurrent} plans computing at once across every client. When every slot is
 * taken, a request may wait up to {@code waitMillis} for one to free ({@link
 * Semaphore#tryAcquire(long, TimeUnit)}), but at most {@code maxWaiting} requests may be
 * waiting at once; anything past that is rejected immediately rather than piling up. Both
 * limits are in-memory and best-effort; a restart clears them.
 */
@Component
public class SandboxGuard {

    /** Client keys are swept of expired timestamps once the map grows past this, to bound memory. */
    private static final int PRUNE_THRESHOLD = 10_000;
    private static final long WINDOW_MILLIS = 60_000;

    private final int perMinute;
    private final Semaphore concurrency;
    private final long waitMillis;
    private final int maxWaiting;
    private final AtomicInteger waiting = new AtomicInteger();
    private final LongSupplier clockMillis;
    private final ConcurrentHashMap<String, Deque<Long>> requestTimes = new ConcurrentHashMap<>();

    @Autowired
    public SandboxGuard(@Value("${routing.sandbox.per-minute:10}") int perMinute,
                        @Value("${routing.sandbox.max-concurrent:3}") int maxConcurrent,
                        @Value("${routing.sandbox.wait-ms:2000}") long waitMillis,
                        @Value("${routing.sandbox.max-waiting:20}") int maxWaiting) {
        this(perMinute, maxConcurrent, waitMillis, maxWaiting, System::currentTimeMillis);
    }

    /** For tests: a controllable clock instead of the wall clock. */
    SandboxGuard(int perMinute, int maxConcurrent, long waitMillis, int maxWaiting, LongSupplier clockMillis) {
        this.perMinute = perMinute;
        this.concurrency = new Semaphore(maxConcurrent);
        this.waitMillis = waitMillis;
        this.maxWaiting = maxWaiting;
        this.clockMillis = clockMillis;
    }

    /**
     * Reserves one global concurrency slot and one request from {@code clientKey}'s
     * rolling-minute budget, or an empty result if either is exhausted. The rate limit is
     * checked first, before anything waits (a concurrency slot taken but then rejected on
     * the rate check is released immediately). If no slot is free immediately, this waits
     * up to {@code waitMillis} for one, unless {@code maxWaiting} requests are already
     * waiting, in which case it is rejected immediately without waiting. An interrupted
     * wait restores the thread's interrupt flag and is rejected. The caller must close the
     * returned {@link Ticket} exactly once, however the request ends.
     */
    public Optional<Ticket> tryAcquire(String clientKey) {
        if (!allowRate(clientKey)) {
            return Optional.empty();
        }
        if (concurrency.tryAcquire()) {
            return Optional.of(new Ticket(concurrency));
        }
        if (!reserveWaitingSlot()) {
            return Optional.empty();
        }
        try {
            if (concurrency.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                return Optional.of(new Ticket(concurrency));
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            waiting.decrementAndGet();
        }
    }

    /** Claims one of {@code maxWaiting} waiting slots, or reports there is none free. */
    private boolean reserveWaitingSlot() {
        int current;
        do {
            current = waiting.get();
            if (current >= maxWaiting) {
                return false;
            }
        } while (!waiting.compareAndSet(current, current + 1));
        return true;
    }

    private boolean allowRate(String clientKey) {
        long now = clockMillis.getAsLong();
        long windowStart = now - WINDOW_MILLIS;
        pruneIfNeeded(windowStart);
        Deque<Long> times = requestTimes.computeIfAbsent(clientKey, k -> new ConcurrentLinkedDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < windowStart) {
                times.pollFirst();
            }
            if (times.size() >= perMinute) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    private void pruneIfNeeded(long windowStart) {
        if (requestTimes.size() <= PRUNE_THRESHOLD) {
            return;
        }
        for (Map.Entry<String, Deque<Long>> entry : requestTimes.entrySet()) {
            Deque<Long> times = entry.getValue();
            synchronized (times) {
                while (!times.isEmpty() && times.peekFirst() < windowStart) {
                    times.pollFirst();
                }
                if (times.isEmpty()) {
                    requestTimes.remove(entry.getKey(), times);
                }
            }
        }
    }

    /** How many client keys are currently tracked; for tests. */
    int trackedClients() {
        return requestTimes.size();
    }

    /** One reserved concurrency slot. {@link #close} releases it; call it exactly once. */
    public static final class Ticket implements AutoCloseable {
        private final Semaphore concurrency;

        private Ticket(Semaphore concurrency) {
            this.concurrency = concurrency;
        }

        @Override
        public void close() {
            concurrency.release();
        }
    }
}
