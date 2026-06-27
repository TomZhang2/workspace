package com.example.pushdown.statistics;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.connector.mock.MockTableHandle;
import com.example.pushdown.type.Type;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatisticsCacheTest {
    @Test
    void cacheStoresAndRetrievesStats() {
        StatisticsCache cache = new StatisticsCache(Duration.ofMinutes(30));
        MockTableHandle table = new MockTableHandle("users");
        TableStatistics stats = TableStatistics.of(1000, Map.of());

        cache.put(table, stats);
        assertThat(cache.get(table)).contains(stats);
    }

    @Test
    void cacheReturnsEmptyForUnCachedTable() {
        StatisticsCache cache = new StatisticsCache(Duration.ofMinutes(30));
        MockTableHandle table = new MockTableHandle("users");
        assertThat(cache.get(table)).isEmpty();
    }

    @Test
    void cacheInvalidationRemovesEntry() {
        StatisticsCache cache = new StatisticsCache(Duration.ofMinutes(30));
        MockTableHandle table = new MockTableHandle("users");
        TableStatistics stats = TableStatistics.of(1000, Map.of());

        cache.put(table, stats);
        cache.invalidate(table);
        assertThat(cache.get(table)).isEmpty();
    }
}
