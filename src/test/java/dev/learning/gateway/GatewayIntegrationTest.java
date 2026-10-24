package dev.learning.gateway;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:gateway-tests;DB_CLOSE_DELAY=-1", "spring.datasource.password=test-only",
    "gateway.jwt-secret=0hihmYfHDc7V6CXGRUYp3QbhhFo4xspci1XTZjdPVIY=", "gateway.demo-password=",
    "gateway.auth-capacity=1000","gateway.health-interval-ms=600000","gateway.health-timeout-ms=100",
    "gateway.request-timeout-ms=250","gateway.cache-ttl-ms=150","gateway.circuit-open-ms=500"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GatewayIntegrationTest {
    static final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    static final AtomicInteger hits=new AtomicInteger(),mutations=new AtomicInteger(),retryCalls=new AtomicInteger();
    static final List<HttpServer> servers=List.of(backend(),backend(),backend(),backend());
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired Telemetry telemetry;
    @Autowired RouteRegistry routes;
    @Autowired HealthChecker checker;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    String token,username;
    @DynamicPropertySource static void origins(DynamicPropertyRegistry registry) {
        registry.add("gateway.routes[0].id",()->"catalog"); registry.add("gateway.routes[0].prefix",()->"/api/catalog"); registry.add("gateway.routes[0].cache",()->true);
        registry.add("gateway.routes[1].id",()->"orders"); registry.add("gateway.routes[1].prefix",()->"/api/orders"); registry.add("gateway.routes[1].cache",()->false);
        for(int i=0;i<4;i++){final int index=i;registry.add("gateway.routes["+(i/2)+"].backends["+(i%2)+"]",()->"http://127.0.0.1:"+servers.get(index).getAddress().getPort());}
    }
    static HttpServer backend() {
        try{
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
            server.createContext("/",exchange->{try{
                String path=exchange.getRequestURI().getPath();
                if(path.equals("/health")){exchange.sendResponseHeaders(200,-1);return;}
                int sequence=hits.incrementAndGet();int status=200;
                String body="port="+server.getAddress().getPort()+" seq="+sequence;
                switch(path){
                    case "/echo" -> body=exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)
                        +" user="+exchange.getRequestHeaders().getFirst("X-Authenticated-User")+" bearer="+exchange.getRequestHeaders().getFirst("Authorization")
                        +" forwarded="+exchange.getRequestHeaders().getFirst("X-Forwarded-For");
                    case "/cache","/cookie","/vary" -> {
                        exchange.getResponseHeaders().set("Cache-Control","public, max-age=5");
                        if(path.equals("/cookie"))exchange.getResponseHeaders().set("Set-Cookie","private=1");
                        if(path.equals("/vary"))exchange.getResponseHeaders().set("Vary","Accept-Language");
                    }
                    case "/fail" -> status=503;
                    case "/retry" -> {if(retryCalls.incrementAndGet()==1)status=503;}
                    case "/mutate" -> {mutations.incrementAndGet();exchange.close();return;}
                    case "/large" -> body="x".repeat(1048577);
                    case "/slow" -> Thread.sleep(1000);
                    case "/stall" -> {exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write('a');exchange.getResponseBody().flush();Thread.sleep(1000);exchange.getResponseBody().write('b');return;}
                    default -> { }
                }
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);boolean head=exchange.getRequestMethod().equals("HEAD");
                exchange.getResponseHeaders().set("Content-Type","text/plain");
                exchange.getResponseHeaders().set("Connection","X-Private");exchange.getResponseHeaders().set("X-Private","secret");
                exchange.sendResponseHeaders(status,head?-1:bytes.length);if(!head)exchange.getResponseBody().write(bytes);
            }catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});
            server.start();return server;
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @AfterAll static void stop(){servers.forEach(s->s.stop(0));workers.shutdownNow();}
    @BeforeEach void authenticate() throws Exception {
        username="user_"+UUID.randomUUID().toString().substring(0,12);
        assertThat(auth("register",username,"correct-password-123").statusCode()).isEqualTo(201);
        token=json.readTree(auth("login",username,"correct-password-123").body()).get("accessToken").asText();
        checker.check();
    }
    HttpResponse<String> auth(String action,String name,String password) throws Exception {
        return call("POST","/auth/"+action,null,json.writeValueAsString(Map.of("username",name,"password",password)),Map.of("Content-Type","application/json"));
    }
    HttpResponse<String> get(String path) throws Exception {return call("GET",path,token,null,Map.of());}
    HttpResponse<String> call(String method,String path,String bearer,String body,Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5));
        if(bearer!=null)builder.header("Authorization","Bearer "+bearer);headers.forEach(builder::header);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    String header(HttpResponse<?> response,String name){return response.headers().firstValue(name).orElse("");}
    String signed(String issuer,List<String> audience,Instant expiry){return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().issuer(issuer).subject("test-user").audience(audience).issuedAt(expiry.minusSeconds(3600)).expiresAt(expiry).build())).getTokenValue();}

    @Test void authenticationIsRequiredAndTokensValidateSignatureIssuerAudienceAndExpiry() throws Exception {
        assertThat(call("GET","/api/catalog/items",null,null,Map.of()).statusCode()).isEqualTo(401);
        assertThat(call("GET","/_gateway/status",null,null,Map.of()).statusCode()).isEqualTo(401);
        for(String bad:List.of("not-a-jwt",signed("wrong",List.of("gateway-api"),Instant.now().plusSeconds(600)),
                signed(SecurityConfiguration.ISSUER,List.of("wrong"),Instant.now().plusSeconds(600)),
                signed(SecurityConfiguration.ISSUER,List.of("gateway-api"),Instant.now().minusSeconds(120)))) {
            assertThat(call("GET","/api/catalog/items",bad,null,Map.of()).statusCode()).isEqualTo(401);
        }
        String[] parts=token.split("\\.");parts[2]=(parts[2].startsWith("A")?"B":"A")+parts[2].substring(1);
        assertThat(call("GET","/api/catalog/items",String.join(".",parts),null,Map.of()).statusCode()).isEqualTo(401);
        assertThat(get("/api/catalog/items").statusCode()).isEqualTo(200);
    }
    @Test void usersPersistWithHashesDuplicateRegistrationAndInvalidPasswordsAreRejected() throws Exception {
        String hash=jdbc.queryForObject("SELECT password_hash FROM app_users WHERE username=?",String.class,username);
        assertThat(hash).startsWith("$2a$").isNotEqualTo("correct-password-123");
        assertThat(auth("register",username,"correct-password-123").statusCode()).isEqualTo(409);
        assertThat(auth("login",username,"incorrect-password").statusCode()).isEqualTo(401);
        assertThat(auth("register","short","tiny").statusCode()).isEqualTo(400);
        assertThat(auth("register","longpass","x".repeat(73)).statusCode()).isEqualTo(400);
        assertThat(call("POST","/auth/login",null,"x".repeat(4097),Map.of("Content-Type","application/json")).statusCode()).isEqualTo(413);
    }
    @Test void routingPreservesRawQueryAndBodyAndRemovesForgedTrustHeaders() throws Exception {
        var response=call("POST","/api/catalog/echo?q=a%2Bb",token,"hello",Map.of("X-Authenticated-User","forged","X-Forwarded-For","forged"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("POST /echo?q=a%2Bb hello","bearer=null","forwarded=null").doesNotContain("user=forged");
        assertThat(header(response,"x-private")).isEmpty();
        assertThat(header(response,"x-request-id")).isNotEmpty();
        assertThat(header(response,"x-gateway-attempts")).isEqualTo("1");
        assertThat(get("/api/catalogue/items").statusCode()).isEqualTo(404);
        assertThat(header(get("/api/orders/items"),"x-gateway-backend")).contains(":"+servers.get(2).getAddress().getPort());
    }
    @Test void roundRobinUsesBothReplicas() throws Exception {
        var first=get("/api/orders/items");var second=get("/api/orders/items");
        assertThat(header(first,"x-gateway-backend")).isNotEqualTo(header(second,"x-gateway-backend"));
    }
    @Test void cacheHitAvoidsUpstreamAndIsolatesUsersAndExpires() throws Exception {
        var first=get("/api/catalog/cache");var hit=get("/api/catalog/cache");
        assertThat(header(first,"x-gateway-outcome")).isEqualTo("CACHE_MISS");
        assertThat(header(hit,"x-gateway-outcome")).isEqualTo("CACHE_HIT");
        assertThat(header(hit,"x-gateway-attempts")).isEqualTo("0");assertThat(hit.body()).isEqualTo(first.body());
        String other="other_"+UUID.randomUUID().toString().substring(0,8);auth("register",other,"correct-password-123");
        String otherToken=json.readTree(auth("login",other,"correct-password-123").body()).get("accessToken").asText();
        var isolated=call("GET","/api/catalog/cache",otherToken,null,Map.of());
        assertThat(header(isolated,"x-gateway-outcome")).isEqualTo("CACHE_MISS");assertThat(isolated.body()).isNotEqualTo(first.body());
        Thread.sleep(160);assertThat(header(get("/api/catalog/cache"),"x-gateway-outcome")).isEqualTo("CACHE_MISS");
    }
    @Test void cacheRespectsCookiesVaryClientBypassAndMutationInvalidation() throws Exception {
        for(String path:List.of("cookie","vary")) {
            assertThat(get("/api/catalog/"+path).body()).isNotEqualTo(get("/api/catalog/"+path).body());
        }
        get("/api/catalog/cache");
        assertThat(header(call("GET","/api/catalog/cache",token,null,Map.of("Cache-Control","no-cache")),"x-gateway-outcome")).isEqualTo("FORWARDED");
        assertThat(call("POST","/api/catalog/cache",token,"write",Map.of()).statusCode()).isEqualTo(200);
        assertThat(header(get("/api/catalog/cache"),"x-gateway-outcome")).isEqualTo("CACHE_MISS");
    }
    @Test void rateLimitAppliesBeforeCacheAndReturnsRetryAfter() throws Exception {
        int rejected=0,hits=0;
        for(int i=0;i<24;i++) {var response=get("/api/catalog/cache");if(response.statusCode()==429){rejected++;assertThat(header(response,"retry-after")).isNotBlank();}if(header(response,"x-gateway-outcome").equals("CACHE_HIT"))hits++;}
        assertThat(rejected).isGreaterThan(0);assertThat(hits).isGreaterThan(0);
        assertThat(telemetry.snapshot().rateLimited()).isEqualTo(rejected);
    }
    @Test void retriesUseADifferentBackendAndNeverRepeatAPost() throws Exception {
        retryCalls.set(0);var recovered=get("/api/orders/retry");
        assertThat(recovered.statusCode()).isEqualTo(200);assertThat(header(recovered,"x-gateway-attempts")).isEqualTo("2");
        assertThat(retryCalls.get()).isEqualTo(2);assertThat(telemetry.snapshot().retries()).isEqualTo(1);
        mutations.set(0);var mutation=call("POST","/api/orders/mutate",token,"pay",Map.of());
        assertThat(mutation.statusCode()).isEqualTo(502);assertThat(mutations.get()).isEqualTo(1);
        assertThat(header(mutation,"x-gateway-attempts")).isEqualTo("1");
    }
    @Test void circuitsOpenFastFailAndRecoverWithRealHalfOpenRequests() throws Exception {
        for(int i=0;i<3;i++)assertThat(get("/api/orders/fail").statusCode()).isEqualTo(503);
        var pool=routes.pools().get(1);
        assertThat(pool.snapshot().backends()).allMatch(b->b.circuit().equals("OPEN"));
        long before=pool.snapshot().backends().stream().mapToLong(RouteRegistry.BackendStatus::attempts).sum();
        var rejected=get("/api/orders/items");assertThat(rejected.statusCode()).isEqualTo(503);assertThat(header(rejected,"x-gateway-attempts")).isEqualTo("0");
        checker.check();assertThat(pool.snapshot().backends()).allMatch(b->b.circuit().equals("OPEN"));
        assertThat(pool.snapshot().backends().stream().mapToLong(RouteRegistry.BackendStatus::attempts).sum()).isEqualTo(before);
        Thread.sleep(520);assertThat(get("/api/orders/items").statusCode()).isEqualTo(200);assertThat(get("/api/orders/items").statusCode()).isEqualTo(200);
        assertThat(pool.snapshot().backends()).allMatch(b->b.circuit().equals("CLOSED"));
    }
    @Test void wholeBodyTimeoutAndBodyLimitsAreEnforcedAndCountersReleased() throws Exception {
        assertThat(get("/api/orders/stall").statusCode()).isEqualTo(504);
        assertThat(routes.snapshot()).flatExtracting(RouteRegistry.PoolStatus::backends).allMatch(b->b.active()==0);
        assertThat(call("POST","/api/catalog/items",token,"x".repeat(1048577),Map.of()).statusCode()).isEqualTo(413);
        assertThat(get("/api/catalog/large").statusCode()).isEqualTo(502);
    }
    @Test void dashboardIsPublicButTelemetryProtectedAndPollingDoesNotCountAsApiTraffic() throws Exception {
        long before=telemetry.snapshot().requests();
        assertThat(call("GET","/",null,null,Map.of()).body()).contains("Meet your gateway");
        assertThat(call("GET","/ui/app.js",null,null,Map.of()).statusCode()).isEqualTo(200);
        assertThat(get("/_gateway/status").body()).contains("telemetry","routes","cacheEntries");
        assertThat(telemetry.snapshot().requests()).isEqualTo(before);
    }
}
