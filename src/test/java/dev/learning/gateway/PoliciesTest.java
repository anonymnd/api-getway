package dev.learning.gateway;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class PoliciesTest {
    static GatewayProperties properties(GatewayProperties.Strategy strategy,int replicas) {
        List<URI> origins=replicas==1?List.of(URI.create("http://localhost:9201")):
                List.of(URI.create("http://localhost:9201"),URI.create("http://localhost:9202"));
        return new GatewayProperties("test","",strategy,1000,100,1000,3,8000,12,2,8,5000,
                List.of(new GatewayProperties.Route("catalog","/api/catalog",true,origins)));
    }
    RouteRegistry.Pool pool(GatewayProperties.Strategy strategy,int replicas,AtomicLong clock) {
        var p=properties(strategy,replicas);var pool=new RouteRegistry.Pool(p.routes().getFirst(),p,clock::get);
        pool.healthProbes().forEach(probe->pool.health(probe,true));return pool;
    }
    @Test void roundRobinAndHealthExclusionUseAtomicLeases() {
        var pool=pool(GatewayProperties.Strategy.ROUND_ROBIN,2,new AtomicLong());
        try(var a=pool.acquire(Set.of()).orElseThrow();var b=pool.acquire(Set.of()).orElseThrow()) {
            assertThat(a.origin().getPort()).isEqualTo(9201);assertThat(b.origin().getPort()).isEqualTo(9202);
            a.result(200,false);b.result(200,false);
        }
        pool.health(pool.healthProbes().getFirst(),false);
        try(var lease=pool.acquire(Set.of()).orElseThrow()){assertThat(lease.origin().getPort()).isEqualTo(9202);lease.result(200,false);}
        assertThat(pool.snapshot().backends()).allMatch(b->b.active()==0);
    }
    @Test void leastConnectionsAccountsForInFlightRequestsAndRetryExclusions() {
        var pool=pool(GatewayProperties.Strategy.LEAST_CONNECTIONS,2,new AtomicLong());
        try(var slow=pool.acquire(Set.of()).orElseThrow()) {
            for(int i=0;i<4;i++)try(var next=pool.acquire(Set.of()).orElseThrow()){assertThat(next.origin()).isNotEqualTo(slow.origin());next.result(200,false);}
            assertThat(pool.acquire(Set.of(URI.create("http://localhost:9201"),URI.create("http://localhost:9202")))).isEmpty();
            slow.result(200,false);
        }
    }
    @Test void circuitIgnoresLateSuccessAndAllowsOnlyOneHalfOpenTrial() {
        var clock=new AtomicLong();var pool=pool(GatewayProperties.Strategy.ROUND_ROBIN,1,clock);
        var lateSuccess=pool.acquire(Set.of()).orElseThrow();
        for(int i=0;i<3;i++)try(var failed=pool.acquire(Set.of()).orElseThrow()){failed.result(503,false);}
        lateSuccess.result(200,false);lateSuccess.close();
        pool.healthProbes().forEach(probe->pool.health(probe,true));
        assertThat(pool.snapshot().backends().getFirst().circuit()).isEqualTo("OPEN");
        assertThat(pool.acquire(Set.of())).isEmpty();
        clock.addAndGet(8_000_000_000L);
        var trial=pool.acquire(Set.of()).orElseThrow();
        assertThat(pool.snapshot().backends().getFirst().circuit()).isEqualTo("HALF_OPEN");
        assertThat(pool.acquire(Set.of())).isEmpty();
        trial.result(200,false);trial.close();trial.close();
        assertThat(pool.snapshot().backends().getFirst().circuit()).isEqualTo("CLOSED");
        assertThat(pool.snapshot().backends().getFirst().active()).isZero();
    }
    @Test void failedHalfOpenTrialReopensAndStaleHealthCannotResurrectTransportFailure() {
        var clock=new AtomicLong();var pool=pool(GatewayProperties.Strategy.ROUND_ROBIN,1,clock);
        for(int i=0;i<3;i++)try(var lease=pool.acquire(Set.of()).orElseThrow()){lease.result(503,false);}
        clock.addAndGet(8_000_000_000L);
        try(var trial=pool.acquire(Set.of()).orElseThrow()){trial.result(503,false);}
        assertThat(pool.acquire(Set.of())).isEmpty();
        var other=pool(GatewayProperties.Strategy.ROUND_ROBIN,1,new AtomicLong());
        var stale=other.healthProbes().getFirst();
        try(var failure=other.acquire(Set.of()).orElseThrow()){failure.result(502,true);}
        other.health(stale,true);
        assertThat(other.snapshot().backends().getFirst().healthy()).isFalse();
    }
    @Test void routeBoundariesAndConfigurationFailClosed() {
        var registry=new RouteRegistry(properties(GatewayProperties.Strategy.ROUND_ROBIN,1));
        assertThat(registry.match("/api/catalog/items")).isPresent();
        assertThat(registry.match("/api/catalogue/items")).isEmpty();
        assertThatThrownBy(()->new GatewayProperties.Route("bad","/api/bad",false,List.of(URI.create("http://user:secret@localhost/path"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void tokenBucketRefillsMonotonicallyAndIsAtomicUnderConcurrency() throws Exception {
        var clock=new AtomicLong();var buckets=new TokenBuckets(clock::get);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<100;i++)tasks.add(()->buckets.take("alice",12,2).allowed());
            int allowed=0;for(var future:executor.invokeAll(tasks))if(future.get())allowed++;
            assertThat(allowed).isEqualTo(12);
        }
        assertThat(buckets.take("alice",12,2).retryAfterSeconds()).isEqualTo(1);
        assertThat(buckets.take("bob",12,2).allowed()).isTrue();
        clock.addAndGet(500_000_000L);
        assertThat(buckets.take("alice",12,2).allowed()).isTrue();
        assertThat(buckets.take("alice",12,2).allowed()).isFalse();
    }
    @Test void limiterRejectsNewKeysAtCapacityAndReclaimsOnlyIdleBuckets() {
        var clock=new AtomicLong();var buckets=new TokenBuckets(clock::get);
        for(int i=0;i<1024;i++)assertThat(buckets.take("u"+i,1,1).allowed()).isTrue();
        assertThat(buckets.take("extra",1,1).allowed()).isFalse();
        clock.addAndGet(121_000_000_000L);
        assertThat(buckets.take("extra",1,1).allowed()).isTrue();
    }
    @Test void cacheIsolatesUsersExpiresAndFencesMutationRaces() {
        var clock=new AtomicLong();var cache=new ResponseCache(clock::get);
        var alice=new ResponseCache.Key("catalog","alice","/items");
        var bob=new ResponseCache.Key("catalog","bob","/items");
        var value=new ResponseCache.Value(new byte[]{1},new HttpHeaders(),"http://localhost:9201");
        cache.put(alice,value,5000,cache.revision("catalog"));
        assertThat(cache.get(alice)).isPresent();assertThat(cache.get(bob)).isEmpty();
        clock.addAndGet(5_000_000_000L);assertThat(cache.get(alice)).isEmpty();
        long revision=cache.revision("catalog");cache.invalidate("catalog");
        cache.put(alice,value,5000,revision);assertThat(cache.get(alice)).isEmpty();
        cache.put(alice,value,5000,cache.revision("catalog"));cache.invalidate("catalog");assertThat(cache.get(alice)).isEmpty();
    }
    @Test void cacheMemoryAndHistoryAreBounded() {
        var cache=new ResponseCache(()->0L);var value=new ResponseCache.Value(new byte[]{1},new HttpHeaders(),"http://localhost:9201");
        for(int i=0;i<140;i++)cache.put(new ResponseCache.Key("catalog","u","/"+i),value,5000,0);
        assertThat(cache.size()).isEqualTo(128);
        var large=new ResponseCache.Key("catalog","u","/large");cache.put(large,new ResponseCache.Value(new byte[65537],new HttpHeaders(),"test"),5000,0);
        assertThat(cache.get(large)).isEmpty();
    }
}
