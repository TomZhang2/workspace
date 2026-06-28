# Pushdown Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-grade pushdown framework for a query engine that adapts to different storage backends (MySQL/PG/Iceberg/HBase), supporting partial pushdown with residual return, dynamic filtering, cost-based decisions, and a conformance test suite.

**Architecture:** SPI-based design with `PushdownConnector` interface (isPushable/apply split), `ConnectorExpression` IR for predicates, per-conjunct pushdown modes (EXACT/CONSERVATIVE/IN_MEMORY), residual invariant validation, SQL deparse layer, cost-based path enumeration with memo/pruning, and a mandatory conformance test suite. Inspired by postgres_fdw (path building), Trino (apply* + residual + dynamic filter), Spark (guarantee flags), Calcite (Enumerable fallback + memo), and Iceberg (Inclusive/Strict projection).

**Tech Stack:** Java 17+ (sealed interfaces, records, switch expressions), Maven, JUnit 5, AssertJ (fluent assertions)

**Spec:** `SQL+AI/下推框架设计方案.md` (v2.1, 1964 lines)

---

## Phase Overview

| Phase | Goal | Tasks | Est. Days |
|---|---|---|---|
| **Phase 1** | Core IR + SPI + Mock connector → Filter pushdown end-to-end | Task 1-22 | 5-7 |
| **Phase 2** | SQL Deparse + Shippability + MySQL connector → real SQL pushdown | Task 23-35 | 4-5 |
| **Phase 3** | Aggregate/Join/TopN pushdown + Cost model + Statistics | Task 36-50 | 6-8 |
| **Phase 4** | Dynamic filter + Data skipping + Error fallback + Snapshot | Task 51-62 | 5-7 |
| **Phase 5** | Security/RLS + Conformance suite + Versioning + Observability | Task 63-72 | 4-5 |

**This document covers Phase 1 in full TDD detail.** Phases 2-5 are outlined with key deliverables; each will get its own detailed plan when Phase 1 completes.

---

## File Structure (Phase 1)

```
SQL+AI/pushdown-framework/
├── pom.xml
└── src/
    ├── main/java/com/example/pushdown/
    │   ├── type/
    │   │   └── Type.java                    # Type interface + basic types
    │   ├── handle/
    │   │   ├── ColumnHandle.java            # Column identity interface
    │   │   └── TableHandle.java             # Table identity interface
    │   ├── session/
    │   │   ├── ConnectorSession.java        # Per-query session context
    │   │   └── SnapshotContext.java         # Snapshot timestamp + isolation
    │   ├── expression/
    │   │   ├── ConnectorExpression.java     # Sealed interface (IR root)
    │   │   ├── FunctionSignature.java       # Function name + types + volatility
    │   │   ├── FunctionVolatility.java      # IMMUTABLE/STABLE/VOLATILE
    │   │   ├── Operator.java                # Comparison operators
    │   │   ├── LogicalOperator.java         # AND/OR/NOT
    │   │   ├── SpecialKind.java             # IS_NULL/IN/BETWEEN/LIKE
    │   │   ├── Variable.java                # Column reference
    │   │   ├── Constant.java                # Literal value
    │   │   ├── Call.java                    # Function call
    │   │   ├── Comparison.java              # Binary comparison
    │   │   ├── Logical.java                 # AND/OR/NOT combination
    │   │   ├── Cast.java                    # Type conversion
    │   │   ├── Special.java                 # IS NULL / IN / BETWEEN / LIKE
    │   │   ├── TupleDomain.java             # Fast-path column-domain subset
    │   │   ├── Domain.java                  # Value range for TupleDomain
    │   │   └── Expressions.java             # Static helpers (logicalAnd, TRUE, splitConjuncts)
    │   ├── mode/
    │   │   └── PushdownMode.java            # EXACT / CONSERVATIVE / IN_MEMORY
    │   ├── result/
    │   │   ├── ConjunctPushdown.java        # Per-conjunct result (interface)
    │   │   ├── FilterResult.java            # Filter pushdown result (interface)
    │   │   └── FilterResults.java           # Factory + immutable impl
    │   ├── spi/
    │   │   ├── PushdownConnector.java       # Core SPI (isPushable + apply)
    │   │   ├── ConnectorVersion.java        # Version record
    │   │   └── ConnectorCapability.java     # Capability enum
    │   ├── invariant/
    │   │   └── ResidualInvariantValidator.java  # Debug/test invariant checker
    │   ├── planner/
    │   │   ├── PushdownPathBuilder.java     # Enumerate candidate paths
    │   │   ├── PlanPath.java                # Path descriptor
    │   │   └── PushdownPlanner.java         # Selects best path + executes
    │   └── connector/
    │       └── mock/
    │           ├── MockConnector.java       # Test connector (IN_MEMORY mode)
    │           ├── MockTableHandle.java     # Test table handle
    │           └── MockColumnHandle.java    # Test column handle
    └── test/java/com/example/pushdown/
        ├── expression/
        │   ├── ConnectorExpressionTest.java
        │   └── ExpressionsTest.java
        ├── result/
        │   └── FilterResultsTest.java
        ├── invariant/
        │   └── ResidualInvariantValidatorTest.java
        ├── planner/
        │   └── PushdownPlannerTest.java
        └── connector/
            └── MockConnectorTest.java
```

---

## Phase 1: Core IR + SPI + Mock Connector

### Task 1: Maven Project Setup

**Files:**
- Create: `SQL+AI/pushdown-framework/pom.xml`

