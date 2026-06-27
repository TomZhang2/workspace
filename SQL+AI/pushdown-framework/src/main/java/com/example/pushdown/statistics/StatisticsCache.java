package com.example.pushdown.statistics;

import com.example.pushdown.handle.TableHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class StatisticsCache {
    private final Duration ttl;
    private final ConcurrentHashMap<TableHandle, CacheEntry> cache = new ConcurrentHashMap<>();

    public StatisticsCache(Duration ttl) {
        this.ttl = ttl;
    }

    public Optional<TableStatistics> get(TableHandle table) {
        CacheEntry entry = cache.get(table);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiry())) {
            cache.remove(table);
            return Optional.empty();
        }
        return Optional.of(entry.stats());
    }

    public void put(TableHandle table, TableStatistics stats) {
        cache.put(table, new CacheEntry(stats, Instant.now().plus(ttl)));
    }

    public void invalidate(TableHandle table) {
        cache.remove(table);
    }

    private record CacheEntry(TableStatistics stats, Instant expiry) {}
}
