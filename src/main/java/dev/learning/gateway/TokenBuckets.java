package dev.learning.gateway;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** Bounded, atomic token buckets. Monotonic time avoids clock-change refill bugs. */
@Component
public class TokenBuckets {
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final LongSupplier clock;
    private static final long IDLE_NANOS = 120_000_000_000L;
    private static class Bucket { double tokens; long updated; Bucket(double t, long n) { tokens=t; updated=n; } }
    public record Decision(boolean allowed, int remaining, int retryAfterSeconds) {}
    public TokenBuckets() { this(System::nanoTime); }
    TokenBuckets(LongSupplier clock) { this.clock = clock; }

    public synchronized Decision take(String key, int capacity, double refillPerSecond) {
        long now = clock.getAsLong();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= 1024) buckets.values().removeIf(b -> now - b.updated > IDLE_NANOS);
            if (buckets.size() >= 1024) return new Decision(false, 0, 60);
            bucket = new Bucket(capacity, now);
            buckets.put(key, bucket);
        }
        bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.updated) / 1_000_000_000.0 * refillPerSecond);
        bucket.updated = now;
        if (bucket.tokens < 1) return new Decision(false, 0, Math.max(1, (int)Math.ceil((1 - bucket.tokens) / refillPerSecond)));
        bucket.tokens--;
        return new Decision(true, (int)bucket.tokens, 0);
    }
}