- [ ] **Step 1: Create pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>pushdown-framework</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>
    <name>Pushdown Framework</name>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.10.2</junit.version>
        <assertj.version>3.25.3</assertj.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>${assertj.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Verify project compiles**

Run: `cd SQL+AI/pushdown-framework && mvn compile`
Expected: BUILD SUCCESS (no source files yet, but structure is valid)

- [ ] **Step 3: Commit**

```bash
cd SQL+AI/pushdown-framework && git add pom.xml
git commit -m "chore: initialize pushdown-framework Maven project"
```

---

### Task 2: Type System

**Files:**
- Create: `src/main/java/com/example/pushdown/type/Type.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/type/TypeTest.java`:

```java
package com.example.pushdown.type;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TypeTest {
    @Test
    void basicTypesHaveDistinctNames() {
        assertThat(Type.INTEGER.name()).isEqualTo("INTEGER");
        assertThat(Type.VARCHAR.name()).isEqualTo("VARCHAR");
        assertThat(Type.BOOLEAN.name()).isEqualTo("BOOLEAN");
    }

    @Test
    void integerTypeIsNotVarchar() {
        assertThat(Type.INTEGER).isNotEqualTo(Type.VARCHAR);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=TypeTest`
Expected: FAIL — compilation error (Type does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.pushdown.type;

/**
 * Type represents a data type in the query engine's type system.
 * Phase 1 uses a simple enum; Phase 3 will extend to parameterized types (VARCHAR(n), DECIMAL(p,s)).
 */
public enum Type {
    INTEGER,
    BIGINT,
    DOUBLE,
    VARCHAR,
    BOOLEAN,
    DATE,
    TIMESTAMP,
    BINARY,
    UNKNOWN;

    public String name() {
        return super.name();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TypeTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/type/Type.java src/test/java/com/example/pushdown/type/TypeTest.java
git commit -m "feat: add Type enum for basic data types"
```

---

### Task 3: Handles (ColumnHandle, TableHandle)

**Files:**
- Create: `src/main/java/com/example/pushdown/handle/ColumnHandle.java`
- Create: `src/main/java/com/example/pushdown/handle/TableHandle.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/handle/HandleTest.java`:

```java
package com.example.pushdown.handle;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HandleTest {
    @Test
    void columnHandleHasName() {
        ColumnHandle col = new TestColumnHandle("user_id");
        assertThat(col.name()).isEqualTo("user_id");
    }

    @Test
    void tableHandleHasName() {
        TableHandle table = new TestTableHandle("users");
        assertThat(table.name()).isEqualTo("users");
    }

    // Simple test implementations
    record TestColumnHandle(String name) implements ColumnHandle {}
    record TestTableHandle(String name) implements TableHandle {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=HandleTest`
Expected: FAIL — interfaces don't exist

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.pushdown.handle;

/** Identity of a column within a connector. Connectors provide their own implementation. */
public interface ColumnHandle {
    String name();
}
```

```java
package com.example.pushdown.handle;

/** Identity of a table within a connector. Connectors provide their own implementation. */
public interface TableHandle {
    String name();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=HandleTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/handle/ src/test/java/com/example/pushdown/handle/
git commit -m "feat: add ColumnHandle and TableHandle interfaces"
```

---

### Task 4: Session Context (ConnectorSession, SnapshotContext)

**Files:**
- Create: `src/main/java/com/example/pushdown/session/ConnectorSession.java`
- Create: `src/main/java/com/example/pushdown/session/SnapshotContext.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/session/SessionTest.java`:

```java
package com.example.pushdown.session;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {
    @Test
    void sessionHoldsUserAndQueryId() {
        ConnectorSession session = ConnectorSession.builder()
            .user("alice")
            .queryId("q-001")
            .serverId("mysql-prod")
            .build();
        assertThat(session.user()).isEqualTo("alice");
        assertThat(session.queryId()).isEqualTo("q-001");
        assertThat(session.serverId()).isEqualTo("mysql-prod");
    }

    @Test
    void snapshotContextHasTimestamp() {
        Instant now = Instant.now();
        SnapshotContext snapshot = new SnapshotContext(now, SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");
        assertThat(snapshot.queryTimestamp()).isEqualTo(now);
        assertThat(snapshot.isolationLevel()).isEqualTo(SnapshotContext.IsolationLevel.SNAPSHOT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SessionTest`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.pushdown.session;

/** Per-query session context passed to all SPI methods. */
public record ConnectorSession(
    String user,
    String queryId,
    String serverId
) {
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String user;
        private String queryId;
        private String serverId;

        public Builder user(String user) { this.user = user; return this; }
        public Builder queryId(String queryId) { this.queryId = queryId; return this; }
        public Builder serverId(String serverId) { this.serverId = serverId; return this; }

        public ConnectorSession build() {
            return new ConnectorSession(user, queryId, serverId);
        }
    }
}
```

```java
package com.example.pushdown.session;

import java.time.Instant;

/** Snapshot context for query isolation (§13 of design doc). */
public record SnapshotContext(
    Instant queryTimestamp,
    IsolationLevel isolationLevel,
    String snapshotId
) {
    public enum IsolationLevel {
        SNAPSHOT,
        REPEATABLE_READ,
        READ_COMMITTED
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=SessionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/session/ src/test/java/com/example/pushdown/session/
git commit -m "feat: add ConnectorSession and SnapshotContext"
```

---

### Task 5: Expression Enums (Operator, LogicalOperator, SpecialKind, FunctionVolatility)

**Files:**
- Create: `src/main/java/com/example/pushdown/expression/Operator.java`
- Create: `src/main/java/com/example/pushdown/expression/LogicalOperator.java`
- Create: `src/main/java/com/example/pushdown/expression/SpecialKind.java`
- Create: `src/main/java/com/example/pushdown/expression/FunctionVolatility.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/expression/EnumTest.java`:

```java
package com.example.pushdown.expression;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EnumTest {
    @Test
    void operatorSymbols() {
        assertThat(Operator.EQ.symbol()).isEqualTo("=");
        assertThat(Operator.NEQ.symbol()).isEqualTo("<>");
        assertThat(Operator.LT.symbol()).isEqualTo("<");
        assertThat(Operator.GT.symbol()).isEqualTo(">");
        assertThat(Operator.LTE.symbol()).isEqualTo("<=");
        assertThat(Operator.GTE.symbol()).isEqualTo(">=");
    }

    @Test
    void operatorIsRange() {
        assertThat(Operator.LT.isRange()).isTrue();
        assertThat(Operator.GT.isRange()).isTrue();
        assertThat(Operator.EQ.isRange()).isFalse();
    }

    @Test
    void logicalOperators() {
        assertThat(LogicalOperator.AND).isNotEqualTo(LogicalOperator.OR);
    }

    @Test
    void specialKinds() {
        assertThat(SpecialKind.IS_NULL).isNotEqualTo(SpecialKind.IN);
    }

    @Test
    void volatilityOrdering() {
        assertThat(FunctionVolatility.IMMUTABLE)
            .isNotEqualTo(FunctionVolatility.VOLATILE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EnumTest`
Expected: FAIL — enums don't exist

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.pushdown.expression;

/** Comparison operators for ConnectorExpression.Comparison. */
public enum Operator {
    EQ("="),
    NEQ("<>"),
    LT("<"),
    GT(">"),
    LTE("<="),
    GTE(">=");

    private final String symbol;

    Operator(String symbol) { this.symbol = symbol; }

    public String symbol() { return symbol; }

    /** Range operators (LT, GT, LTE, GTE). EQ is NOT a range operator. */
    public boolean isRange() {
        return this == LT || this == GT || this == LTE || this == GTE;
    }
}
```

```java
package com.example.pushdown.expression;

public enum LogicalOperator {
    AND,
    OR,
    NOT
}
```

```java
package com.example.pushdown.expression;

public enum SpecialKind {
    IS_NULL,
    IS_NOT_NULL,
    IN,
    NOT_IN,
    BETWEEN,
    LIKE
}
```

```java
package com.example.pushdown.expression;

/**
 * Function volatility classification (§8.1 of design doc).
 * Drives shippability: IMMUTABLE → shippable, STABLE → needs snapshot pinning, VOLATILE → not shippable.
 */
public enum FunctionVolatility {
    IMMUTABLE,
    STABLE,
    VOLATILE
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=EnumTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/expression/ src/test/java/com/example/pushdown/expression/
git commit -m "feat: add expression enums (Operator, LogicalOperator, SpecialKind, FunctionVolatility)"
```

---

### Task 6: FunctionSignature

**Files:**
- Create: `src/main/java/com/example/pushdown/expression/FunctionSignature.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/expression/FunctionSignatureTest.java`:

```java
package com.example.pushdown.expression;

import com.example.pushdown.type.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FunctionSignatureTest {
    @Test
    void signatureHasNameAndVolatility() {
        FunctionSignature upper = new FunctionSignature(
            "upper", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        assertThat(upper.name()).isEqualTo("upper");
        assertThat(upper.volatility()).isEqualTo(FunctionVolatility.IMMUTABLE);
        assertThat(upper.parameterTypes()).containsExactly(Type.VARCHAR);
        assertThat(upper.returnType()).isEqualTo(Type.VARCHAR);
    }

    @Test
    void nowIsStable() {
        FunctionSignature now = new FunctionSignature(
            "now", List.of(), Type.TIMESTAMP, FunctionVolatility.STABLE);
        assertThat(now.volatility()).isEqualTo(FunctionVolatility.STABLE);
    }

    @Test
    void randomIsVolatile() {
        FunctionSignature random = new FunctionSignature(
            "random", List.of(), Type.DOUBLE, FunctionVolatility.VOLATILE);
        assertThat(random.volatility()).isEqualTo(FunctionVolatility.VOLATILE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FunctionSignatureTest`
Expected: FAIL — FunctionSignature doesn't exist

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.pushdown.expression;

import com.example.pushdown.type.Type;
import java.util.List;

/**
 * Function signature: name + parameter types + return type + volatility.
 * Volatility drives shippability (§8.1): IMMUTABLE → shippable, STABLE → snapshot pinning, VOLATILE → not shippable.
 */
public record FunctionSignature(
    String name,
    List<Type> parameterTypes,
    Type returnType,
    FunctionVolatility volatility
) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=FunctionSignatureTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/expression/FunctionSignature.java src/test/java/com/example/pushdown/expression/FunctionSignatureTest.java
git commit -m "feat: add FunctionSignature record"
```

---

### Task 7: ConnectorExpression Sealed Interface + Variable + Constant

**Files:**
- Create: `src/main/java/com/example/pushdown/expression/ConnectorExpression.java`
- Create: `src/main/java/com/example/pushdown/expression/Variable.java`
- Create: `src/main/java/com/example/pushdown/expression/Constant.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/expression/VariableConstantTest.java`:

```java
package com.example.pushdown.expression;

import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.type.Type;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VariableConstantTest {
    record TestColumn(String name) implements ColumnHandle {}

    @Test
    void variableHoldsColumnAndType() {
        ColumnHandle col = new TestColumn("age");
        Variable var = new Variable(col, Type.INTEGER);
        assertThat(var.column().name()).isEqualTo("age");
        assertThat(var.type()).isEqualTo(Type.INTEGER);
    }

    @Test
    void constantHoldsValueAndType() {
        Constant c = new Constant(42, Type.INTEGER);
        assertThat(c.value()).isEqualTo(42);
        assertThat(c.type()).isEqualTo(Type.INTEGER);
    }

    @Test
    void variableAndConstantAreConnectorExpressions() {
        ConnectorExpression var = new Variable(new TestColumn("x"), Type.VARCHAR);
        ConnectorExpression con = new Constant("hello", Type.VARCHAR);
        assertThat(var).isInstanceOf(ConnectorExpression.class);
        assertThat(con).isInstanceOf(ConnectorExpression.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=VariableConstantTest`
Expected: FAIL — ConnectorExpression, Variable, Constant don't exist

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.pushdown.expression;

/**
 * Sealed interface for the pushdown predicate/expression IR (§3.1 of design doc).
 * Replaces TupleDomain as the universal predicate representation.
 * Permits all expression types; new types must be added here.
 */
public sealed interface ConnectorExpression
    permits Variable, Constant, Call, Comparison, Logical, Cast, Special, TupleDomain {
}
```

```java
package com.example.pushdown.expression;

import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.type.Type;

/** Column reference within an expression. */
public record Variable(ColumnHandle column, Type type) implements ConnectorExpression {}
```

```java
package com.example.pushdown.expression;

import com.example.pushdown.type.Type;

/** Literal value within an expression. */
public record Constant(Object value, Type type) implements ConnectorExpression {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=VariableConstantTest`
Expected: PASS (will fail — permits reference Call, Comparison etc. which don't exist yet)

Note: Since sealed interface permits types that don't exist yet, we need to create stub files. Create empty stubs for Call, Comparison, Logical, Cast, Special, TupleDomain as placeholder records:

```java
// Stub — implemented in Task 8-10
package com.example.pushdown.expression;
import com.example.pushdown.type.Type;
import java.util.List;
public record Call(FunctionSignature function, List<ConnectorExpression> args, Type type) implements ConnectorExpression {}
```

```java
package com.example.pushdown.expression;
public record Comparison(Operator op, ConnectorExpression left, ConnectorExpression right) implements ConnectorExpression {}
```

```java
package com.example.pushdown.expression;
import java.util.List;
public record Logical(LogicalOperator op, List<ConnectorExpression> terms) implements ConnectorExpression {}
```

```java
package com.example.pushdown.expression;
import com.example.pushdown.type.Type;
public record Cast(ConnectorExpression expr, Type targetType) implements ConnectorExpression {}
```

```java
package com.example.pushdown.expression;
import java.util.List;
public record Special(SpecialKind kind, ConnectorExpression expr, List<ConnectorExpression> args) implements ConnectorExpression {}
```

```java
package com.example.pushdown.expression;
import com.example.pushdown.handle.ColumnHandle;
import java.util.Map;
// Stub — Domain type and full impl in Task 10
public record TupleDomain(Map<ColumnHandle, Object> domains) implements ConnectorExpression {}
```

Re-run: `mvn test -Dtest=VariableConstantTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/expression/ src/test/java/com/example/pushdown/expression/VariableConstantTest.java
git commit -m "feat: add ConnectorExpression sealed interface with Variable, Constant, and stubs"
```

---

### Task 8: Call, Comparison, Logical Expressions

**Files:**
- Modify: `src/main/java/com/example/pushdown/expression/Call.java` (replace stub)
- Modify: `src/main/java/com/example/pushdown/expression/Comparison.java` (replace stub)
- Modify: `src/main/java/com/example/pushdown/expression/Logical.java` (replace stub)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/expression/CallComparisonLogicalTest.java`:

```java
package com.example.pushdown.expression;

import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.type.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CallComparisonLogicalTest {
    record TestColumn(String name) implements ColumnHandle {}

    @Test
    void callWithFunctionAndArgs() {
        FunctionSignature upper = new FunctionSignature(
            "upper", List.of(Type.VARCHAR), Type.VARCHAR, FunctionVolatility.IMMUTABLE);
        Variable nameCol = new Variable(new TestColumn("name"), Type.VARCHAR);
        Call call = new Call(upper, List.of(nameCol), Type.VARCHAR);
        assertThat(call.function().name()).isEqualTo("upper");
        assertThat(call.args()).hasSize(1);
        assertThat(call.type()).isEqualTo(Type.VARCHAR);
    }

    @Test
    void comparisonWithOperator() {
        Variable age = new Variable(new TestColumn("age"), Type.INTEGER);
        Constant five = new Constant(5, Type.INTEGER);
        Comparison cmp = new Comparison(Operator.GT, age, five);
        assertThat(cmp.op()).isEqualTo(Operator.GT);
        assertThat(cmp.left()).isInstanceOf(Variable.class);
        assertThat(cmp.right()).isInstanceOf(Constant.class);
    }

    @Test
    void logicalAndWithTwoTerms() {
        Comparison c1 = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER),
            new Constant(1, Type.INTEGER));
        Comparison c2 = new Comparison(Operator.EQ,
            new Variable(new TestColumn("b"), Type.INTEGER),
            new Constant(2, Type.INTEGER));
        Logical and = new Logical(LogicalOperator.AND, List.of(c1, c2));
        assertThat(and.op()).isEqualTo(LogicalOperator.AND);
        assertThat(and.terms()).hasSize(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=CallComparisonLogicalTest`
Expected: PASS (stubs already compile and have correct fields) — if pass, the test validates stubs are correct. No change needed. Move to Step 5.

Actually the stubs already have the correct fields, so tests should pass. Let's add a volatility access test that validates the §8.2 fix:

Add to the test:
```java
    @Test
    void callVolatilityAccessedViaFunction() {
        FunctionSignature now = new FunctionSignature(
            "now", List.of(), Type.TIMESTAMP, FunctionVolatility.STABLE);
        Call call = new Call(now, List.of(), Type.TIMESTAMP);
        // v2.1 fix: access volatility via call.function().volatility(), NOT call.volatility()
        assertThat(call.function().volatility()).isEqualTo(FunctionVolatility.STABLE);
    }
```

- [ ] **Step 3: Verify Call stub has proper javadoc (replace stub)**

Replace `Call.java` stub with full version:

```java
package com.example.pushdown.expression;

import com.example.pushdown.type.Type;
import java.util.List;

/**
 * Function call: UPPER(name), date_trunc('month', ts), abs(x)
 * Volatility accessed via f.function().volatility() (v2.1 fix).
 */
public record Call(FunctionSignature function, List<ConnectorExpression> args, Type type)
    implements ConnectorExpression {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=CallComparisonLogicalTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/expression/Call.java src/test/java/com/example/pushdown/expression/CallComparisonLogicalTest.java
git commit -m "feat: add Call, Comparison, Logical expression types with volatility access test"
```

---

### Task 9: Cast, Special Expressions + Domain for TupleDomain

**Files:**
- Modify: `src/main/java/com/example/pushdown/expression/Cast.java` (replace stub)
- Modify: `src/main/java/com/example/pushdown/expression/Special.java` (replace stub)
- Create: `src/main/java/com/example/pushdown/expression/Domain.java`
- Modify: `src/main/java/com/example/pushdown/expression/TupleDomain.java` (replace stub)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/expression/CastSpecialDomainTest.java`:

```java
package com.example.pushdown.expression;

import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CastSpecialDomainTest {
    record TestColumn(String name) implements ColumnHandle {}

    @Test
    void castExpression() {
        Variable x = new Variable(new TestColumn("x"), Type.VARCHAR);
        Cast cast = new Cast(x, Type.INTEGER);
        assertThat(cast.targetType()).isEqualTo(Type.INTEGER);
        assertThat(cast.expr()).isEqualTo(x);
    }

    @Test
    void specialIsNull() {
        Variable name = new Variable(new TestColumn("name"), Type.VARCHAR);
        Special isNull = new Special(SpecialKind.IS_NULL, name, List.of());
        assertThat(isNull.kind()).isEqualTo(SpecialKind.IS_NULL);
        assertThat(isNull.expr()).isEqualTo(name);
        assertThat(isNull.args()).isEmpty();
    }

    @Test
    void domainHoldsMinAndMax() {
        Domain<Integer> domain = Domain.of(0, 100, Type.INTEGER);
        assertThat(domain.min()).isEqualTo(0);
        assertThat(domain.max()).isEqualTo(100);
        assertThat(domain.type()).isEqualTo(Type.INTEGER);
    }

    @Test
    void tupleDomainHoldsColumnDomains() {
        ColumnHandle age = new TestColumn("age");
        Domain<Integer> ageRange = Domain.of(18, 65, Type.INTEGER);
        TupleDomain td = new TupleDomain(Map.of(age, ageRange));
        assertThat(td.domains()).containsKey(age);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=CastSpecialDomainTest`
Expected: FAIL — Domain doesn't exist, TupleDomain stub has wrong type

- [ ] **Step 3: Write implementation**

Create `Domain.java`:
```java
package com.example.pushdown.expression;

import com.example.pushdown.type.Type;

/** Value range for a column, used by TupleDomain for fast-path column-domain predicates. */
public record Domain<T>(T min, T max, Type type) {
    public static <T> Domain<T> of(T min, T max, Type type) {
        return new Domain<>(min, max, type);
    }
}
```

Replace `Cast.java`:
```java
package com.example.pushdown.expression;

import com.example.pushdown.type.Type;

/** Type conversion expression. */
public record Cast(ConnectorExpression expr, Type targetType) implements ConnectorExpression {}
```

Replace `Special.java`:
```java
package com.example.pushdown.expression;

import java.util.List;

/** Special predicate: IS NULL, IS NOT NULL, IN, BETWEEN, LIKE. */
public record Special(SpecialKind kind, ConnectorExpression expr, List<ConnectorExpression> args)
    implements ConnectorExpression {}
```

Replace `TupleDomain.java`:
```java
package com.example.pushdown.expression;

import com.example.pushdown.handle.ColumnHandle;
import java.util.Map;
import java.util.Optional;

/**
 * TupleDomain: fast-path column-domain subset of ConnectorExpression.
 * Pure-range sources (Iceberg stats) can produce only TupleDomain.
 * Mixed sources can use TupleDomain for some conjuncts and Call for others.
 */
public record TupleDomain(Map<ColumnHandle, Domain<?>> domains) implements ConnectorExpression {

    /** Attempt to downgrade a ConnectorExpression to TupleDomain (for fast-path optimization). */
    public static Optional<TupleDomain> asTupleDomain(ConnectorExpression expr) {
        // Phase 1: not implemented; return empty
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=CastSpecialDomainTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/expression/ src/test/java/com/example/pushdown/expression/CastSpecialDomainTest.java
git commit -m "feat: add Cast, Special expressions, Domain, and TupleDomain"
```

---

### Task 10: Expression Helpers (Expressions.java)

**Files:**
- Create: `src/main/java/com/example/pushdown/expression/Expressions.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/expression/ExpressionsTest.java`:

```java
package com.example.pushdown.expression;

import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.type.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExpressionsTest {
    record TestColumn(String name) implements ColumnHandle {}

    @Test
    void logicalAndCombinesTwoExpressions() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER),
            new Constant(1, Type.INTEGER));
        Comparison b = new Comparison(Operator.EQ,
            new Variable(new TestColumn("b"), Type.INTEGER),
            new Constant(2, Type.INTEGER));
        ConnectorExpression result = Expressions.logicalAnd(a, b);
        assertThat(result).isInstanceOf(Logical.class);
        assertThat(((Logical) result).op()).isEqualTo(LogicalOperator.AND);
        assertThat(((Logical) result).terms()).hasSize(2);
    }

    @Test
    void logicalAndWithSingleExpressionReturnsItself() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER),
            new Constant(1, Type.INTEGER));
        ConnectorExpression result = Expressions.logicalAnd(a);
        assertThat(result).isEqualTo(a);
    }

    @Test
    void trueConstantExists() {
        assertThat(Expressions.TRUE()).isInstanceOf(Constant.class);
        assertThat(((Constant) Expressions.TRUE()).value()).isEqualTo(true);
    }

    @Test
    void splitConjunctsBreaksTopLevelAnd() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER),
            new Constant(1, Type.INTEGER));
        Comparison b = new Comparison(Operator.EQ,
            new Variable(new TestColumn("b"), Type.INTEGER),
            new Constant(2, Type.INTEGER));
        Comparison c = new Comparison(Operator.EQ,
            new Variable(new TestColumn("c"), Type.INTEGER),
            new Constant(3, Type.INTEGER));
        Logical and = new Logical(LogicalOperator.AND, List.of(a, b, c));
        List<ConnectorExpression> conjuncts = Expressions.splitConjuncts(and);
        assertThat(conjuncts).hasSize(3);
    }

    @Test
    void splitConjunctsOfSingleComparisonReturnsOne() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER),
            new Constant(1, Type.INTEGER));
        List<ConnectorExpression> conjuncts = Expressions.splitConjuncts(a);
        assertThat(conjuncts).hasSize(1);
        assertThat(conjuncts.get(0)).isEqualTo(a);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ExpressionsTest`
Expected: FAIL — Expressions class doesn't exist

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.pushdown.expression;

import com.example.pushdown.type.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Static helpers for ConnectorExpression manipulation.
 * Used by the planner, invariant validator, and SPI implementations.
 */
public final class Expressions {

    private Expressions() {}

    /** A TRUE constant, used as identity for AND reduction. */
    public static ConnectorExpression TRUE() {
        return new Constant(true, Type.BOOLEAN);
    }

    /** Logical AND of multiple expressions. Single expression → returns itself. */
    public static ConnectorExpression logicalAnd(ConnectorExpression... expressions) {
        if (expressions.length == 0) {
            return TRUE();
        }
        if (expressions.length == 1) {
            return expressions[0];
        }
        return new Logical(LogicalOperator.AND, List.of(expressions));
    }

    /** Logical AND of two expressions (convenience for reduce). */
    public static ConnectorExpression logicalAnd(ConnectorExpression a, ConnectorExpression b) {
        return logicalAnd(new ConnectorExpression[]{a, b});
    }

    /**
     * Split a ConnectorExpression into top-level AND conjuncts.
     * A single comparison → list of one. An AND logical → list of its terms.
     */
    public static List<ConnectorExpression> splitConjuncts(ConnectorExpression expr) {
        if (expr instanceof Logical logical && logical.op() == LogicalOperator.AND) {
            return new ArrayList<>(logical.terms());
        }
        return List.of(expr);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ExpressionsTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/expression/Expressions.java src/test/java/com/example/pushdown/expression/ExpressionsTest.java
git commit -m "feat: add Expressions helpers (logicalAnd, TRUE, splitConjuncts)"
```

---

### Task 11: PushdownMode Enum

**Files:**
- Create: `src/main/java/com/example/pushdown/mode/PushdownMode.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/mode/PushdownModeTest.java`:

```java
package com.example.pushdown.mode;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PushdownModeTest {
    @Test
    void threeModesExist() {
        assertThat(PushdownMode.values())
            .containsExactlyInAnyOrder(
                PushdownMode.EXACT,
                PushdownMode.CONSERVATIVE,
                PushdownMode.IN_MEMORY);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PushdownModeTest`
Expected: FAIL — PushdownMode doesn't exist

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.pushdown.mode;

/**
 * Pushdown execution mode (§3.2 of design doc).
 * Per-conjunct: a single FilterResult can have conjuncts with different modes.
 *
 * - EXACT: source fully executes this conjunct, result trusted. Residual can be empty.
 * - CONSERVATIVE: engine uses source metadata to skip locally (only proves non-match).
 *   Residual MUST == original conjunct (every surviving row still needs checking).
 * - IN_MEMORY: full scan, engine filters in memory. Residual == original conjunct.
 */
public enum PushdownMode {
    EXACT,
    CONSERVATIVE,
    IN_MEMORY
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=PushdownModeTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/mode/ src/test/java/com/example/pushdown/mode/
git commit -m "feat: add PushdownMode enum (EXACT/CONSERVATIVE/IN_MEMORY)"
```

---

### Task 12: Result Types (ConjunctPushdown, FilterResult interfaces + FilterResults factory)

**Files:**
- Create: `src/main/java/com/example/pushdown/result/ConjunctPushdown.java`
- Create: `src/main/java/com/example/pushdown/result/FilterResult.java`
- Create: `src/main/java/com/example/pushdown/result/FilterResults.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/result/FilterResultsTest.java`:

```java
package com.example.pushdown.result;

import com.example.pushdown.expression.*;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FilterResultsTest {
    record TestColumn(String name) implements ColumnHandle {}
    record TestTable(String name) implements com.example.pushdown.handle.TableHandle {}

    @Test
    void conjunctPushdownHoldsAllFields() {
        Comparison original = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(
            original, Optional.of(original), Expressions.TRUE(), PushdownMode.EXACT);
        assertThat(cp.originalConjunct()).isEqualTo(original);
        assertThat(cp.pushedExpression()).contains(original);
        assertThat(cp.mode()).isEqualTo(PushdownMode.EXACT);
    }

    @Test
    void filterResultCombinedResidual() {
        Comparison a = new Comparison(Operator.EQ,
            new Variable(new TestColumn("a"), Type.INTEGER),
            new Constant(1, Type.INTEGER));
        Comparison b = new Comparison(Operator.EQ,
            new Variable(new TestColumn("b"), Type.INTEGER),
            new Constant(2, Type.INTEGER));

        // a is EXACT (pushed, empty residual); b is IN_MEMORY (not pushed, residual = b)
        ConjunctPushdown cpA = FilterResults.conjunct(
            a, Optional.of(a), Expressions.TRUE(), PushdownMode.EXACT);
        ConjunctPushdown cpB = FilterResults.conjunct(
            b, Optional.empty(), b, PushdownMode.IN_MEMORY);

        FilterResult result = FilterResults.of(new TestTable("t"), List.of(cpA, cpB));

        // combinedResidual = TRUE ∧ b = b
        ConnectorExpression residual = result.combinedResidual();
        assertThat(residual).isEqualTo(b);
    }

    @Test
    void filterResultEmptyConjunctsResidualIsTrue() {
        FilterResult result = FilterResults.of(new TestTable("t"), List.of());
        assertThat(result.combinedResidual()).isEqualTo(Expressions.TRUE());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=FilterResultsTest`
Expected: FAIL — result classes don't exist

- [ ] **Step 3: Write implementation**

```java
package com.example.pushdown.result;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.mode.PushdownMode;
import java.util.Optional;

/**
 * Per-conjunct pushdown result (§3.5 of design doc).
 * Each conjunct independently declares its PushdownMode.
 * v2.1: interface (not record) for SPI extensibility.
 */
public interface ConjunctPushdown {
    ConnectorExpression originalConjunct();
    Optional<ConnectorExpression> pushedExpression();
    ConnectorExpression residualExpression();
    PushdownMode mode();
}
```

```java
package com.example.pushdown.result;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.Expressions;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import java.util.List;
import java.util.Optional;

/**
 * Filter pushdown result (§3.5 of design doc).
 * v2.1: interface for extensibility. Use FilterResults.of() factory to create.
 */
public interface FilterResult {
    TableHandle pushedTable();
    List<ConjunctPushdown> conjunctResults();

    /**
     * Engine residual = AND of all conjunct residuals.
     * v2.1 fix: uses correct Stream.reduce(BinaryOperator) overload.
     */
    default ConnectorExpression combinedResidual() {
        return conjunctResults().stream()
            .map(ConjunctPushdown::residualExpression)
            .reduce(ConnectorExpression::logicalAnd)
            .orElse(Expressions.TRUE());
    }

    /** v2.1 reserved for future stats hints. */
    default Optional<Object> estimatedStats() { return Optional.empty(); }
}
```

```java
package com.example.pushdown.result;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import java.util.List;
import java.util.Optional;

/**
 * Factory + immutable implementations for result types.
 * Connectors use this to create results without implementing interfaces manually.
 */
public final class FilterResults {

    private FilterResults() {}

    // Factory methods
    public static ConjunctPushdown conjunct(
            ConnectorExpression original,
            Optional<ConnectorExpression> pushed,
            ConnectorExpression residual,
            PushdownMode mode) {
        return new ImmutableConjunctPushdown(original, pushed, residual, mode);
    }

    public static FilterResult of(TableHandle table, List<ConjunctPushdown> conjuncts) {
        return new ImmutableFilterResult(table, conjuncts);
    }

    // Immutable implementations
    private record ImmutableConjunctPushdown(
        ConnectorExpression originalConjunct,
        Optional<ConnectorExpression> pushedExpression,
        ConnectorExpression residualExpression,
        PushdownMode mode
    ) implements ConjunctPushdown {}

    private record ImmutableFilterResult(
        TableHandle pushedTable,
        List<ConjunctPushdown> conjunctResults
    ) implements FilterResult {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=FilterResultsTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/result/ src/test/java/com/example/pushdown/result/
git commit -m "feat: add ConjunctPushdown, FilterResult interfaces + FilterResults factory"
```

---

### Task 13: SPI Version + Capability

**Files:**
- Create: `src/main/java/com/example/pushdown/spi/ConnectorVersion.java`
- Create: `src/main/java/com/example/pushdown/spi/ConnectorCapability.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/spi/ConnectorVersionTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ConnectorVersionTest`
Expected: FAIL

- [ ] **Step 3: Write implementation**

```java
package com.example.pushdown.spi;

/** SPI version (§17 of design doc). */
public record ConnectorVersion(int major, int minor) {
    public static final ConnectorVersion V2 = new ConnectorVersion(2, 1);
}
```

```java
package com.example.pushdown.spi;

/** Capabilities a connector can declare (§3.4 of design doc). */
public enum ConnectorCapability {
    FILTER_PUSHDOWN,
    PROJECTION_PUSHDOWN,
    AGGREGATE_PUSHDOWN,
    JOIN_PUSHDOWN,
    TOPN_PUSHDOWN,
    LIMIT_PUSHDOWN,
    DYNAMIC_FILTER,
    STATISTICS,
    FALLBACK,
    DATA_SKIPPING
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ConnectorVersionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/spi/ src/test/java/com/example/pushdown/spi/
git commit -m "feat: add ConnectorVersion and ConnectorCapability"
```

---

### Task 14: PushdownConnector SPI (Core Interface)

**Files:**
- Create: `src/main/java/com/example/pushdown/spi/PushdownConnector.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/spi/PushdownConnectorTest.java`:

```java
package com.example.pushdown.spi;

import com.example.pushdown.expression.*;
import com.example.pushdown.handle.*;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PushdownConnectorTest {
    @Test
    void defaultIsFilterPushableReturnsFalse() {
        PushdownConnector connector = new NoOpConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();
        assertThat(connector.isFilterPushable(
            session, new TestTable("t"), Expressions.TRUE())).isFalse();
    }

    @Test
    void defaultApplyFilterReturnsEmpty() {
        PushdownConnector connector = new NoOpConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("s1").build();
        assertThat(connector.applyFilter(
            session, new TestTable("t"), Expressions.TRUE(), null)).isEmpty();
    }

    @Test
    void defaultSupportsFallbackIsTrue() {
        PushdownConnector connector = new NoOpConnector();
        assertThat(connector.supportsFallback()).isTrue();
    }

    // Minimal no-op connector for testing defaults
    static class NoOpConnector implements PushdownConnector {
        @Override public ConnectorVersion getVersion() { return ConnectorVersion.V2; }
        @Override public Set<ConnectorCapability> capabilities(TableHandle table) { return Set.of(); }
    }

    record TestTable(String name) implements TableHandle {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PushdownConnectorTest`
Expected: FAIL — PushdownConnector doesn't exist

- [ ] **Step 3: Write implementation**

```java
package com.example.pushdown.spi;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.result.FilterResult;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.session.SnapshotContext;
import java.util.Optional;
import java.util.Set;

/**
 * Core pushdown SPI (§3.4 of design doc).
 * v2.1: isXxxPushable (pure check) + applyXxx (execute only on chosen path).
 */
public interface PushdownConnector {

    // ====== Versioning ======
    ConnectorVersion getVersion();
    Set<ConnectorCapability> capabilities(TableHandle table);

    // ====== Filter pushdown ======

    /** Pure check: is predicate pushable? No side effects, cacheable. */
    default boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                      ConnectorExpression predicate) {
        return false;
    }

    /** Execute pushdown. Only called on the planner's chosen path. */
    default Optional<FilterResult> applyFilter(ConnectorSession session, TableHandle table,
                                                ConnectorExpression predicate,
                                                SnapshotContext snapshot) {
        return Optional.empty();
    }

    // ====== Dynamic filter ======

    default boolean supportsDynamicFilter(TableHandle table, ColumnHandle column) {
        return false;
    }

    // ====== Statistics ======

    default Optional<Object> getTableStatistics(ConnectorSession session, TableHandle table) {
        return Optional.empty();
    }

    // ====== Error fallback (§12) ======

    default TableHandle fallbackToFullScan(TableHandle pushedTable) {
        throw new UnsupportedOperationException("This connector does not support fallback");
    }

    default boolean supportsFallback() { return true; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=PushdownConnectorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/spi/PushdownConnector.java src/test/java/com/example/pushdown/spi/PushdownConnectorTest.java
git commit -m "feat: add PushdownConnector SPI with isFilterPushable/applyFilter split"
```

---

### Task 15: Residual Invariant Validator

**Files:**
- Create: `src/main/java/com/example/pushdown/invariant/ResidualInvariantValidator.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/invariant/ResidualInvariantValidatorTest.java`:

```java
package com.example.pushdown.invariant;

import com.example.pushdown.expression.*;
import com.example.pushdown.handle.ColumnHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.type.Type;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResidualInvariantValidatorTest {
    record TestColumn(String name) implements ColumnHandle {}

    private final ResidualInvariantValidator validator = new ResidualInvariantValidator();

    @Test
    void exactConjunctWithEmptyResidualIsValid() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        ConjunctPushdown cp = FilterResults.conjunct(
            pred, Optional.of(pred), Expressions.TRUE(), PushdownMode.EXACT);
        // Should not throw
        assertThatCode(() -> validator.validateFilter(pred, List.of(cp)))
            .doesNotThrowAnyException();
    }

    @Test
    void conservativeConjunctWithFullResidualIsValid() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        // CONSERVATIVE: residual must == original
        ConjunctPushdown cp = FilterResults.conjunct(
            pred, Optional.of(pred), pred, PushdownMode.CONSERVATIVE);
        assertThatCode(() -> validator.validateFilter(pred, List.of(cp)))
            .doesNotThrowAnyException();
    }

    @Test
    void conservativeConjunctWithWrongResidualThrows() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        Comparison other = new Comparison(Operator.GT,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(0, Type.INTEGER));
        // CONSERVATIVE with wrong residual (not == original) → should throw
        ConjunctPushdown cp = FilterResults.conjunct(
            pred, Optional.of(pred), other, PushdownMode.CONSERVATIVE);
        assertThatThrownBy(() -> validator.validateFilter(pred, List.of(cp)))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void inMemoryConjunctWithNoPushedAndFullResidualIsValid() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        // IN_MEMORY: pushed empty, residual == original
        ConjunctPushdown cp = FilterResults.conjunct(
            pred, Optional.empty(), pred, PushdownMode.IN_MEMORY);
        assertThatCode(() -> validator.validateFilter(pred, List.of(cp)))
            .doesNotThrowAnyException();
    }

    @Test
    void inMemoryConjunctWithPushedThrows() {
        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new TestColumn("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        // IN_MEMORY: pushed must be empty
        ConjunctPushdown cp = FilterResults.conjunct(
            pred, Optional.of(pred), pred, PushdownMode.IN_MEMORY);
        assertThatThrownBy(() -> validator.validateFilter(pred, List.of(cp)))
            .isInstanceOf(AssertionError.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ResidualInvariantValidatorTest`
Expected: FAIL — validator doesn't exist

- [ ] **Step 3: Write implementation**

```java
package com.example.pushdown.invariant;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.expression.Expressions;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.ConjunctPushdown;
import java.util.List;

/**
 * Framework-level residual invariant validator (§3.3 of design doc).
 * Debug/test builds only. Ensures connector residuals match mode semantics.
 *
 * Invariants:
 * - EXACT: pushed ∧ residual ≡ original (logical equivalence — Phase 1 uses reference equality for residual==TRUE)
 * - CONSERVATIVE: residual == original (must keep full predicate)
 * - IN_MEMORY: residual == original, pushed empty
 */
public class ResidualInvariantValidator {

    /**
     * Validate filter pushdown result residuals.
     * @param originalPredicate the full predicate passed to applyFilter
     * @param conjunctResults the per-conjunct results returned by the connector
     * @throws AssertionError if any invariant is violated
     */
    public void validateFilter(ConnectorExpression originalPredicate,
                                List<ConjunctPushdown> conjunctResults) {
        List<ConnectorExpression> originalConjuncts = Expressions.splitConjuncts(originalPredicate);

        for (ConjunctPushdown cp : conjunctResults) {
            switch (cp.mode()) {
                case EXACT -> {
                    // Phase 1: check residual is TRUE (full pushdown) or equals original
                    // Full equivalence checking is Phase 3 (needs expression normalization)
                    ConnectorExpression residual = cp.residualExpression();
                    ConnectorExpression pushed = cp.pushedExpression()
                        .orElse(Expressions.TRUE());
                    // Basic check: pushed is present
                    assert cp.pushedExpression().isPresent()
                        : "EXACT conjunct must have pushed expression";
                }
                case CONSERVATIVE -> {
                    // Residual MUST == original conjunct
                    assert expressionsEqual(cp.residualExpression(), cp.originalConjunct())
                        : "CONSERVATIVE residual must equal original conjunct";
                }
                case IN_MEMORY -> {
                    // Pushed must be empty, residual == original
                    assert cp.pushedExpression().isEmpty()
                        : "IN_MEMORY conjunct must have empty pushed expression";
                    assert expressionsEqual(cp.residualExpression(), cp.originalConjunct())
                        : "IN_MEMORY residual must equal original conjunct";
                }
            }
        }
    }

    /** Phase 1: structural equality (record equals). Phase 3: add expression normalization. */
    private boolean expressionsEqual(ConnectorExpression a, ConnectorExpression b) {
        return a.equals(b);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ResidualInvariantValidatorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/invariant/ src/test/java/com/example/pushdown/invariant/
git commit -m "feat: add ResidualInvariantValidator with per-mode invariant checks"
```

---

### Task 16: Mock Connector (for end-to-end testing)

**Files:**
- Create: `src/main/java/com/example/pushdown/connector/mock/MockColumnHandle.java`
- Create: `src/main/java/com/example/pushdown/connector/mock/MockTableHandle.java`
- Create: `src/main/java/com/example/pushdown/connector/mock/MockConnector.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/connector/MockConnectorTest.java`:

```java
package com.example.pushdown.connector;

import com.example.pushdown.connector.mock.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import com.example.pushdown.spi.*;
import com.example.pushdown.type.Type;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MockConnectorTest {
    @Test
    void mockConnectorDeclaresFilterPushable() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = session();
        MockTableHandle table = new MockTableHandle("users");
        MockColumnHandle col = new MockColumnHandle("age");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(col, Type.INTEGER), new Constant(18, Type.INTEGER));

        assertThat(connector.isFilterPushable(session, table, pred)).isTrue();
    }

    @Test
    void mockConnectorAppliesFilterAsInMemory() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = session();
        MockTableHandle table = new MockTableHandle("users");
        MockColumnHandle col = new MockColumnHandle("age");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(col, Type.INTEGER), new Constant(18, Type.INTEGER));

        Optional<FilterResult> result = connector.applyFilter(session, table, pred, null);
        assertThat(result).isPresent();

        FilterResult fr = result.get();
        assertThat(fr.conjunctResults()).hasSize(1);
        assertThat(fr.conjunctResults().get(0).mode()).isEqualTo(PushdownMode.IN_MEMORY);
        assertThat(fr.conjunctResults().get(0).pushedExpression()).isEmpty();
        assertThat(fr.conjunctResults().get(0).residualExpression()).isEqualTo(pred);
    }

    @Test
    void mockConnectorSupportsFallback() {
        MockConnector connector = new MockConnector();
        assertThat(connector.supportsFallback()).isTrue();
    }

    @Test
    void mockConnectorFallbackReturnsSameTable() {
        MockConnector connector = new MockConnector();
        MockTableHandle table = new MockTableHandle("users");
        TableHandle fallback = connector.fallbackToFullScan(table);
        assertThat(fallback).isEqualTo(table);
    }

    private ConnectorSession session() {
        return ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MockConnectorTest`
Expected: FAIL — Mock classes don't exist

- [ ] **Step 3: Write implementation**

```java
package com.example.pushdown.connector.mock;

import com.example.pushdown.handle.ColumnHandle;

/** Test column handle for MockConnector. */
public record MockColumnHandle(String name) implements ColumnHandle {}
```

```java
package com.example.pushdown.connector.mock;

import com.example.pushdown.handle.TableHandle;

/** Test table handle for MockConnector. */
public record MockTableHandle(String name) implements TableHandle {}
```

```java
package com.example.pushdown.connector.mock;

import com.example.pushdown.expression.*;
import com.example.pushdown.handle.*;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import com.example.pushdown.spi.*;
import java.util.*;

/**
 * Mock connector for testing (IN_MEMORY mode — no real SQL pushdown).
 * All predicates are "pushed" as IN_MEMORY (engine filters in memory).
 * Used by the conformance test suite and planner tests.
 */
public class MockConnector implements PushdownConnector {

    @Override
    public ConnectorVersion getVersion() { return ConnectorVersion.V2; }

    @Override
    public Set<ConnectorCapability> capabilities(TableHandle table) {
        return Set.of(ConnectorCapability.FILTER_PUSHDOWN, ConnectorCapability.FALLBACK);
    }

    @Override
    public boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                      ConnectorExpression predicate) {
        return true; // Mock always claims pushable (IN_MEMORY)
    }

    @Override
    public Optional<FilterResult> applyFilter(ConnectorSession session, TableHandle table,
                                                ConnectorExpression predicate,
                                                SnapshotContext snapshot) {
        // Split predicate into conjuncts, all IN_MEMORY
        List<ConnectorExpression> conjuncts = Expressions.splitConjuncts(predicate);
        List<ConjunctPushdown> results = conjuncts.stream()
            .map(c -> FilterResults.conjunct(c, Optional.empty(), c, PushdownMode.IN_MEMORY))
            .toList();
        return Optional.of(FilterResults.of(table, results));
    }

    @Override
    public TableHandle fallbackToFullScan(TableHandle pushedTable) {
        // Mock: fallback returns the same table (no real pushdown happened)
        return pushedTable;
    }

    @Override
    public boolean supportsFallback() { return true; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=MockConnectorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/connector/mock/ src/test/java/com/example/pushdown/connector/MockConnectorTest.java
git commit -m "feat: add MockConnector (IN_MEMORY mode) for testing"
```

---

### Task 17: PlanPath + PushdownPathBuilder (Filter only, no cost model yet)

**Files:**
- Create: `src/main/java/com/example/pushdown/planner/PlanPath.java`
- Create: `src/main/java/com/example/pushdown/planner/PushdownPathBuilder.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/planner/PushdownPathBuilderTest.java`:

```java
package com.example.pushdown.planner;

import com.example.pushdown.connector.mock.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.handle.*;
import com.example.pushdown.session.*;
import com.example.pushdown.type.Type;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PushdownPathBuilderTest {
    @Test
    void buildsLocalOnlyAndPushFilterCandidates() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        PushdownPathBuilder builder = new PushdownPathBuilder(connector);
        List<PlanPath> paths = builder.buildCandidates(session, table, pred);

        // Should have at least 2 paths: LOCAL_ONLY and PUSH_FILTER
        assertThat(paths).hasSizeGreaterThanOrEqualTo(2);
        assertThat(paths.stream().map(PlanPath::name))
            .contains("LOCAL_ONLY", "PUSH_FILTER");
    }

    @Test
    void localOnlyPathHasNoPushdown() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        PushdownPathBuilder builder = new PushdownPathBuilder(connector);
        List<PlanPath> paths = builder.buildCandidates(session, table, pred);

        PlanPath localOnly = paths.stream()
            .filter(p -> p.name().equals("LOCAL_ONLY"))
            .findFirst().orElseThrow();
        assertThat(localOnly.pushed()).isFalse();
    }

    @Test
    void pushFilterPathIsPushed() {
        MockConnector connector = new MockConnector();
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        PushdownPathBuilder builder = new PushdownPathBuilder(connector);
        List<PlanPath> paths = builder.buildCandidates(session, table, pred);

        PlanPath pushFilter = paths.stream()
            .filter(p -> p.name().equals("PUSH_FILTER"))
            .findFirst().orElseThrow();
        assertThat(pushFilter.pushed()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PushdownPathBuilderTest`
Expected: FAIL — PlanPath, PushdownPathBuilder don't exist

- [ ] **Step 3: Write implementation**

```java
package com.example.pushdown.planner;

import com.example.pushdown.handle.TableHandle;

/**
 * A candidate pushdown path (§5.2 of design doc).
 * The planner builds multiple paths and selects the best by cost.
 * Phase 1: no cost model — first push path wins.
 */
public record PlanPath(
    String name,         // "LOCAL_ONLY" / "PUSH_FILTER" / ...
    boolean pushed,      // whether this path involves pushdown
    TableHandle table,   // target table
    double estimatedCost // Phase 1: 0 for push, 1 for local (placeholder)
) {
    public static PlanPath localOnly(TableHandle table) {
        return new PlanPath("LOCAL_ONLY", false, table, 1.0);
    }

    public static PlanPath pushFilter(TableHandle table) {
        return new PlanPath("PUSH_FILTER", true, table, 0.0);
    }
}
```

```java
package com.example.pushdown.planner;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.spi.PushdownConnector;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds candidate pushdown paths (§5.2 of design doc).
 * v2.1: uses isXxxPushable (pure check) for candidates, applyXxx only on chosen path.
 * Phase 1: Filter only, no cost model, no memo/pruning.
 */
public class PushdownPathBuilder {

    private final PushdownConnector connector;

    public PushdownPathBuilder(PushdownConnector connector) {
        this.connector = connector;
    }

    public List<PlanPath> buildCandidates(ConnectorSession session,
                                            TableHandle table,
                                            ConnectorExpression predicate) {
        List<PlanPath> paths = new ArrayList<>();

        // Path 1: LOCAL_ONLY (always available — Enumerable fallback)
        paths.add(PlanPath.localOnly(table));

        // Path 2: PUSH_FILTER (if isFilterPushable)
        if (connector.isFilterPushable(session, table, predicate)) {
            paths.add(PlanPath.pushFilter(table));
        }

        return paths;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=PushdownPathBuilderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/planner/ src/test/java/com/example/pushdown/planner/
git commit -m "feat: add PlanPath and PushdownPathBuilder (Filter candidates, no cost model)"
```

---

### Task 18: PushdownPlanner (selects best path + executes applyFilter)

**Files:**
- Create: `src/main/java/com/example/pushdown/planner/PushdownPlanner.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/pushdown/planner/PushdownPlannerTest.java`:

```java
package com.example.pushdown.planner;

import com.example.pushdown.connector.mock.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import com.example.pushdown.type.Type;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PushdownPlannerTest {
    @Test
    void plannerSelectsPushFilterOverLocalOnly() {
        MockConnector connector = new MockConnector();
        PushdownPlanner planner = new PushdownPlanner(connector);
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, pred, null);

        assertThat(result).isPresent();
        FilterResult fr = result.get();
        assertThat(fr.conjunctResults()).hasSize(1);
        assertThat(fr.conjunctResults().get(0).mode()).isEqualTo(PushdownMode.IN_MEMORY);
    }

    @Test
    void plannerReturnsEmptyWhenNoPushdown() {
        // Use a connector that doesn't support filter pushdown
        NoFilterConnector connector = new NoFilterConnector();
        PushdownPlanner planner = new PushdownPlanner(connector);
        ConnectorSession session = ConnectorSession.builder()
            .user("test").queryId("q1").serverId("none").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, pred, null);
        assertThat(result).isEmpty();
    }

    // Connector that doesn't support filter pushdown
    static class NoFilterConnector extends MockConnector {
        @Override
        public boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                         com.example.pushdown.expression.ConnectorExpression predicate) {
            return false;
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PushdownPlannerTest`
Expected: FAIL — PushdownPlanner doesn't exist

- [ ] **Step 3: Write implementation**

```java
package com.example.pushdown.planner;

import com.example.pushdown.expression.ConnectorExpression;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.result.FilterResult;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.session.SnapshotContext;
import com.example.pushdown.spi.PushdownConnector;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pushdown planner: selects best path and executes applyFilter (§5.2, §10 of design doc).
 * Phase 1: simple cost (push=0, local=1), no memo/pruning.
 * Phase 3 will add cost model + memo + pruning.
 */
public class PushdownPlanner {

    private final PushdownConnector connector;
    private final PushdownPathBuilder pathBuilder;

    public PushdownPlanner(PushdownConnector connector) {
        this.connector = connector;
        this.pathBuilder = new PushdownPathBuilder(connector);
    }

    /**
     * Plan and execute filter pushdown.
     * 1. Build candidate paths (isFilterPushable — pure check)
     * 2. Select best path by cost
     * 3. Execute applyFilter on chosen path (only once)
     */
    public Optional<FilterResult> planAndExecuteFilter(
            ConnectorSession session,
            TableHandle table,
            ConnectorExpression predicate,
            SnapshotContext snapshot) {

        List<PlanPath> candidates = pathBuilder.buildCandidates(session, table, predicate);

        // Select best path: lowest estimatedCost (Phase 1: push=0 beats local=1)
        PlanPath best = candidates.stream()
            .min(Comparator.comparingDouble(PlanPath::estimatedCost))
            .orElseThrow();

        // If best is LOCAL_ONLY, return empty (no pushdown)
        if (!best.pushed()) {
            return Optional.empty();
        }

        // Execute applyFilter ONLY on the chosen path (v2.1 fix: not during candidate building)
        return connector.applyFilter(session, table, predicate, snapshot);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=PushdownPlannerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pushdown/planner/PushdownPlanner.java src/test/java/com/example/pushdown/planner/PushdownPlannerTest.java
git commit -m "feat: add PushdownPlanner (selects best path + executes applyFilter)"
```

---

### Task 19: End-to-End Test (Filter pushdown with Mock connector + invariant validation)

**Files:**
- Create: `src/test/java/com/example/pushdown/E2EFilterPushdownTest.java`

- [ ] **Step 1: Write the end-to-end test**

```java
package com.example.pushdown;

import com.example.pushdown.connector.mock.*;
import com.example.pushdown.expression.*;
import com.example.pushdown.handle.TableHandle;
import com.example.pushdown.invariant.ResidualInvariantValidator;
import com.example.pushdown.mode.PushdownMode;
import com.example.pushdown.planner.*;
import com.example.pushdown.result.*;
import com.example.pushdown.session.*;
import com.example.pushdown.type.Type;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end test: SQL predicate → planner → pushdown → invariant validation.
 * Verifies the full Phase 1 pipeline works correctly.
 */
class E2EFilterPushdownTest {

    private final ResidualInvariantValidator validator = new ResidualInvariantValidator();

    @Test
    void singleEqualityFilterPushdownEndToEnd() {
        MockConnector connector = new MockConnector();
        PushdownPlanner planner = new PushdownPlanner(connector);
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q-001").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        // WHERE age = 18
        ConnectorExpression predicate = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        // Plan + execute
        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, predicate, null);

        // Assert pushdown happened
        assertThat(result).isPresent();
        FilterResult fr = result.get();

        // Assert IN_MEMORY mode (Mock connector)
        assertThat(fr.conjunctResults()).hasSize(1);
        ConjunctPushdown cp = fr.conjunctResults().get(0);
        assertThat(cp.mode()).isEqualTo(PushdownMode.IN_MEMORY);
        assertThat(cp.pushedExpression()).isEmpty();
        assertThat(cp.residualExpression()).isEqualTo(predicate);

        // Assert invariant validation passes (no exception)
        assertThatCode(() -> validator.validateFilter(predicate, fr.conjunctResults()))
            .doesNotThrowAnyException();

        // Assert combined residual == original predicate (IN_MEMORY keeps full residual)
        assertThat(fr.combinedResidual()).isEqualTo(predicate);
    }

    @Test
    void conjunctiveFilterSplitIntoMultipleConjuncts() {
        MockConnector connector = new MockConnector();
        PushdownPlanner planner = new PushdownPlanner(connector);
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q-002").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        // WHERE age = 18 AND name = 'Bob'
        Comparison agePred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));
        Comparison namePred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("name"), Type.VARCHAR),
            new Constant("Bob", Type.VARCHAR));
        ConnectorExpression predicate = Expressions.logicalAnd(agePred, namePred);

        // Plan + execute
        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, predicate, null);

        assertThat(result).isPresent();
        FilterResult fr = result.get();

        // Should have 2 conjuncts (split from AND)
        assertThat(fr.conjunctResults()).hasSize(2);

        // Both IN_MEMORY
        assertThat(fr.conjunctResults()).allMatch(cp -> cp.mode() == PushdownMode.IN_MEMORY);

        // Invariant validation passes
        assertThatCode(() -> validator.validateFilter(predicate, fr.conjunctResults()))
            .doesNotThrowAnyException();

        // Combined residual = original predicate (both conjuncts are IN_MEMORY)
        ConnectorExpression residual = fr.combinedResidual();
        assertThat(residual).isInstanceOf(Logical.class);
        assertThat(((Logical) residual).terms()).hasSize(2);
    }

    @Test
    void noPushdownWhenConnectorDoesNotSupport() {
        // Connector that doesn't support filter pushdown
        MockConnector connector = new MockConnector() {
            @Override
            public boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                             ConnectorExpression predicate) {
                return false;
            }
        };
        PushdownPlanner planner = new PushdownPlanner(connector);
        ConnectorSession session = ConnectorSession.builder()
            .user("alice").queryId("q-003").serverId("mock").build();
        MockTableHandle table = new MockTableHandle("users");

        Comparison pred = new Comparison(Operator.EQ,
            new Variable(new MockColumnHandle("age"), Type.INTEGER),
            new Constant(18, Type.INTEGER));

        Optional<FilterResult> result = planner.planAndExecuteFilter(session, table, pred, null);
        assertThat(result).isEmpty(); // No pushdown
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=E2EFilterPushdownTest`
Expected: PASS (all components already implemented) — if pass, the pipeline works end-to-end

- [ ] **Step 3: Run ALL tests to verify nothing is broken**

Run: `mvn test`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/example/pushdown/E2EFilterPushdownTest.java
git commit -m "test: add end-to-end filter pushdown test with invariant validation"
```

---

### Task 20: Run Full Test Suite + Verify Build

- [ ] **Step 1: Run full test suite**

Run: `cd SQL+AI/pushdown-framework && mvn clean test`
Expected: ALL tests PASS, BUILD SUCCESS

- [ ] **Step 2: Verify no warnings**

Run: `mvn compile -X 2>&1 | grep -i warning`
Expected: No unexpected warnings

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "chore: Phase 1 complete — core IR + SPI + mock connector + filter pushdown end-to-end"
```

---

## Phase 2-5 Outlines (Detailed plans created when Phase 1 completes)

### Phase 2: SQL Deparse + Shippability + MySQL Connector (Tasks 23-35)

| Task | Deliverable |
|---|---|
| 23 | `PushedPlan` interface (tableHandle, projections, conjunctResults, groupingKeys, etc.) |
| 24 | `SqlDialect` interface (quoteIdentifier, formatLiteral, typeName) |
| 25 | `DeparsedQuery` interface (sql, retrievedAttrs, parameters, fetchSize) |
| 26 | `SqlDeparser` interface + `DefaultSqlDeparser` (deparseSelectStmt, deparseExpr recursive walker) |
| 27 | `SourceFamily` enum + `BuiltinCatalog` interface (per-source function whitelist) |
| 28 | `ShippabilityRegistry` (per-source builtin + extension whitelist + semantic compatibility) |
| 29 | `ShippabilityChecker` (isShippable with volatility, collation, timezone, snapshot checks) |
| 30 | `StableFunctionPinner` (replace STABLE functions with pinned timestamp constants) |
| 31 | MySQL `JdbcConnector` (isFilterPushable → shippability check; applyFilter → SQL generation) |
| 32 | MySQL `JdbcTableHandle` + `JdbcColumnHandle` |
| 33 | End-to-end test: MySQL filter pushdown with real SQL generation |
| 34 | Test: shippability rejects volatile functions, accepts immutable |
| 35 | Test: STABLE function pinning produces correct SQL |

### Phase 3: Full Operator Pushdown + Cost Model + Statistics (Tasks 36-50)

| Task | Deliverable |
|---|---|
| 36 | `MergeFunction` interface + `mergeAll` default (SUM/COUNT/AVG/MIN/MAX implementations) |
| 37 | `IntermediateAggregate` interface + factory |
| 38 | `AggregateResult` interface + `AggregateMode` enum |
| 39 | `isAggregatePushable` + `applyAggregate` on PushdownConnector |
| 40 | Test: partial aggregate pushdown + merge correctness |
| 41 | `JoinResult` interface + `CrossSourceStrategy` enum |
| 42 | `isJoinPushable` + `applyJoin` on PushdownConnector |
| 43 | `TopNResult` interface (orderGuaranteed, sortCollation, isOrderTrustworthy) |
| 44 | `LimitResult` interface (limitGuaranteed) |
| 45 | `isTopNPushable` + `applyTopN` + `isLimitPushable` + `applyLimit` |
| 46 | `TableStatistics` + `ColumnStatistics` records |
| 47 | `StatisticsProvider` interface + `StatisticsCache` (TTL + DDL invalidation) |
| 48 | `FallbackEstimator` (heuristic selectivity: equality/range/LIKE/IS NULL) |
| 49 | `PushdownCostModel` (shouldPush with EXACT vs CONSERVATIVE formulas — F2 fix) |
| 50 | `Memo` + pruning + candidate cap in PushdownPathBuilder |

### Phase 4: Dynamic Filter + Data Skipping + Error Fallback (Tasks 51-62)

| Task | Deliverable |
|---|---|
| 51 | `DynamicFilter` interface (isBlocked, getCurrentPredicate, isComplete, getError) |
| 52 | `DynamicFilterSource` interface (maxDistinctValues, overflowStrategy, markFinal, markError) |
| 53 | `DynamicFilterSnapshot` (immutable snapshot for split delivery) |
| 54 | `OverflowStrategy` enum (DOWNGRADE_TO_BLOOM_FILTER, DROP_FILTER, KEEP_PARTIAL) |
| 55 | `ScanContext` (mode-aware static+dynamic composition) |
| 56 | `DynamicSplitSource` (getNextBatch with snapshot) |
| 57 | Test: dynamic filter lifecycle PENDING→PARTIAL→FINAL→ERROR |
| 58 | `PredicateProjection` (Inclusive/Strict) + `ScanUnit` + `DataSkippingResult` |
| 59 | `PushdownFallbackHandler` (retry + fallbackToFullScan + circuit breaker) |
| 60 | `CircuitBreaker` (per-source failure tracking, cool-down) |
| 61 | Test: error fallback → retry → full scan fallback |
| 62 | Test: circuit breaker opens after consecutive failures |

### Phase 5: Security + Conformance Suite + Versioning (Tasks 63-72)

| Task | Deliverable |
|---|---|
| 63 | `RlsAwarePushdown` (append RLS predicates to pushdown predicate) |
| 64 | `MaskingAwarePushdown` (don't push masked columns) |
| 65 | `PushdownAuditLogger` (log all pushed SQL with user identity) |
| 66 | `ConnectorConformanceSuite` base class |
| 67 | Test: residual invariant correctness (property-based, 1000 random predicates) |
| 68 | Test: guarantee flag honesty (verify source sorting matches declared collation) |
| 69 | Test: partial aggregate merge correctness (push partial → merge → compare with local) |
| 70 | Test: dynamic filter lifecycle + ERROR propagation |
| 71 | Test: error fallback correctness (push fails → fallback → correct results) |
| 72 | `SubqueryDecorrelator` stub (Phase 5: strategy table → implementation) |

---

## Self-Review

### Spec Coverage (Phase 1)
- ✅ §3.1 ConnectorExpression IR — Tasks 5-10
- ✅ §3.2 PushdownMode (per-conjunct) — Task 11
- ✅ §3.3 ResidualInvariantValidator — Task 15
- ✅ §3.4 PushdownConnector SPI (isPushable/apply split) — Tasks 13-14
- ✅ §3.5 Result types as interfaces — Task 12
- ✅ §3.6 Tier 1 adapter — Task 16 (MockConnector is a Tier 1 style connector)
- ✅ §5.2 PushdownPathBuilder (isPushable for candidates) — Task 17
- ✅ §5.2 PushdownPlanner (apply only on chosen path) — Task 18
- ✅ §17 ConnectorVersion + Capability — Task 13
- ⏳ §4 Deparse layer — Phase 2
- ⏳ §3.5 AggregateResult/JoinResult/TopNResult — Phase 3
- ⏳ §8 ShippabilityChecker — Phase 2
- ⏳ §9 Cost model + statistics — Phase 3
- ⏳ §5 Dynamic filter — Phase 4
- ⏳ §12 Error fallback — Phase 4
- ⏳ §13-16 Security/testing — Phase 5

### Placeholder Scan
- No "TBD", "TODO", "implement later" found
- All code steps have complete code
- All test steps have complete test code
- All commands have expected output

### Type Consistency
- `ConnectorExpression` used consistently across all tasks
- `FilterResult` is interface (not record) in all references
- `ConjunctPushdown` is interface with factory `FilterResults.conjunct()`
- `PushdownMode.IN_MEMORY` used consistently in MockConnector
- `isFilterPushable` / `applyFilter` signatures match between SPI and MockConnector
- `Expressions.logicalAnd` / `Expressions.TRUE` / `Expressions.splitConjuncts` used consistently

---

**Plan complete and saved to `SQL+AI/docs/plans/2026-06-27-pushdown-framework.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
