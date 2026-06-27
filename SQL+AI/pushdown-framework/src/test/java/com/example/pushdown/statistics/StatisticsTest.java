package com.example.pushdown.statistics;

import com.example.pushdown.connector.mock.MockColumnHandle;
import com.example.pushdown.type.Type;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatisticsTest {
    @Test
    void tableStatisticsHoldsRowCountAndColumnStats() {
        // of(distinctValuesCount, nullCount, min, max, type)
        ColumnStatistics ageStats = ColumnStatistics.of(100, 50, 0, 65, Type.INTEGER);
        TableStatistics stats = TableStatistics.of(10000, Map.of(new MockColumnHandle("age"), ageStats));

        assertThat(stats.rowCount()).isEqualTo(10000);
        assertThat(stats.columnStats()).hasSize(1);
        assertThat(stats.columnStats().get(new MockColumnHandle("age")).distinctValuesCount()).isEqualTo(100);
    }

    @Test
    void columnStatisticsWithMinMax() {
        // of(distinctValuesCount, nullCount, min, max, type)
        ColumnStatistics stats = ColumnStatistics.of(100, 5, 0, 65, Type.INTEGER);
        assertThat(stats.nullCount()).isEqualTo(5);
        assertThat(stats.minValue()).contains(0);
        assertThat(stats.maxValue()).contains(65);
        assertThat(stats.type()).isEqualTo(Type.INTEGER);
    }

    @Test
    void emptyColumnStatistics() {
        ColumnStatistics stats = ColumnStatistics.empty(Type.UNKNOWN);
        assertThat(stats.distinctValuesCount()).isEqualTo(0);
        assertThat(stats.minValue()).isEmpty();
        assertThat(stats.maxValue()).isEmpty();
    }
}
