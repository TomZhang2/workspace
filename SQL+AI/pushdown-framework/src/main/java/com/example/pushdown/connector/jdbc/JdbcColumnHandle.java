package com.example.pushdown.connector.jdbc;

import com.example.pushdown.handle.ColumnHandle;

public record JdbcColumnHandle(String name) implements ColumnHandle {}
