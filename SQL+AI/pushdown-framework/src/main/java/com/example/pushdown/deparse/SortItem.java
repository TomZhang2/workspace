package com.example.pushdown.deparse;

import com.example.pushdown.expression.ConnectorExpression;

public record SortItem(ConnectorExpression expression, SortDirection direction, NullOrdering nullOrdering) {
    public enum SortDirection { ASC, DESC }
    public enum NullOrdering { FIRST, LAST }
}
