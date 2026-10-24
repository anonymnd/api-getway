package dev.learning.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class Telemetry extends OncePerRequestFilter {
    public record Event(long sequence, Instant at, String requestId, String method, String service,
            int status, String outcome, int attempts, String backend, double durationMs) {}
    public record Snapshot(long requests, long completed, int active, long errors, long unauthorized,
            long rateLimited, long cacheHits, long cacheMisses, long retries, double averageResponseMs,
            List<Event> events, Instant observedAt) {}
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long requests, completed, errors, unauthorized, rateLimited, cacheHits, cacheMisses, retries, nanos;
    private int active;

    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        request.setAttribute("requestId", id);
        response.setHeader("X-Request-Id", id);
        synchronized (this) { requests++; active++; }
        long start = System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            synchronized (this) {
                long elapsed = System.nanoTime() - start;
                active--; completed++; nanos += elapsed;
                int status = response.getStatus();
                if (status >= 500) errors++;
                if (status == 401) unauthorized++;
                if (status == 429) rateLimited++;
                String outcome = response.getHeader("X-Gateway-Outcome");
                if (outcome == null) outcome = status == 401 ? "AUTH_DENIED" : "REJECTED";
                String attempts = response.getHeader("X-Gateway-Attempts");
                events.addFirst(new Event(completed, Instant.now(), id, request.getMethod(),
                        String.valueOf(request.getAttribute("service") == null ? "—" : request.getAttribute("service")),
                        status, outcome, attempts == null ? 0 : Integer.parseInt(attempts),
                        response.getHeader("X-Gateway-Backend"), elapsed / 1_000_000.0));
                if (events.size() > 50) events.removeLast();
            }
        }
    }
    public synchronized void hit() { cacheHits++; }
    public synchronized void miss() { cacheMisses++; }
    public synchronized void retry() { retries++; }
    public synchronized Snapshot snapshot() {
        return new Snapshot(requests, completed, active, errors, unauthorized, rateLimited, cacheHits, cacheMisses,
                retries, completed == 0 ? 0 : nanos / 1_000_000.0 / completed, List.copyOf(events), Instant.now());
    }
}
