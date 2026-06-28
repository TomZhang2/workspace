package com.example.pushdown.security;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.Expressions;
import com.example.pushdown.expression.LogicalOperator;
import com.example.pushdown.expression.Logical;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.handle.TableHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * RLS-aware pushdown: appends engine RLS predicates to the pushdown predicate.
 * 
 * Final pushed predicate = user predicate ∧ RLS predicates.
 * If source has its own RLS, both apply (stricter wins).
 * Non-shippable RLS predicates naturally become residuals (safe — engine evaluates locally).
 */
public class RlsAwarePushdown {

    private final RlsService rlsService;

    public RlsAwarePushdown(RlsService rlsService) {
        this.rlsService = rlsService;
    }

    /**
     * Append RLS predicates to the user predicate.
     */
    public ConnectorExpression appendRlsPredicates(ConnectorExpression userPredicate,
                                                     ConnectorSession session,
                                                     TableHandle table) {
        List<ConnectorExpression> rlsPredicates = rlsService.getRlsPredicates(session.user(), table);
        
        if (rlsPredicates.isEmpty()) {
            return userPredicate;
        }
        
        List<ConnectorExpression> all = new ArrayList<>();
        all.add(userPredicate);
        all.addAll(rlsPredicates);
        return new Logical(LogicalOperator.AND, all);
    }

    /**
     * Service interface for RLS predicate retrieval.
     * Implementations query the engine's RLS policy for the user+table.
     */
    public interface RlsService {
        List<ConnectorExpression> getRlsPredicates(String user, TableHandle table);
    }
}
