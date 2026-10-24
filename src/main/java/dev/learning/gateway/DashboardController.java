package dev.learning.gateway;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class DashboardController {
    private final RouteRegistry routes;
    private final Telemetry telemetry;
    private final ResponseCache cache;
    private final GatewayProperties properties;
    public DashboardController(RouteRegistry routes,Telemetry telemetry,ResponseCache cache,GatewayProperties properties) {
        this.routes=routes; this.telemetry=telemetry; this.cache=cache; this.properties=properties;
    }
    @GetMapping("/health") public Map<String,String> health() { return Map.of("status","UP"); }
    @GetMapping("/_gateway/status") public ResponseEntity<?> status(@AuthenticationPrincipal Jwt user) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("telemetry",telemetry.snapshot(),
                "routes",routes.snapshot(),"cacheEntries",cache.size(),"username",user.getClaimAsString("username"),
                "policy",Map.of("rateCapacity",properties.rateCapacity(),"rateRefill",properties.rateRefillPerSecond(),
                        "maxAttempts",2,"circuitThreshold",properties.circuitThreshold(),"circuitOpenMs",properties.circuitOpenMs(),"cacheTtlMs",properties.cacheTtlMs())));
    }
    @GetMapping({"/","/ui/app.js","/ui/styles.css"}) public ResponseEntity<Resource> dashboard(HttpServletRequest request) {
        String file=request.getRequestURI().endsWith(".js") ? "app.js" : request.getRequestURI().endsWith(".css") ? "styles.css" : "index.html";
        String type=file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css" : "text/html";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type+";charset=UTF-8"))
                .header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
                .body(new ClassPathResource("dashboard/"+file));
    }
}
