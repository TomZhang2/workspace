package com.example.pushdown.connector.jdbc;

import com.example.pushdown.handle.TableHandle;

public record JdbcTableHandle(String name) implements TableHandle {}
