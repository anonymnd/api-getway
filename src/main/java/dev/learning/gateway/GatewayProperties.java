package dev.learning.gateway;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway")
public record GatewayProperties(String jwtSecret, String demoPassword, Strategy strategy,
        long healthIntervalMs, int healthTimeoutMs, int requestTimeoutMs,
        int circuitThreshold, long circuitOpenMs, int rateCapacity, int rateRefillPerSecond,
        int authCapacity, long cacheTtlMs, List<Route> routes) {
    public enum Strategy { ROUND_ROBIN, LEAST_CONNECTIONS }
    public record Route(String id, String prefix, boolean cache, List<URI> backends) {
        public Route {
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,31}") || prefix == null
                    || !prefix.matches("/api/[a-z][a-z0-9-]{0,31}") || backends == null || backends.isEmpty()) {
                throw new IllegalArgumentException("Each route needs an id, /api/service prefix and backend origins");
            }
            backends = backends.stream().map(uri -> {
                if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                        || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
                    throw new IllegalArgumentException("Backend must be an HTTP(S) origin without path or credentials");
                }
                return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
            }).toList();
            if (new HashSet<>(backends).size() != backends.size()) throw new IllegalArgumentException("Duplicate backend");
        }
    }
    public GatewayProperties {
        if (strategy == null || routes == null || routes.isEmpty() || healthIntervalMs < 1
                || healthTimeoutMs < 1 || requestTimeoutMs < 1 || circuitThreshold < 1 || circuitOpenMs < 1
                || rateCapacity < 1 || rateRefillPerSecond < 1 || authCapacity < 1 || cacheTtlMs < 1) {
            throw new IllegalArgumentException("Routing and positive timeout/limit settings are required");
        }
        routes = List.copyOf(routes);
        if (routes.stream().map(Route::id).distinct().count() != routes.size()
                || routes.stream().map(Route::prefix).distinct().count() != routes.size()) {
            throw new IllegalArgumentException("Route ids and prefixes must be unique");
        }
    }
}
