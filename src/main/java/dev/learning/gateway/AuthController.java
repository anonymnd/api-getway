package dev.learning.gateway;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController implements ApplicationRunner {
    public record Credentials(String username, String password) {}
    private record User(String id, String username, String hash) {}
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final TokenBuckets limits;
    private final GatewayProperties properties;
    private final String dummyHash;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwords, JwtEncoder tokens,
            TokenBuckets limits, GatewayProperties properties) {
        this.jdbc=jdbc; this.passwords=passwords; this.tokens=tokens; this.limits=limits; this.properties=properties;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    @Override public void run(ApplicationArguments args) {
        if (properties.demoPassword() != null && !properties.demoPassword().isBlank()) {
            if (!valid(new Credentials("learner", properties.demoPassword()))) throw new IllegalArgumentException("Demo password must be 10-72 UTF-8 bytes");
            if (find("learner").isEmpty()) insert("learner", properties.demoPassword());
        }
    }
    @PostMapping(value="/auth/register", consumes="application/json")
    public ResponseEntity<?> register(@RequestBody Credentials credentials, HttpServletRequest request) {
        var limit = authLimit(request);
        if (!limit.allowed()) return ResponseEntity.status(429).header("Retry-After", String.valueOf(limit.retryAfterSeconds())).body(Map.of("error", "Too many authentication attempts"));
        if (!valid(credentials)) return ResponseEntity.badRequest().body(Map.of("error", "Username: 3-32 letters/digits/_/-. Password: 10-72 UTF-8 bytes."));
        String username = credentials.username().toLowerCase(Locale.ROOT);
        try { insert(username, credentials.password()); }
        catch (DuplicateKeyException e) { return ResponseEntity.status(409).body(Map.of("error", "Username already exists")); }
        return ResponseEntity.status(201).body(Map.of("username", username, "message", "Account created. Sign in to receive a JWT."));
    }
    @PostMapping(value="/auth/login", consumes="application/json")
    public ResponseEntity<?> login(@RequestBody Credentials credentials, HttpServletRequest request) {
        var limit = authLimit(request);
        if (!limit.allowed()) return ResponseEntity.status(429).header("Retry-After", String.valueOf(limit.retryAfterSeconds())).body(Map.of("error", "Too many authentication attempts"));
        if (!valid(credentials)) return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        var users = find(credentials.username().toLowerCase(Locale.ROOT));
        boolean matches = passwords.matches(credentials.password(), users.isEmpty() ? dummyHash : users.getFirst().hash());
        if (users.isEmpty() || !matches) return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        User user = users.getFirst();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(SecurityConfiguration.ISSUER).subject(user.id())
                .audience(List.of("gateway-api")).issuedAt(now).expiresAt(now.plusSeconds(1800)).claim("username", user.username()).build();
        String token = tokens.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("accessToken", token, "tokenType", "Bearer", "expiresIn", 1800, "username", user.username()));
    }
    private TokenBuckets.Decision authLimit(HttpServletRequest request) {
        // Never trust an unverified X-Forwarded-For header for brute-force limits.
        return limits.take("auth:" + request.getRemoteAddr(), properties.authCapacity(), properties.authCapacity() / 60.0);
    }
    private boolean valid(Credentials value) {
        return value != null && value.username() != null && value.username().matches("[A-Za-z0-9_-]{3,32}")
                && value.password() != null && value.password().length() >= 10
                && value.password().getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    private List<User> find(String username) {
        return jdbc.query("SELECT id, username, password_hash FROM app_users WHERE username = ?",
                (rs, row) -> new User(rs.getString(1), rs.getString(2), rs.getString(3)), username);
    }
    private void insert(String username, String password) {
        jdbc.update("INSERT INTO app_users(id, username, password_hash) VALUES (?, ?, ?)", UUID.randomUUID().toString(), username, passwords.encode(password));
    }
}
