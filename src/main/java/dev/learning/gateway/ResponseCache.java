package dev.learning.gateway;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** User-isolated LRU with bounded memory and generation-fenced mutation invalidation. */
@Component
public class ResponseCache {
    public record Key(String route, String user, String pathAndQuery) {}
    public record Value(byte[] body, HttpHeaders headers, String backend) {}
    private record Entry(Value value,long expires) {}
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final Map<String,Long> revisions=new HashMap<>();
    private final LongSupplier clock;
    public ResponseCache() { this(System::nanoTime); }
    ResponseCache(LongSupplier clock) { this.clock=clock; }
    public synchronized Optional<Value> get(Key key) {
        Entry entry=entries.get(key);
        if(entry==null) return Optional.empty();
        if(clock.getAsLong()>=entry.expires) { entries.remove(key); return Optional.empty(); }
        return Optional.of(entry.value);
    }
    public synchronized long revision(String route) { return revisions.getOrDefault(route,0L); }
    public synchronized void put(Key key,Value value,long ttlMs,long expectedRevision) {
        if(revision(key.route)!=expectedRevision || value.body.length>65536 || ttlMs<=0) return;
        HttpHeaders headers=new HttpHeaders(); headers.putAll(value.headers);
        entries.put(key,new Entry(new Value(value.body.clone(),HttpHeaders.readOnlyHttpHeaders(headers),value.backend),clock.getAsLong()+ttlMs*1_000_000));
        while(entries.size()>128) entries.remove(entries.keySet().iterator().next());
    }
    public synchronized void invalidate(String route) {
        revisions.merge(route,1L,Long::sum); entries.keySet().removeIf(key -> key.route.equals(route));
    }
    public synchronized int size() { entries.entrySet().removeIf(e -> clock.getAsLong()>=e.getValue().expires); return entries.size(); }
}
