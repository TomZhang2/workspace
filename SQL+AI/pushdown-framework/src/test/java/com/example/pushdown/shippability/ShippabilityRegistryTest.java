package com.example.pushdown.shippability;

import com.example.pushdown.expression.FunctionSignature;
import com.example.pushdown.expression.FunctionVolatility;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ShippabilityRegistryTest {
    @Test
    void upperIsShippableToMysql() {
        ShippabilityRegistry registry = new ShippabilityRegistry();
        FunctionSignature upper = new FunctionSignature("UPPER", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        assertThat(registry.isShippable(upper, "mysql-prod")).isTrue();
    }

    @Test
    void customFunctionNotShippableByDefault() {
        ShippabilityRegistry registry = new ShippabilityRegistry();
        FunctionSignature custom = new FunctionSignature("my_func", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        assertThat(registry.isShippable(custom, "mysql-prod")).isFalse();
    }

    @Test
    void customFunctionShippableViaExtensionWhitelist() {
        ShippabilityRegistry registry = new ShippabilityRegistry();
        FunctionSignature custom = new FunctionSignature("my_func", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        registry.registerExtension("mysql-prod", Set.of("my_func"));
        assertThat(registry.isShippable(custom, "mysql-prod")).isTrue();
    }

    @Test
    void genericSourceHasNoBuiltins() {
        ShippabilityRegistry registry = new ShippabilityRegistry();
        FunctionSignature upper = new FunctionSignature("UPPER", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        assertThat(registry.isShippable(upper, "unknown-source")).isFalse();
    }
}
