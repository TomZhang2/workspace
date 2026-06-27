package com.example.pushdown.shippability;

import com.example.pushdown.expression.FunctionSignature;
import com.example.pushdown.expression.FunctionVolatility;
import com.example.pushdown.type.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SourceFamilyTest {
    @Test
    void sourceFamiliesExist() {
        assertThat(SourceFamily.values())
            .contains(SourceFamily.MYSQL, SourceFamily.POSTGRESQL, SourceFamily.GENERIC);
    }

    @Test
    void mysqlCatalogContainsUpper() {
        BuiltinCatalog mysql = SourceFamily.MYSQL.builtinCatalog();
        FunctionSignature upper = new FunctionSignature("UPPER", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        assertThat(mysql.contains(upper)).isTrue();
    }

    @Test
    void mysqlCatalogDoesNotContainRandomFunction() {
        BuiltinCatalog mysql = SourceFamily.MYSQL.builtinCatalog();
        FunctionSignature custom = new FunctionSignature("my_custom_func", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        assertThat(mysql.contains(custom)).isFalse();
    }

    @Test
    void mysqlCatalogChecksSemanticallyCompatible() {
        BuiltinCatalog mysql = SourceFamily.MYSQL.builtinCatalog();
        FunctionSignature concat = new FunctionSignature("CONCAT", List.of(Type.VARCHAR, Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        assertThat(mysql.isSemanticallyCompatible(concat)).isTrue();
    }

    @Test
    void genericCatalogIsEmpty() {
        BuiltinCatalog generic = SourceFamily.GENERIC.builtinCatalog();
        FunctionSignature upper = new FunctionSignature("UPPER", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        assertThat(generic.contains(upper)).isFalse();
    }
}
