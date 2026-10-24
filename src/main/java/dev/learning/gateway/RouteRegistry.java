package dev.learning.gateway;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

@Component
public class RouteRegistry {
    private final List<Pool> pools;
    public RouteRegistry(GatewayProperties properties) { pools = properties.routes().stream().map(r -> new Pool(r, properties, System::nanoTime)).toList(); }
    Optional<Pool> match(String path) { return pools.stream().filter(p -> path.equals(p.route.prefix()) || path.startsWith(p.route.prefix() + "/")).findFirst(); }
    List<Pool> pools() { return pools; }
    public List<PoolStatus> snapshot() { return pools.stream().map(Pool::snapshot).toList(); }
    public record BackendStatus(URI origin, boolean healthy, String circuit, int active, long attempts,
            int consecutiveFailures, long retryAfterMs, Instant lastHealthCheck) {}
    public record PoolStatus(String id, String prefix, boolean cacheEnabled, GatewayProperties.Strategy strategy, List<BackendStatus> backends) {}
    enum Circuit { CLOSED, OPEN, HALF_OPEN }

    static final class Backend {
        final URI origin;
        boolean healthy;
        Circuit circuit = Circuit.CLOSED;
        boolean probeInFlight;
        int active, consecutiveFailures;
        long attempts, generation, healthRevision, openUntil;
        Instant lastHealthCheck;
        Backend(URI origin) { this.origin = origin; }
    }
    record HealthProbe(Backend backend, long revision) {}

    static final class Pool {
        final GatewayProperties.Route route;
        private final GatewayProperties properties;
        private final LongSupplier clock;
        private final List<Backend> backends;
        private int cursor;
        Pool(GatewayProperties.Route route, GatewayProperties properties, LongSupplier clock) {
            this.route=route; this.properties=properties; this.clock=clock;
            backends = route.backends().stream().map(Backend::new).toList();
        }
        synchronized Optional<Lease> acquire(Set<URI> excluded) {
            long now = clock.getAsLong();
            int chosen = -1;
            for (int offset=0; offset<backends.size(); offset++) {
                int index = (cursor + offset) % backends.size();
                Backend b = backends.get(index);
                if (!b.healthy || excluded.contains(b.origin) || b.probeInFlight
                        || (b.circuit == Circuit.OPEN && now < b.openUntil)) continue;
                if (chosen == -1 || b.active < backends.get(chosen).active) {
                    chosen=index;
                    if (properties.strategy() == GatewayProperties.Strategy.ROUND_ROBIN) break;
                }
            }
            if (chosen == -1) return Optional.empty();
            Backend b = backends.get(chosen);
            if (b.circuit != Circuit.CLOSED) { b.circuit=Circuit.HALF_OPEN; b.probeInFlight=true; }
            b.active++; b.attempts++;
            cursor=(chosen+1)%backends.size();
            return Optional.of(new Lease(b));
        }
        synchronized List<HealthProbe> healthProbes() { return backends.stream().map(b -> new HealthProbe(b, b.healthRevision)).toList(); }
        synchronized void health(HealthProbe probe, boolean healthy) {
            if (probe.backend.healthRevision == probe.revision) {
                probe.backend.healthy=healthy; probe.backend.lastHealthCheck=Instant.now();
            }
            // Readiness and request-failure circuit state are intentionally independent.
        }
        synchronized PoolStatus snapshot() {
            long now=clock.getAsLong();
            return new PoolStatus(route.id(), route.prefix(), route.cache(), properties.strategy(), backends.stream()
                    .map(b -> new BackendStatus(b.origin,b.healthy,b.circuit.name(),b.active,b.attempts,
                            b.consecutiveFailures, b.circuit == Circuit.OPEN ? Math.max(0,(b.openUntil-now)/1_000_000) : 0,b.lastHealthCheck)).toList());
        }

        final class Lease implements AutoCloseable {
            private final Backend backend;
            private final long generation;
            private boolean closed;
            private boolean failed=true;
            private boolean unreachable;
            Lease(Backend backend) { this.backend=backend; generation=backend.generation; }
            URI origin() { return backend.origin; }
            void result(int status, boolean transportFailure) { failed=status>=500; unreachable=transportFailure; }
            @Override public void close() {
                synchronized (Pool.this) {
                    if (closed) return;
                    closed=true; backend.active--;
                    if (unreachable) { backend.healthy=false; backend.healthRevision++; }
                    if (generation != backend.generation) return; // Ignore old completions after a circuit transition.
                    backend.probeInFlight=false;
                    if (!failed) { backend.consecutiveFailures=0; backend.circuit=Circuit.CLOSED; }
                    else if (++backend.consecutiveFailures >= properties.circuitThreshold() || backend.circuit==Circuit.HALF_OPEN) {
                        backend.circuit=Circuit.OPEN;
                        backend.openUntil=clock.getAsLong()+properties.circuitOpenMs()*1_000_000;
                        backend.generation++;
                    }
                }
            }
        }
    }
}
