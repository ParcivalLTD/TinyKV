package io.tinykv;

import io.tinykv.Record.IndexEntry;
import io.tinykv.Record.Key;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe lock-free in-memory index mapping keys to on-disk coordinates.
 */
public final class Index {

    private final ConcurrentHashMap<Key, IndexEntry> map = new ConcurrentHashMap<>();

    public Optional<IndexEntry> get(Key key) {
        Objects.requireNonNull(key);
        return Optional.ofNullable(map.get(key));
    }

    public void put(Key key, IndexEntry entry) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(entry);
        map.put(key, entry);
    }

    public boolean putIfNewer(Key key, IndexEntry entry) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(entry);
        AtomicBoolean updated = new AtomicBoolean(false);
        map.compute(key, (k, existing) -> {
            if (existing == null || entry.timestamp() >= existing.timestamp()) {
                updated.set(true);
                return entry;
            }
            return existing;
        });
        return updated.get();
    }

    public boolean remove(Key key, long timestamp) {
        Objects.requireNonNull(key);
        AtomicBoolean removed = new AtomicBoolean(false);
        map.computeIfPresent(key, (k, existing) -> {
            if (timestamp >= existing.timestamp()) {
                removed.set(true);
                return null;
            }
            return existing;
        });
        return removed.get();
    }

    public boolean remove(Key key) {
        Objects.requireNonNull(key);
        return map.remove(key) != null;
    }

    public boolean containsKey(Key key) {
        return map.containsKey(key);
    }

    public int size() {
        return map.size();
    }

    public Map<Key, IndexEntry> asMap() {
        return Collections.unmodifiableMap(map);
    }

    public void clear() {
        map.clear();
    }
}
