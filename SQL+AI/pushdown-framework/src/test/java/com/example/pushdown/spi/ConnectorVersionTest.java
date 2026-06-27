package com.example.pushdown.spi;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConnectorVersionTest {
    @Test
    void v2VersionExists() {
        assertThat(ConnectorVersion.V2.major()).isEqualTo(2);
        assertThat(ConnectorVersion.V2.minor()).isEqualTo(1);
    }

    @Test
    void capabilitiesIncludeFilterPushdown() {
        assertThat(ConnectorCapability.values())
            .contains(ConnectorCapability.FILTER_PUSHDOWN);
    }
}
