package dev.learning.gateway;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class GatewayController implements AutoCloseable {
    private static final Set<String> HOP=Set.of("connection","keep-alive","proxy-authenticate","proxy-authorization",
            "te","trailer","transfer-encoding","upgrade","host","content-length","expect");
    private static final Set<Integer> RETRYABLE=Set.of(502,503,504);
    private static final int BODY_LIMIT=1048576;
    private final RouteRegistry routes;
    private final GatewayProperties properties;
    private final TokenBuckets limits;
    private final ResponseCache cache;
    private final Telemetry telemetry;
    private final HttpClient client;
    private record Result(int status,byte[] body,HttpHeaders headers,String backend) {}

    public GatewayController(RouteRegistry routes,GatewayProperties properties,TokenBuckets limits,ResponseCache cache,Telemetry telemetry) {
        this.routes=routes; this.properties=properties; this.limits=limits; this.cache=cache; this.telemetry=telemetry;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.requestTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @RequestMapping(value={"/api","/api/**"},method={RequestMethod.GET,RequestMethod.HEAD,RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE,RequestMethod.OPTIONS})
    public ResponseEntity<?> proxy(HttpServletRequest incoming,@AuthenticationPrincipal Jwt user) throws IOException {
        var match=routes.match(incoming.getRequestURI());
        if(match.isEmpty()) return error(404,"No matching service route","NO_ROUTE",0);
        var pool=match.get(); var route=pool.route;
        incoming.setAttribute("service",route.id());
        var limit=limits.take("api:"+user.getSubject()+":"+route.id(),properties.rateCapacity(),properties.rateRefillPerSecond());
        if(!limit.allowed()) return ResponseEntity.status(429).header("Retry-After",String.valueOf(limit.retryAfterSeconds()))
                .header("X-RateLimit-Remaining","0").header("X-Gateway-Outcome","RATE_LIMITED")
                .body(Map.of("error","User rate limit exceeded"));
        if(incoming.getContentLengthLong()>BODY_LIMIT) return error(413,"Request body exceeds 1 MiB","TOO_LARGE",0);
        byte[] body=incoming.getInputStream().readNBytes(BODY_LIMIT+1);
        if(body.length>BODY_LIMIT) return error(413,"Request body exceeds 1 MiB","TOO_LARGE",0);
        String rawPath=incoming.getRequestURI().substring(route.prefix().length());
        if(rawPath.isEmpty()) rawPath="/";
        String targetPath=rawPath+(incoming.getQueryString()==null ? "" : "?"+incoming.getQueryString());
        boolean cacheable=route.cache() && incoming.getMethod().equals("GET") && body.length==0
                && incoming.getHeader("Range")==null && incoming.getHeader("Cookie")==null
                && incoming.getHeader("If-None-Match")==null && incoming.getHeader("If-Modified-Since")==null
                && !String.valueOf(incoming.getHeader("Cache-Control")).toLowerCase(Locale.ROOT).matches(".*(no-cache|no-store).*");
        var key=new ResponseCache.Key(route.id(),user.getSubject(),targetPath);
        long revision=cache.revision(route.id());
        if(cacheable) {
            var cached=cache.get(key);
            if(cached.isPresent()) {
                telemetry.hit(); var value=cached.get();
                return response(new Result(200,value.body(),value.headers(),value.backend()),"CACHE_HIT",0,limit.remaining());
            }
            telemetry.miss();
        }
        boolean safe=incoming.getMethod().equals("GET") || incoming.getMethod().equals("HEAD");
        int attempts=0;
        Set<URI> tried=new HashSet<>();
        Result result=null;
        while(attempts<(safe ? 2 : 1)) {
            var selected=pool.acquire(tried);
            if(selected.isEmpty()) break;
            if(attempts>0) {
                telemetry.retry();
                try { Thread.sleep(50); } catch(InterruptedException e) {
                    selected.get().close(); Thread.currentThread().interrupt(); return error(503,"Gateway interrupted","INTERRUPTED",attempts);
                }
            }
            attempts++;
            try(var lease=selected.get()) {
                tried.add(lease.origin());
                result=forward(incoming,user,body,URI.create(lease.origin()+targetPath));
                boolean transportFailure=result.headers.containsKey("X-Internal-Transport-Failure");
                result.headers.remove("X-Internal-Transport-Failure");
                lease.result(result.status,transportFailure);
            }
            if(!RETRYABLE.contains(result.status)) break;
        }
        if(result==null) return error(503,"No ready backend: health check failed or circuit is open","NO_BACKEND",0);
        if(!safe && result.status>=200 && result.status<300) cache.invalidate(route.id());
        if(cacheable && result.status==200) {
            long ttl=cacheTtl(result.headers);
            if(ttl>0) cache.put(key,new ResponseCache.Value(result.body,result.headers,result.backend),ttl,revision);
        }
        return response(result,cacheable ? "CACHE_MISS" : "FORWARDED",attempts,limit.remaining());
    }
    private Result forward(HttpServletRequest incoming,Jwt user,byte[] body,URI target) {
        var builder=HttpRequest.newBuilder(target).timeout(Duration.ofMillis(properties.requestTimeoutMs()))
                .method(incoming.getMethod(),body.length==0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        Set<String> excluded=excluded(Collections.list(incoming.getHeaders("Connection")));
        for(String name:Collections.list(incoming.getHeaderNames())) {
            String lower=name.toLowerCase(Locale.ROOT);
            if(!excluded.contains(lower) && !lower.equals("authorization") && !lower.equals("forwarded")
                    && !lower.startsWith("x-forwarded-") && !lower.startsWith("x-authenticated-")
                    && !lower.startsWith("x-gateway-") && !lower.equals("x-request-id")) {
                Collections.list(incoming.getHeaders(name)).forEach(value -> builder.header(name,value));
            }
        }
        builder.header("X-Authenticated-User",user.getSubject());
        builder.header("X-Request-Id",String.valueOf(incoming.getAttribute("requestId")));
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            pending=client.sendAsync(builder.build(),info -> new LimitedBodySubscriber(BODY_LIMIT));
            var upstream=pending.get(properties.requestTimeoutMs(),TimeUnit.MILLISECONDS);
            HttpHeaders headers=new HttpHeaders();
            Set<String> skipped=excluded(upstream.headers().allValues("connection"));
            upstream.headers().map().forEach((name,values)-> {
                if(!skipped.contains(name.toLowerCase(Locale.ROOT)) && !name.toLowerCase(Locale.ROOT).startsWith("x-gateway-")
                        && !name.equalsIgnoreCase("x-internal-transport-failure") && !name.equalsIgnoreCase("x-request-id")) headers.put(name,values);
            });
            return new Result(upstream.statusCode(),upstream.body(),headers,target.getScheme()+"://"+target.getRawAuthority());
        } catch(TimeoutException e) { return failure(504,false,target); }
        catch(ExecutionException e) { return failure(e.getCause() instanceof HttpTimeoutException ? 504 : 502,true,target); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); return failure(503,false,target); }
        finally { if(pending!=null && !pending.isDone()) pending.cancel(true); }
    }
    private Result failure(int status,boolean transportFailure,URI target) {
        var headers=new HttpHeaders(); headers.set("Content-Type","application/json");
        if(transportFailure) headers.set("X-Internal-Transport-Failure","true");
        return new Result(status,("{\"error\":\"Upstream request failed\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),headers,target.getScheme()+"://"+target.getRawAuthority());
    }
    private ResponseEntity<byte[]> response(Result result,String outcome,int attempts,int remaining) {
        HttpHeaders headers=new HttpHeaders(); headers.putAll(result.headers);
        headers.set("X-Gateway-Outcome",outcome); headers.set("X-Gateway-Attempts",String.valueOf(attempts));
        headers.set("X-Gateway-Backend",result.backend); headers.set("X-RateLimit-Remaining",String.valueOf(remaining));
        // Gateway caching is internal. Never let a browser/shared cache bypass auth or rate limiting.
        headers.set("Cache-Control","no-store");
        return new ResponseEntity<>(result.body,headers,result.status);
    }
    private long cacheTtl(HttpHeaders headers) {
        String control=String.valueOf(headers.getFirst("Cache-Control")).toLowerCase(Locale.ROOT);
        if(headers.containsKey("Set-Cookie") || headers.containsKey("Vary") || !control.contains("public")
                || control.contains("no-store") || control.contains("no-cache") || control.contains("private")) return 0;
        var matcher=java.util.regex.Pattern.compile("(?:^|[,\\s])max-age=(\\d+)").matcher(control);
        if(!matcher.find()) return 0;
        try { return Math.min(properties.cacheTtlMs(),Math.multiplyExact(Long.parseLong(matcher.group(1)),1000)); }
        catch(ArithmeticException | NumberFormatException e) { return 0; }
    }
    private static Set<String> excluded(List<String> values) {
        Set<String> result=new HashSet<>(HOP);
        values.forEach(value->{ for(String token:value.split(",")) result.add(token.trim().toLowerCase(Locale.ROOT)); });
        return result;
    }
    private ResponseEntity<?> error(int status,String message,String outcome,int attempts) {
        return ResponseEntity.status(status).header("X-Gateway-Outcome",outcome).header("X-Gateway-Attempts",String.valueOf(attempts))
                .header("Cache-Control","no-store").body(Map.of("error",message));
    }
    @jakarta.annotation.PreDestroy
    @Override public void close() { client.close(); }
}
