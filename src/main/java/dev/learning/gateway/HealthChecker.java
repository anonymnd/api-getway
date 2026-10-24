package dev.learning.gateway;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HealthChecker {
    private final RouteRegistry registry;
    private final GatewayProperties properties;
    public HealthChecker(RouteRegistry registry, GatewayProperties properties) { this.registry=registry; this.properties=properties; }
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString="${gateway.health-interval-ms}",initialDelayString="${gateway.health-interval-ms}")
    public synchronized void check() {
        for (var pool : registry.pools()) for (var probe : pool.healthProbes()) {
            boolean healthy=false;
            HttpURLConnection connection=null;
            try {
                connection=(HttpURLConnection)URI.create(probe.backend().origin+"/health").toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(properties.healthTimeoutMs()); connection.setReadTimeout(properties.healthTimeoutMs());
                int status=connection.getResponseCode(); healthy=status>=200 && status<300;
            } catch (IOException ignored) { }
            finally { if(connection!=null) connection.disconnect(); }
            pool.health(probe,healthy);
        }
    }
}
