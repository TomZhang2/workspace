package com.example.pushdown.deparse;

import com.example.pushdown.expression.Call;
import com.example.pushdown.expression.Cast;
import com.example.pushdown.expression.Comparison;
import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.Constant;
import com.example.pushdown.expression.Domain;
import com.example.pushdown.expression.Expressions;
import com.example.pushdown.expression.Logical;
import com.example.pushdown.expression.LogicalOperator;
import com.example.pushdown.expression.Special;
import com.example.pushdown.expression.TupleDomain;
import com.example.pushdown.expression.Variable;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.ConjunctPushdown;
import com.example.pushdown.type.Type;
import java.util.ArrayList;
import java.util.List;

public class DefaultSqlDeparser implements SqlDeparser {

    @Override
    public DeparsedQuery deparseSelectStmt(PushedPlan plan, SqlDialect dialect) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        List<Integer> retrievedAttrs = new ArrayList<>();

        // SELECT
        sql.append("SELECT ");
        if (plan.projections().isEmpty()) {
            sql.append("*");
        } else {
            for (int i = 0; i < plan.projections().size(); i++) {
                if (i > 0) sql.append(", ");
                deparseExpr(sql, plan.projections().get(i), dialect, params);
                retrievedAttrs.add(i + 1);
            }
        }

        // FROM
        sql.append(" FROM ");
        sql.append(dialect.quoteIdentifier(plan.tableHandle().name()));

        // WHERE (only EXACT conjuncts that pushed a non-trivial predicate)
        List<ConnectorExpression> whereConjuncts = plan.conjunctResults().stream()
            .filter(cp -> cp.mode() == PushdownMode.EXACT)
            .map(cp -> cp.pushedExpression().orElse(Expressions.TRUE()))
            .filter(e -> !isTrueConstant(e))
            .toList();
        if (!whereConjuncts.isEmpty()) {
            sql.append(" WHERE ");
            for (int i = 0; i < whereConjuncts.size(); i++) {
                if (i > 0) sql.append(" AND ");
                sql.append("(");
                deparseExpr(sql, whereConjuncts.get(i), dialect, params);
                sql.append(")");
            }
        }

        // GROUP BY
        if (plan.groupingKeys().isPresent() && !plan.groupingKeys().get().isEmpty()) {
            sql.append(" GROUP BY ");
            List<ColumnHandle> keys = plan.groupingKeys().get();
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append(dialect.quoteIdentifier(keys.get(i).name()));
            }
        }

        // HAVING
        if (plan.pushedHaving().isPresent()) {
            sql.append(" HAVING ");
            sql.append("(");
            deparseExpr(sql, plan.pushedHaving().get(), dialect, params);
            sql.append(")");
        }

        // ORDER BY
        if (plan.sortItems().isPresent() && !plan.sortItems().get().isEmpty()) {
            sql.append(" ORDER BY ");
            List<SortItem> items = plan.sortItems().get();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sql.append(", ");
                SortItem item = items.get(i);
                deparseExpr(sql, item.expression(), dialect, params);
                sql.append(" ").append(item.direction().name());
                sql.append(" NULLS ").append(item.nullOrdering().name());
            }
        }

        // LIMIT
        if (plan.limit().isPresent()) {
            sql.append(" LIMIT ").append(plan.limit().get());
        }

        return DeparsedQuery.of(sql.toString(), retrievedAttrs, params, plan.fetchSize());
    }

    private static boolean isTrueConstant(ConnectorExpression e) {
        if (e instanceof Constant c) {
            return c.type() == Type.BOOLEAN && Boolean.TRUE.equals(c.value());
        }
        return false;
    }

    private void deparseExpr(StringBuilder sql, ConnectorExpression expr, SqlDialect dialect, List<Object> params) {
        if (expr instanceof Variable v) {
            sql.append(dialect.quoteIdentifier(v.column().name()));
        } else if (expr instanceof Constant c) {
            sql.append(dialect.formatLiteral(c.value(), c.type()));
        } else if (expr instanceof Call call) {
            sql.append(call.function().name()).append("(");
            for (int i = 0; i < call.args().size(); i++) {
                if (i > 0) sql.append(", ");
                deparseExpr(sql, call.args().get(i), dialect, params);
            }
            sql.append(")");
        } else if (expr instanceof Comparison cmp) {
            deparseExpr(sql, cmp.left(), dialect, params);
            sql.append(" ").append(cmp.op().symbol()).append(" ");
            deparseExpr(sql, cmp.right(), dialect, params);
        } else if (expr instanceof Logical log) {
            String sep = log.op() == LogicalOperator.AND ? " AND " : " OR ";
            for (int i = 0; i < log.terms().size(); i++) {
                if (i > 0) sql.append(sep);
                sql.append("(");
                deparseExpr(sql, log.terms().get(i), dialect, params);
                sql.append(")");
            }
        } else if (expr instanceof Cast cast) {
            sql.append("CAST(");
            deparseExpr(sql, cast.expr(), dialect, params);
            sql.append(" AS ").append(dialect.typeName(cast.targetType())).append(")");
        } else if (expr instanceof Special s) {
            deparseSpecial(sql, s, dialect, params);
        } else if (expr instanceof TupleDomain td) {
            // Deparse as conjunct of column range predicates
            boolean first = true;
            for (var entry : td.domains().entrySet()) {
                if (!first) sql.append(" AND ");
                first = false;
                sql.append(dialect.quoteIdentifier(entry.getKey().name()));
                Domain<?> d = entry.getValue();
                sql.append(" BETWEEN ").append(dialect.formatLiteral(d.min(), d.type()))
                   .append(" AND ").append(dialect.formatLiteral(d.max(), d.type()));
            }
        } else {
            sql.append("/* unknown expr */");
        }
    }

    private void deparseSpecial(StringBuilder sql, Special s, SqlDialect dialect, List<Object> params) {
        switch (s.kind()) {
            case IS_NULL -> {
                deparseExpr(sql, s.expr(), dialect, params);
                sql.append(" IS NULL");
            }
            case IS_NOT_NULL -> {
                deparseExpr(sql, s.expr(), dialect, params);
                sql.append(" IS NOT NULL");
            }
            case IN -> {
                deparseExpr(sql, s.expr(), dialect, params);
                sql.append(" IN (");
                appendArgs(sql, s, dialect, params);
                sql.append(")");
            }
            default -> {
                deparseExpr(sql, s.expr(), dialect, params);
                sql.append(" ").append(s.kind().name()).append(" (");
                appendArgs(sql, s, dialect, params);
                sql.append(")");
            }
        }
    }

    private void appendArgs(StringBuilder sql, Special s, SqlDialect dialect, List<Object> params) {
        for (int i = 0; i < s.args().size(); i++) {
            if (i > 0) sql.append(", ");
            deparseExpr(sql, s.args().get(i), dialect, params);
        }
    }
}
