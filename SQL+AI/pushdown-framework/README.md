# Pushdown Framework

一个查询引擎下推框架，将 SQL 中底层存储支持的部分自动下推到存储执行，减少数据传输量。支持部分下推（残余返回）、动态运行时过滤、成本驱动决策和熔断回退。

> **555 个测试通过 | Java 17 | Maven | 基于 postgres_fdw / Trino / Spark / Calcite / Iceberg 研究**

---

## 目录

- [能力概览](#能力概览)
- [架构](#架构)
- [快速开始](#快速开始)
- [接入新存储](#接入新存储)
- [核心概念](#核心概念)
- [API 参考](#api-参考)
- [模块说明](#模块说明)

---

## 能力概览

### 下推算子

| 算子 | 支持情况 | 说明 |
|---|---|---|
| **Filter** | ✅ 完整 | 逐 conjunct 下推；EXACT/CONSERVATIVE/IN_MEMORY 三种模式 |
| **Projection** | ✅ 完整 | 表达式投影下推 |
| **Aggregate** | ✅ 完整 | COMPLETE（源做完整聚合）/ PARTIAL（两阶段聚合 + 合并） |
| **Join** | ✅ 完整 | 同源 FULL_PUSH；跨源 FILTER_EACH_SIDE / BROADCAST / SEMI |
| **TopN** | ✅ 完整 | ORDER BY + LIMIT，带 collation 保证标志 |
| **Limit** | ✅ 完整 | 纯 LIMIT，带 guarantee flag |
| **Dynamic Filter** | ✅ 完整 | 运行时谓词（PENDING→PARTIAL→FINAL\|ERROR）+ Bloom filter 降级 |

### 关键特性

| 特性 | 说明 |
|---|---|
| **部分下推 + 残余返回** | 源接能接的 conjunct，返回接不了的作为残余，引擎在上方补 Filter |
| **逐 conjunct 模式** | 同一查询中不同 conjunct 可有不同模式（Hive 混合 EXACT + CONSERVATIVE） |
| **残余不变量校验** | debug/test 模式自动校验 EXACT/CONSERVATIVE/IN_MEMORY 残余语义正确性 |
| **isPushable / apply 拆分** | 纯检查（无副作用、可缓存）与执行（仅选中路径）分离 |
| **ConnectorExpression IR** | 支持函数调用、算术、cast 谓词（`UPPER(name)='BOB'`、`a+b>10`） |
| **成本驱动决策** | EXACT vs CONSERVATIVE 不同成本公式；选择率接近 1.0 时拒绝下推 |
| **Shippability 逐源白名单** | MySQL/PG/ClickHouse 各有独立内置函数目录 + 语义兼容检查 |
| **STABLE 函数快照钉入** | `now()` 替换为快照时间戳常量，保证快照一致性 |
| **错误回退 + 熔断** | 源失败→重试→回退全扫；连续 5 次失败→熔断 60s |
| **安全：RLS 不绕过** | 引擎 RLS 谓词追加到下推谓词；脱敏列不下推 |
| **一致性测试套件** | 100 次属性测试：随机谓词→验证下推+残余=本地执行结果 |

---

## 架构

```
SQL → Parser → LogicalPlan (ConnectorExpression IR)
                    │
         ┌──────────┴──────────┐
         ▼                     ▼
   PushdownPathBuilder     ShippabilityChecker
   (枚举候选路径)          (逐源白名单 + volatility)
   + Memo + 剪枝 + 上限
         │
         ▼
   PushdownCostModel
   (EXACT vs CONSERVATIVE)
         │
         ▼
   PushdownPlanner (选最优路径)
         │
         ▼
   applyFilter / applyAggregate / applyJoin / applyTopN
   (仅对选中路径执行)
         │
         ▼
   SqlDeparser → SQL 字符串 (仅 SQL 源)
         │
         ▼
   源执行下推 + 引擎执行残余 → 合并结果
```

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>pushdown-framework</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. 使用内置 MySQL 连接器

```java
import com.example.pushdown.connector.jdbc.JdbcConnector;
import com.example.pushdown.planner.PushdownPlanner;
import com.example.pushdown.session.ConnectorSession;
import com.example.pushdown.session.SnapshotContext;
import com.example.pushdown.expression.*;
import com.example.pushdown.type.Type;
import java.time.Instant;

// 1. 创建连接器
JdbcConnector connector = new JdbcConnector("mysql-prod");

// 2. 创建 Planner
PushdownPlanner planner = new PushdownPlanner(connector);

// 3. 构建 session
ConnectorSession session = ConnectorSession.builder()
    .user("alice")
    .queryId("q-001")
    .serverId("mysql-prod")
    .build();

SnapshotContext snapshot = new SnapshotContext(
    Instant.now(), SnapshotContext.IsolationLevel.SNAPSHOT, "snap-1");

// 4. 构建谓词: WHERE age = 18 AND UPPER(name) = 'BOB'
ConnectorExpression predicate = Expressions.logicalAnd(
    new Comparison(Operator.EQ,
        new Variable(new JdbcColumnHandle("age"), Type.INTEGER),
        new Constant(18, Type.INTEGER)),
    new Comparison(Operator.EQ,
        new Call(new FunctionSignature("UPPER", List.of(Type.VARCHAR), Type.VARCHAR,
                 FunctionVolatility.IMMUTABLE),
            List.of(new Variable(new JdbcColumnHandle("name"), Type.VARCHAR)),
            Type.VARCHAR),
        new Constant("BOB", Type.VARCHAR))
);

// 5. 执行下推
var result = planner.planAndExecuteFilter(
    session, new JdbcTableHandle("users"), predicate, snapshot);

// 6. 结果: 两个 conjunct 都是 EXACT (age=18 和 UPPER(name)='BOB' 都可运)
// residual = TRUE (源已全部处理)
```

### 3. 生成 SQL

```java
import com.example.pushdown.deparse.*;

// 用下推结果构建 PushedPlan
PushedPlan plan = PushedPlan.builder()
    .tableHandle(new JdbcTableHandle("users"))
    .projections(List.of(
        new Variable(new JdbcColumnHandle("name"), Type.VARCHAR),
        new Variable(new JdbcColumnHandle("age"), Type.INTEGER)
    ))
    .conjunctResults(result.get().conjunctResults())
    .build();

// deparse 为 SQL
DeparsedQuery query = new DefaultSqlDeparser().deparseSelectStmt(plan, SqlDialect.MYSQL);

System.out.println(query.sql());
// 输出: SELECT `name`, `age` FROM `users` WHERE (`age` = 18) AND (`UPPER`(`name`) = 'BOB')
```

### 4. 混合下推（部分可运）

```java
// WHERE age = 18 AND random() > 0.5
// age=18 是 IMMUTABLE → EXACT (下推到 SQL)
// random() 是 VOLATILE → IN_MEMORY (引擎本地执行)

ConnectorExpression predicate = Expressions.logicalAnd(
    new Comparison(Operator.EQ,
        new Variable(new JdbcColumnHandle("age"), Type.INTEGER),
        new Constant(18, Type.INTEGER)),
    new Comparison(Operator.GT,
        new Call(new FunctionSignature("RANDOM", List.of(), Type.DOUBLE,
                 FunctionVolatility.VOLATILE), List.of(), Type.DOUBLE),
        new Constant(0.5, Type.DOUBLE))
);

var result = planner.planAndExecuteFilter(
    session, new JdbcTableHandle("users"), predicate, snapshot);

// 结果:
// conjunct 0: age=18 → EXACT (pushed, residual=TRUE)
// conjunct 1: random()>0.5 → IN_MEMORY (not pushed, residual=full predicate)
// SQL 中只有 WHERE age = 18
// random()>0.5 留给引擎本地执行
```

---

## 接入新存储

### Tier 1：简单接入（仅全扫 + 内存过滤）

实现 `ScannableTable` / `FilterableTable`，适配器自动包装为 `PushdownConnector`：

```java
// 最简单：所有谓词都 IN_MEMORY（引擎内存过滤）
public class MyConnector extends MockConnector {
    // 覆写 isFilterPushable 返回 true 即可
    // applyFilter 自动将所有 conjunct 标记为 IN_MEMORY
}
```

### Tier 2：深度接入（完整下推）

实现 `PushdownConnector` 接口，按需覆写：

```java
public class MyConnector implements PushdownConnector {

    private final ShippabilityChecker shippabilityChecker = new ShippabilityChecker();
    private final StableFunctionPinner stablePinner = new StableFunctionPinner();

    @Override
    public ConnectorVersion getVersion() { return ConnectorVersion.V2; }

    @Override
    public Set<ConnectorCapability> capabilities(TableHandle table) {
        return Set.of(ConnectorCapability.FILTER_PUSHDOWN,
                       ConnectorCapability.AGGREGATE_PUSHDOWN,
                       ConnectorCapability.FALLBACK);
    }

    // ====== Filter 下推 ======
    @Override
    public boolean isFilterPushable(ConnectorSession session, TableHandle table,
                                      ConnectorExpression predicate) {
        // 纯检查：至少一个 conjunct 可运
        return Expressions.splitConjuncts(predicate).stream()
            .anyMatch(c -> shippabilityChecker.isShippable(c, session, null));
    }

    @Override
    public Optional<FilterResult> applyFilter(ConnectorSession session, TableHandle table,
                                                ConnectorExpression predicate,
                                                SnapshotContext snapshot) {
        List<ConjunctPushdown> results = new ArrayList<>();
        for (var conjunct : Expressions.splitConjuncts(predicate)) {
            if (shippabilityChecker.isShippable(conjunct, session, snapshot)) {
                // 可运 → EXACT
                var pushed = stablePinner.pinStableFunctions(conjunct, snapshot);
                results.add(FilterResults.conjunct(
                    conjunct, Optional.of(pushed), Expressions.TRUE(), PushdownMode.EXACT));
            } else {
                // 不可运 → IN_MEMORY
                results.add(FilterResults.conjunct(
                    conjunct, Optional.empty(), conjunct, PushdownMode.IN_MEMORY));
            }
        }
        return Optional.of(FilterResults.of(table, results));
    }

    // ====== Aggregate 下推 ======
    @Override
    public boolean isAggregatePushable(ConnectorSession session, TableHandle table,
                                         List<FunctionSignature> aggregates,
                                         List<ColumnHandle> groupingKeys,
                                         ConnectorExpression having) {
        // 所有聚合函数都必须可运 + IMMUTABLE
        return aggregates.stream().allMatch(agg ->
            agg.volatility() != FunctionVolatility.VOLATILE &&
            shippabilityChecker.getRegistry().isShippable(agg, session.serverId()));
    }

    @Override
    public Optional<AggregateResult> applyAggregate(ConnectorSession session, TableHandle table,
                                                      List<FunctionSignature> aggregates,
                                                      List<ColumnHandle> groupingKeys,
                                                      ConnectorExpression having) {
        // 为每个聚合函数创建 IntermediateAggregate + MergeFunction
        List<IntermediateAggregate> intermediates = aggregates.stream()
            .map(agg -> IntermediateAggregate.of(agg, agg.returnType(),
                mergeFunctionFor(agg), agg.name() + "_partial"))
            .toList();
        return Optional.of(AggregateResult.of(table, AggregateMode.COMPLETE,
            intermediates, Expressions.TRUE(), List.of()));
    }

    private MergeFunction mergeFunctionFor(FunctionSignature agg) {
        return switch (agg.name().toUpperCase()) {
            case "COUNT" -> MergeFunctions.count();
            case "SUM"   -> MergeFunctions.sum();
            case "AVG"   -> MergeFunctions.avg();
            case "MIN"   -> MergeFunctions.min();
            case "MAX"   -> MergeFunctions.max();
            default -> throw new UnsupportedOperationException("Unsupported agg: " + agg.name());
        };
    }

    // ====== 错误回退 ======
    @Override
    public TableHandle fallbackToFullScan(TableHandle pushedTable) {
        return pushedTable; // 返回原始未下推的 table handle
    }

    @Override
    public boolean supportsFallback() { return true; }
}
```

### 注册扩展函数白名单

```java
ShippabilityRegistry registry = new ShippabilityRegistry();
// 注册自定义函数（标记为源已安装）
registry.registerExtension("my-source", Set.of("MY_CUSTOM_FUNC", "ANOTHER_FUNC"));
```

---

## 核心概念

### 三种下推模式

| 模式 | 含义 | 残余 | 适用场景 |
|---|---|---|---|
| **EXACT** | 源完整执行此 conjunct，结果可信 | 可为空（TRUE） | JDBC SQL 下推（MySQL/PG） |
| **CONSERVATIVE** | 引擎用源元数据本地跳过，只证伪不证真 | 必须 == 原始 conjunct | 数据跳过（Iceberg/Delta/Parquet） |
| **IN_MEMORY** | 全扫后引擎内存过滤 | 必须 == 原始 conjunct | 不支持 SQL 的源（HBase/Redis） |

> **关键**：同一查询中不同 conjunct 可有不同模式。例如 Hive 查询 `WHERE city='NYC' AND json_col @> '{"x":1}'`——`city='NYC'` 是 EXACT（HiveQL WHERE），`json_col @> ...` 是 CONSERVATIVE（ORC 统计跳过）。

### 残余返回契约

每个 `applyXxx` 返回结果携带残余——引擎据此知道还需本地做什么：

```java
// applyFilter 返回:
FilterResult {
    pushedTable: <已烤入 EXACT conjunct 的新 handle>,
    conjunctResults: [
        ConjunctPushdown { original: age=18, pushed: age=18, residual: TRUE, mode: EXACT },
        ConjunctPushdown { original: random()>0.5, pushed: empty, residual: random()>0.5, mode: IN_MEMORY }
    ]
}

// combinedResidual = TRUE ∧ random()>0.5 = random()>0.5
// 引擎在上方插入 Filter(random()>0.5) 执行残余
```

### isPushable / apply 拆分

```
路径构建阶段: isFilterPushable() → 纯检查，无副作用，可缓存
                    ↓
规划器选最优路径: 按 cost 选择
                    ↓
仅对选中路径执行: applyFilter() → 生成新 TableHandle + SQL
```

### 动态过滤生命周期

```
PENDING  → isBlocked() 未完成, predicate=empty
    ↓
PARTIAL  → isBlocked() 完成, predicate=部分谓词
    ↓
FINAL    → isComplete()=true, predicate=完整谓词

ERROR    → getError()=present, probe 放弃动态过滤，回退全扫
```

超限时降级：精确 IN-list → Bloom filter → 放弃过滤

### 成本模型（EXACT vs CONSERVATIVE 不同公式）

```
EXACT push cost = startup + remoteCompute(N) + transfer(N × selectivity)
  → 源执行谓词，只传存活行

CONSERVATIVE push cost = startup + filePrune + transfer(N × sel × 2.0) + localResidualCompute
  → 源跳过文件，但存活行全扫 + 引擎逐行检查残余
  → 2.0 是 over-scan 因子（统计是粗粒度的）
```

---

## API 参考

### PushdownConnector（核心 SPI）

```java
public interface PushdownConnector {
    // 版本 + 能力
    ConnectorVersion getVersion();
    Set<ConnectorCapability> capabilities(TableHandle table);

    // Filter
    boolean isFilterPushable(session, table, predicate);           // 纯检查
    Optional<FilterResult> applyFilter(session, table, pred, snapshot);  // 执行

    // Aggregate
    boolean isAggregatePushable(session, table, aggregates, groupingKeys, having);
    Optional<AggregateResult> applyAggregate(session, table, aggregates, groupingKeys, having);

    // Join
    boolean isJoinPushable(session, joinType, left, right, condition);
    Optional<JoinResult> applyJoin(session, joinType, left, right, condition);

    // TopN
    boolean isTopNPushable(session, table, limit, orderBy);
    Optional<TopNResult> applyTopN(session, table, limit, orderBy);

    // Limit
    boolean isLimitPushable(session, table, limit);
    Optional<LimitResult> applyLimit(session, table, limit);

    // 动态过滤
    boolean supportsDynamicFilter(table, column);

    // 统计
    Optional<Object> getTableStatistics(session, table);

    // 错误回退
    TableHandle fallbackToFullScan(pushedTable);
    boolean supportsFallback();
}
```

### ConnectorExpression IR

```java
sealed interface ConnectorExpression
    permits Variable, Constant, Call, Comparison, Logical, Cast, Special, TupleDomain {}

Variable(ColumnHandle column, Type type)         // 列引用
Constant(Object value, Type type)                 // 字面量
Call(FunctionSignature function, List<args>, Type)  // 函数调用
Comparison(Operator op, left, right)              // 比较操作
Logical(LogicalOperator op, List<terms>)          // AND/OR/NOT
Cast(ConnectorExpression expr, Type targetType)   // 类型转换
Special(SpecialKind kind, expr, List<args>)       // IS NULL / IN / BETWEEN / LIKE
TupleDomain(Map<ColumnHandle, Domain> domains)    // 快速路径子集
```

### 结果类型（全部为 interface，可扩展）

| 接口 | 关键方法 |
|---|---|
| `FilterResult` | `pushedTable()`, `conjunctResults()`, `combinedResidual()` |
| `ConjunctPushdown` | `originalConjunct()`, `pushedExpression()`, `residualExpression()`, `mode()` |
| `AggregateResult` | `mode()`, `intermediateAggregates()`, `remainingHaving()` |
| `JoinResult` | `pushedJoinTable()`, `remainingCondition()`, `crossSourceStrategy()` |
| `TopNResult` | `orderGuaranteed()`, `limitGuaranteed()`, `sortCollation()`, `isOrderTrustworthy(collation)` |
| `LimitResult` | `limitGuaranteed()` |

---

## 模块说明

| 包 | 职责 |
|---|---|
| `expression` | ConnectorExpression IR（谓词/投影/HAVING/JOIN 条件的统一表达式） |
| `spi` | PushdownConnector 核心 SPI + ConnectorVersion + ConnectorCapability |
| `mode` | PushdownMode 枚举（EXACT/CONSERVATIVE/IN_MEMORY） |
| `result` | FilterResult / ConjunctPushdown 接口 + FilterResults 工厂 |
| `deparse` | PushedPlan + SqlDialect + SqlDeparser（表达式→SQL 字符串） |
| `shippability` | ShippabilityChecker + 逐源白名单 + StableFunctionPinner |
| `aggregate` | MergeFunction + IntermediateAggregate + AggregateResult |
| `join` | JoinType + CrossSourceStrategy + JoinResult |
| `topn` | Collation + TopNResult + LimitResult（guarantee flag） |
| `planner` | PushdownPathBuilder（枚举+Memo+剪枝） + PushdownPlanner |
| `cost` | PushdownCostModel（EXACT vs CONSERVATIVE 成本公式） |
| `statistics` | TableStatistics + StatisticsCache + FallbackEstimator |
| `dynamicfilter` | DynamicFilter（四态生命周期）+ ScanContext + DynamicFilterSource |
| `fallback` | PushdownFallbackHandler（retry+fallback） + CircuitBreaker |
| `security` | RlsAwarePushdown + MaskingAwarePushdown + PushdownAuditLogger |
| `invariant` | ResidualInvariantValidator（debug/test 不变量校验） |
| `connector.jdbc` | JdbcConnector（MySQL/PG EXACT 模式参考实现） |
| `connector.mock` | MockConnector（IN_MEMORY 模式测试连接器） |

---

## 内置连接器

| 连接器 | 模式 | 说明 |
|---|---|---|
| `JdbcConnector` | EXACT | MySQL/PG SQL 下推；ShippabilityChecker 判定可运性；STABLE 钉入 |
| `MockConnector` | IN_MEMORY | 测试用；所有谓词引擎内存过滤 |

### 内置 Shippability 白名单

| 源族 | 内置函数 |
|---|---|
| MySQL | ABS, CEIL, FLOOR, ROUND, MOD, CONCAT, SUBSTRING, LENGTH, TRIM, LOWER, UPPER, REPLACE, LEFT, RIGHT, COUNT, SUM, MIN, MAX, AVG, NOW, CURRENT_TIMESTAMP, CURRENT_DATE, DATE_FORMAT, DATE_ADD, DATE_SUB, DATEDIFF, COALESCE, IFNULL, NULLIF, CAST |
| PostgreSQL | 同上 + PG 特有函数名 |
| ClickHouse | 同上 + CH 特有函数名 |
| Generic | 空（无内置函数可运） |

---

## 测试

```bash
cd SQL+AI/pushdown-framework
mvn clean test
```

```
Tests run: 555, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 测试覆盖

| 测试类 | 测试数 | 覆盖内容 |
|---|---|---|
| `ConnectorConformanceSuiteTest` | 365 | 属性测试（100 次随机谓词）+ 不变量 + guarantee + 合并 + 生命周期 + 回退 |
| `ExpressionIRTest` | 11 | 8 种表达式类型 + Domain + TupleDomain |
| `DefaultSqlDeparserTest` | 8 | SELECT/WHERE/GROUP BY/ORDER BY/LIMIT deparse |
| `ShippabilityCheckerTest` | 9 | volatility + 白名单 + 递归参数检查 |
| `PushdownCostModelTest` | 6 | EXACT vs CONSERVATIVE 成本 + shouldPush 决策 |
| `JdbcConnectorTest` | 8 | EXACT 下推 + 混合 conjunct + STABLE 钉入 |
| `JdbcPushdownE2ETest` | 4 | 端到端 SQL 生成验证 |
| `PushdownFallbackHandlerTest` | 6 | retry + 熔断 + 回退 |
| `DynamicFilterLifecycleTest` | 11 | 四态生命周期 + 降级 + ScanContext |
| 其他 | 127 | IR/SPI/统计/聚合/Join/TopN/安全 |

---

## 设计文档

完整设计文档（含 Oracle 架构评审 + v2.1 修复）见：`SQL+AI/下推框架设计方案.md`

实施计划见：`SQL+AI/docs/plans/2026-06-27-pushdown-framework.md`
