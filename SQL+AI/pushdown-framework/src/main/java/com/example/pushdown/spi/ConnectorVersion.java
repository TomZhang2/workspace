package com.example.pushdown.spi;

public record ConnectorVersion(int major, int minor) {
    public static final ConnectorVersion V2 = new ConnectorVersion(2, 1);
}
