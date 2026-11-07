package ar.com.taller.acceso;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IntentosLogin {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_FAILURES = 5;
    private static final int MAX_KEYS = 10_000;
    private final Map<String, Counter> counters = new HashMap<>();
    private final Clock clock;

    public IntentosLogin(Clock clock) { this.clock = clock; }

    public synchronized Permit begin(String account, String origin) {
        Instant now = clock.instant();
        counters.values().removeIf(c -> c.pending == 0 && !now.isBefore(c.start.plus(WINDOW))
                && (c.lockedUntil == null || !now.isBefore(c.lockedUntil)));
        Counter a = counter("cuenta:" + account, now);
        Counter o = counter("origen:" + origin, now);
        long wait = Math.max(waitFor(a, now), waitFor(o, now));
        if (wait > 0) throw new LimitExceeded(wait);
        a.pending++;
        o.pending++;
        return new Permit(a, o);
    }

    private Counter counter(String key, Instant now) {
        Counter existing = counters.get(key);
        if (existing != null) return existing;
        if (counters.size() >= MAX_KEYS) throw new LimitExceeded(60);
        Counter created = new Counter(now);
        counters.put(key, created);
        return created;
    }

    private long waitFor(Counter c, Instant now) {
        if (c.lockedUntil != null && now.isBefore(c.lockedUntil)) {
            return Math.max(1, Duration.between(now, c.lockedUntil).toSeconds() + 1);
        }
        return c.failures + c.pending >= MAX_FAILURES ? 1 : 0;
    }

    private void failure(Counter c, Instant now) {
        if (!now.isBefore(c.start.plus(WINDOW))) {
            c.start = now;
            c.failures = 0;
        }
        if (++c.failures >= MAX_FAILURES) c.lockedUntil = now.plus(WINDOW);
    }

    public final class Permit implements AutoCloseable {
        private final Counter account;
        private final Counter origin;
        private boolean completed;
        private Permit(Counter account, Counter origin) { this.account = account; this.origin = origin; }

        public void failed() {
            synchronized (IntentosLogin.this) {
                if (completed) return;
                Instant now = clock.instant();
                account.pending--;
                origin.pending--;
                failure(account, now);
                failure(origin, now);
                completed = true;
            }
        }

        public void succeeded() {
            synchronized (IntentosLogin.this) {
                if (completed) return;
                account.pending--;
                origin.pending--;
                account.failures = 0;
                account.start = clock.instant();
                account.lockedUntil = null;
                completed = true;
                // A successful login does not erase failures by other accounts at the same origin.
            }
        }

        @Override public void close() {
            synchronized (IntentosLogin.this) {
                if (!completed) {
                    account.pending--;
                    origin.pending--;
                    completed = true;
                }
            }
        }
    }

    private static final class Counter {
        private Instant start;
        private Instant lockedUntil;
        private int failures;
        private int pending;
        private Counter(Instant start) { this.start = start; }
    }

    public static final class LimitExceeded extends RuntimeException {
        private final long seconds;
        public LimitExceeded(long seconds) { this.seconds = seconds; }
        public long seconds() { return seconds; }
    }
}
