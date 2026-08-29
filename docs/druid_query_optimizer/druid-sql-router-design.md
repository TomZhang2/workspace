# 面向 Apache Druid 的 SQL 感知查询路由引擎设计

> **问题定义**:你做一个查询引擎/网关,Druid 是下游。输入只有一条 SQL。要求:对每条 SQL 自动选择"当前这条 SQL 最合适的优化手段"——端点(sync `/druid/v2/sql` vs async `/druid/v2/sql/statements`)、引擎(native vs `msq-dart`)、query context 注入(`timeout`/`priority`/`vectorize`/`useApproximateTopN`/…)、以及必要时的 SQL 改写。
> 
> 本文是《Apache Druid 查询调优深度洞察报告》(`druid-query-tuning-insights.md`)的工程落地篇:把那本"参数手册"变成一台"自动决策机"。所有 Druid 侧事实均与 35.0.1 源码核对。

---

## 0. 核心思路(一句话)

**不要猜,要分层探测**:SQL 文本 → 解析出特征(廉价、可能不准)→ 用集群元数据补全扫描量(廉价、较准)→ 必要时用 `EXPLAIN PLAN` 让 Druid 自己告诉你它打算怎么跑(廉价、最准)→ 规则决策 + 历史反馈校准 → 执行 → 捕获真实代价回填历史。决策永远可以缺省:**不确定就只加 `timeout` + `sqlQueryId`,其余全默认**。

三个"免费午餐"(不执行用户查询就能拿到的信号):

1. **`sys.segments`**:`num_rows`/`size`/`start`/`end` 按 datasource + 时间区间聚合 = 扫描量估算(Broker 后台 SegmentMetadata 查询维护,不是现算);
2. **`EXPLAIN PLAN FOR`**:只规划不执行(源码 `QueryHandler.planExplanation` 返回预计算单行),`PLAN` 列就是 planner 翻译好的 native 查询 JSON——`queryType`(scan/timeseries/topN/groupBy)、归一化后的 `intervals`、虚拟列、join/子查询结构,全在里面;
3. **执行后响应头 `X-Druid-Response-Context`**:`NUM_SCANNED_ROWS`、`QUERY_TOTAL_BYTES_GATHERED`、`CPU_CONSUMED_NANOS` 等真实代价 → 反馈闭环的原料。

---

## 1. 总体架构:六阶段流水线

```
SQL ──► [S1 解析与特征抽取] ──► [S2 元数据富化] ──► [S3 EXPLAIN 探针(条件触发)]
                                                                    │
        ┌───────────────────────────────────────────────────────────┘
        ▼
      [S4 决策引擎:规则表 + digest 历史] ──► 决策对象{endpoint, engine, context, rewrites, fallback}
        │
        ▼
      [S5 执行与协议处理] ──► [S6 反馈捕获] ──► 历史表(digest → 代价画像) ─┐
        ▲                                                                    │
        └──────────────── 降级/重试 ◄────────────────────────────────────────┘
```

旁路系统:**元数据缓存**(S2 用)、**环境探测**(启动时)、**hint 通道**(注释覆写,最高优先级)。

---

## 2. Stage 1 — 解析与特征抽取

### 2.1 解析器选型

| 方案                        | 语言     | 评价                                                                                        |
| ------------------------- | ------ | ----------------------------------------------------------------------------------------- |
| **sqlglot**               | Python | **首选**(若你是 Python 栈):零依赖、AST 完整、30+ 方言且**自带 Druid 方言**(社区级支持)、可做 transpile/优化/血缘;dbt 生态在用 |
| **Apache Calcite parser** | Java   | 最重也最准:Druid 自己就用 Calcite,解析语义和下游 planner 100% 对齐;适合 Java 栈且追求"网关与 Broker 对 SQL 的理解一致"     |
| **JSqlParser**            | Java   | 轻量 Java 解析器,方言中立,特征抽取够用;复杂 Druid 方言函数(TIME_FLOOR 等)当普通函数处理即可                              |
| 正则/字符串                    | 任意     | **只许用于两处**:hint 注释提取、最粗的语句类型判别(首个关键字)。拿正则解析 SQL 结构是这类系统最常见的翻车点                            |

### 2.2 特征向量(抽取目标)

```
feat = {
  # --- 语句层 ---
  stmt_type:       SELECT | INSERT | REPLACE | EXPLAIN | SET(透传)
  has_group_by, has_order_by, has_having, has_distinct,
  has_window,      # 窗口函数
  limit, offset,   # OFFSET 存在性单独记(深翻页惩罚)
  # --- FROM 结构 ---
  tables:          [ (schema, name) ... ],     # 含子查询/CTE 中的表
  join_count, join_right_types: [table|subquery|lookup...],
  has_subquery, subquery_depth, has_cte,
  union_all:       none | top_level | table_level,
  # --- 聚合层 ---
  aggs:            [ (fn, arg) ... ],          # COUNT(*), SUM(x), COUNT(DISTINCT x)...
  has_exact_distinct,                          # COUNT(DISTINCT ...) 出现
  group_by_cols, group_by_has_time_floor_only, # 仅 FLOOR(__time TO …) 桶
  single_dim_topn_shape,                       # 单维 GROUP BY + ORDER BY 聚合 + LIMIT
  # --- 过滤层 ---
  time_pred:       {op, start?, end?} | CURRENT_TIMESTAMP-relative | none | unparsable,
  in_lists:        [ (col, size, value_type) ],# IN 列表大小与值类型
  filter_ops:      [ '>=', 'LIKE', ... ],      # 不等式/LIKE/REGEX 占比(CPU 信号)
  # --- 输出层 ---
  selected_cols,   select_star,
  # --- 工程 ---
  hints:           {engine?, async?, priority?, ...},  # 注释 hint(S6 前解析)
  digest:          normalize(sql),             # 字面量脱敏(ProxySQL digest 模式)
}
```

抽取要点:

- **`time_pred` 尽力而为即可**——识别不了就标记 `unparsable`,交给 S3 的 EXPLAIN 兜底(planner 归一化的 `intervals` 比你手写解析可靠);
- **IN 列表的值类型**(数字 vs 字符串)必须抽:数字值打在字符串列上 = 已知的数量级退化(§5 决策表 R8);
- **digest 归一化**:去字面量、压空白、去注释(**保留 hint 注释**)——它是历史表与 EXPLAIN 缓存的 key。

### 2.3 Hint 协议(人工逃生通道)

业界通用做法(Trino Gateway 的 `-- higherlimit` 查询注释、Crunchproxy 的 `/* read */` 注释都是先例):自动决策允许被显式覆写,且**优先级最高**:

```sql
SELECT /*+ engine=msq-dart, async, timeout=600000 */ user, COUNT(*) FROM t GROUP BY 1;
```

没有 hint 的流量走全自动;hint 只做白名单校验(可设的 key 集合固定),防注入。

---

## 3. Stage 2 — 元数据富化(不执行查询的成本估算)

### 3.1 扫描量估算(核心公式)

`sys.segments` 每行一个 segment,带 `start`/`end`/`num_rows`/`size`/`is_realtime`/`is_available`。Druid 按 segment 时间区间与查询 intervals 重叠来剪枝,所以:

```sql
-- 网关后台缓存(每 datasource 定期刷新,不要每请求现查)
SELECT
  COUNT(*)                    AS seg_cnt,
  SUM("size")                 AS scan_bytes_upper,   -- 与区间重叠的 segment 总大小(上界)
  SUM("num_rows")             AS scan_rows_upper,    -- 总行数(上界)
  SUM("num_rows" * is_realtime) / GREATEST(SUM("num_rows"),1) AS realtime_share
FROM sys.segments
WHERE datasource = :ds AND is_active = 1
  AND "end"   > :q_start        -- 查询区间起点
  AND "start" < :q_end          -- 查询区间终点
```

行级时间过滤后真实扫描量 << 该上界(segment 是粗粒度),但对**量级判断**(1M 行还是 1B 行)完全够用——决策只需要量级。精度可随 digest 历史积累自动校准(§7)。

注意:

- `num_rows` 由 Broker 后台维护,可能为 0(未知)或滞后(实时段)——估算式要容忍 0;
- 该查询本身走 `/druid/v2/sql`(sys 表是 BINDABLE convention,**只有 native 引擎能查**,msq-task/msq-dart 查不了);
- 权限:sys.segments 按行做 DATASOURCE READ 过滤——网关账号要有各 datasource 读权限,否则估算偏小(可用 Coordinator REST `/druid/coordinator/v1/metadata/segments` 走运维通道替代)。

### 3.2 Join 代价信号(INFORMATION_SCHEMA)

```sql
SELECT TABLE_NAME, IS_JOINABLE, IS_BROADCAST
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA IN ('druid','lookup') AND TABLE_NAME IN (:tables)
```

- 右侧 `IS_JOINABLE='YES'`(lookup)→ broadcast join 便宜,无需干预;
- 右侧是普通表/子查询 → planner 会物化右侧为 inline(subquery 路径)→ join_count≥1 且右表非 lookup 时,打上 `expensive_join` 信号(→ DART 候选,§5 R5);
- `IS_BROADCAST='YES'` 的 datasource(broadcast load rule)也便宜。

### 3.3 集群画像

`sys.servers`(`server_type='HISTORICAL'`,`curr_size`/`max_size`/`tier`;注意**列名是 `curr_size`,官方文档误写 `current_size`**,以代码为准)→ Historical 数量与各 tier 容量。用途:

- `maxQueuedBytes` 经验值 ≈ 2MiB × Historical 数;
- DART `targetPartitionsPerWorker` = 目标 Historical 的 `druid.processing.numThreads`(探测不到就用 CPU 核数近似);
- tier 结构提示"热/冷分层"是否存在(→ 是否敢把老区间查询路由到冷 tier Broker 组)。

全部元数据(S1~S3)**缓存 + TTL 刷新**(segments 30~60s、servers 5min、INFORMATION_SCHEMA 5min),估算请求零额外开销。

---

## 4. Stage 3 — EXPLAIN 探针(条件触发)

### 4.1 为什么值得

`EXPLAIN PLAN FOR <原SQL>` 发到 `/druid/v2/sql`,返回三列(均 VARCHAR):`PLAN`、`RESOURCES`、`ATTRIBUTES`。**只规划、不执行**(源码确认:结果行是预计算的单行;官方文档明示 "the query will not actually be executed")。`PLAN` 默认就是 native 查询 JSON(`useNativeQueryExplain` 默认 true),你能拿到:

| 信号                         | 位置                            | 决策用途                                                      |
| -------------------------- | ----------------------------- | --------------------------------------------------------- |
| `queryType`                | 每个 native query 的 `queryType` | 确认 planner 选择了 scan/timeseries/topN/groupBy → 校准 S1 的形状判定 |
| `intervals`                | native query 顶层               | **planner 归一化的时间区间**(覆盖 S1 解析不了的所有写法)→ 精确喂给 S2 的扫描量估算     |
| `dataSource.type`          | `table` / `join` / `query`    | `query`=子查询被物化;`join`=join 结构,左右一目了然                      |
| `virtualColumns`           | 表达式列                          | 复杂表达式 → CPU 型查询信号                                         |
| 多个 native query            | PLAN 是数组                      | UNION ALL / 多阶段(每个独立决策)                                   |
| `ATTRIBUTES.statementType` | SELECT/INSERT/REPLACE         | 非SELECT直接分流                                               |

成本:一次额外规划往返。`sqlQuery/planningTimeMs` 指标(Broker 侧)可以观测它;一般 ms~几十 ms,复杂 SQL 可能上百 ms。

### 4.2 触发策略(别无脑全量探)

| 条件                                                 | 是否 EXPLAIN    |
| -------------------------------------------------- | ------------- |
| digest 命中历史表(近期同型查询)                               | **否**(历史已够准)  |
| S1 形状简单 + S2 估算明确小(如 scan_rows < 100 万)            | **否**(不值得多一跳) |
| S2 显示大扫描(阈值可配,如 > 5000 万行)或 `time_pred=unparsable` | **是**         |
| join/子查询/窗口/多层嵌套等复杂形状                              | **是**         |
| 高 QPS 低价值流量                                        | 否(探针成本占比过高)   |

EXPLAIN 结果按 **digest + 参数形状** 缓存(规划结果对字面量不敏感,intervals 会变——缓存 key 用归一化形状,值里保留 intervals 提取逻辑重算)。

---

## 5. Stage 4 — 决策引擎(核心决策表)

输入:`feat`(S1)+ `est`(S2)+ `explain`(S3,可选)+ `history[digest]`(S6)。输出决策对象。规则按序评估,**首条命中即出**(顺序即优先级),全部未命中 → 缺省决策(只加 `timeout`+`sqlQueryId`)。

### 5.1 决策总表

| #   | 触发特征                                                                                 | 判定                 | 决策(endpoint / engine / context / 改写)                                                                                                                                                                                                                   |
| --- | ------------------------------------------------------------------------------------ | ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| R0  | hint 存在                                                                              | 人工指定               | 按 hint 白名单覆写,跳过自动决策(仍做安全校验)                                                                                                                                                                                                                            |
| R1  | `stmt_type ≠ SELECT`(且非 SET 透传)                                                      | 写/元数据              | INSERT/REPLACE → 原样转发 `/druid/v2/sql`(或按你产品定位拒绝);EXPLAIN/DESCRIBE → 透传                                                                                                                                                                                 |
| R2  | `has_group_by=false` 且 `limit` 小且 est.scan_rows 中等                                   | **小结果集明细**         | sync `/druid/v2/sql`;`resultFormat=array` + `header=true`;普通 `timeout`                                                                                                                                                                                 |
| R3  | `has_group_by=false` 且(无 limit 或 est.result_rows 大)                                  | **导出/拉取型**         | 行数 < ~10 万:sync + `arrayLines`/`csv` 流式;≥ ~10 万或用户标记慢:**async `/druid/v2/sql/statements` + `executionMode=async` + `selectDestination=durableStorage`**;`populateCache:false`;`priority` 调低;若客户端用 JDBC → avatica-protobuf 提示 + `maxRowsPerFrame` 服务端配合 |
| R4  | `single_dim_topn_shape`(单维+ORDER BY聚合+LIMIT)                                         | **TopN 型**         | 默认已是近似 topN,**不动**;要求精确(policy/hint)→ `useApproximateTopN:false`;历史显示结果可疑 → `minTopNThreshold` 上调(如 5000)                                                                                                                                              |
| R5  | `has_exact_distinct` 或 高基数 GROUP BY(est 大 or 历史慢)或 `expensive_join`(join 右侧非 lookup) | **重计算型 → DART 候选** | 见 5.2 引擎细则:可行则 `engine=msq-dart` + `targetPartitionsPerWorker=<numThreads>`;join 且左表含实时数据 → 追加 `sqlJoinAlgorithm=sortMerge`                                                                                                                            |
| R6  | 聚合查询 + 向量化形状(聚合器在向量化集合内、无多值分组列、table 数据源、无 `descending` timeseries)且 est.scan_rows 大 | **向量化收益型**         | 不干预(`vectorize` 默认 true);仅当历史显示 CPU 异常时排查回退原因(§7 的 vectorized 维度);**绝不**注入 `force`                                                                                                                                                                     |
| R7  | `has_subquery`                                                                       | **子查询风险型**         | EXPLAIN 确认物化行数级;可自动改写:外层若为聚合 → 保持(可能流式);`WHERE x IN (SELECT…)` → 知道它就是 inner join,并入 R5 判断;不可改写且历史 OOM → 拒绝并提示,或(有额度时)`maxSubqueryRows` 上调但**不得超集群上限**                                                                                                 |
| R8  | `in_lists` 中存在(字符串列 × 数字值)且 size 大                                                   | **IN 退化型**         | 自动改写为字符串字面量(`WHERE s IN ('123','456')`);超大 IN(>1000)→ 转临时表/JOIN 提示;集群建议配 `maxNumericInFilters=100` 兜底                                                                                                                                                  |
| R9  | `offset` 深翻页(如 > 10000)                                                              | **深翻页型**           | 拒绝或改写为时间游标(`WHERE __time > :last_seen ORDER BY __time LIMIT n`);说明 OFFSET 迫使 scan/groupBy 且跳过行仍要生成                                                                                                                                                     |
| R10 | 交互面板流量(来源标记/短聚合/高频重复 digest)                                                         | **面板型**            | `timeout` 收紧(如 10s)、`priority` 上调、`lane=interactive`、缓存默认开(`useCache/populateCache` true);命中 `__time MIN/MAX` 形状 → `enableTimeBoundaryPlanning:true`                                                                                                   |
| R11 | est.scan_rows 极大(如 > 5 亿)且为聚合                                                        | **超重查询**           | 强烈倾向 async(DART 或 native 皆可,按 R5);`maxQueuedBytes` 按集群规模注入;`maxScatterGatherBytes` 设熔断上限;超时预算放宽                                                                                                                                                        |
| R12 | `has_window` / `has_cte` / 复杂形状但 est 小                                               | 中性                 | 缺省决策,只加 `timeout`+`sqlQueryId`                                                                                                                                                                                                                         |
| —   | 全不命中                                                                                 | **缺省**             | sync + native 引擎 + `timeout`(全局默认)+ `sqlQueryId`(网关生成)。**不确定就什么都不动**                                                                                                                                                                                   |

### 5.2 引擎选择细则(native vs `msq-dart`)

DART 检查清单(全过才可选,来自官方已知限制):

1. ✅ 端点是 HTTP `/druid/v2/sql`(engine 参数经 JDBC 会被**忽略**——JDBC 流量直接排除);
2. ✅ 集群已 `druid.msq.dart.enabled=true`(启动时 `GET /druid/v2/sql/engines` 探测,未启用则此规则整体禁用);
3. ✅ 语句是 SELECT;
4. ✅ **不依赖**:查询缓存收益(重查询缓存本来命中率低)、优先级/laning(DART 不支持)、近似 TopN(DART 恒精确——反而符合 R4 精确需求);
5. ⚠️ 实时数据:右表/复杂类型有已知 issue(实时+复杂类型 NPE、实时+广播 join 需 sortMerge)→ `realtime_share` 高时降级用 native;
6. ⚠️ 轻查询别用(设计者基准:轻查询 native 快约 2 倍,25 并发点查 native 1529 QPS vs DART 730)——R5 只在"重"信号(大扫描/精确 distinct/大 join/历史慢)时触发。

注入参数:`engine=msq-dart` + `targetPartitionsPerWorker=<Historical numThreads>`(拉满单节点并行度,这是 DART 最重要的调优项)+ 可选 `maxConcurrentStages`。

### 5.3 端点选择细则

- **默认 sync**(`/druid/v2/sql`):无状态、流式、低延迟;它也是 DART 的入口;
- **async**(`/druid/v2/sql/statements`,`executionMode=async`):结果 > ~3000 行官方即建议 `selectDestination=durableStorage`;断连不丢、分页拉取、能查深存储冷数据;代价:轮询延迟、需 MSQ 扩展;
- 决策信号:est.result_rows(R3/R11)、历史超时率(digest 级)、客户端类型(长连接保持成本)。

### 5.4 Context 注入清单(按规则携带,非全量)

常用:`timeout`、`priority`、`lane`、`sqlQueryId`、`useCache/populateCache`、`maxQueuedBytes`、`maxScatterGatherBytes`、`maxSubqueryRows`、`useApproximateTopN`、`minTopNThreshold`、`enableTimeBoundaryPlanning`、`engine`、`targetPartitionsPerWorker`、`sqlJoinAlgorithm`、`selectDestination`、`executionMode`。参数语义/默认值见调优报告 §8。

### 5.5 语义安全红线(自动决策**禁止**做的事)

| 禁止项                                                                                                     | 原因                                             |
| ------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| 为提速把 `useApproximateTopN:false→true`、`useApproximateCountDistinct:false→true`、`forceLimitPushDown:true` | **改变结果正确性**。近似换速度只能由用户/policy 显式选择(hint 或全局策略) |
| 注入 `vectorize=force`                                                                                    | 实时 segment 永不可向量化,生产必炸                         |
| 超过集群上限调 `maxSubqueryRows`                                                                               | 上限是保护 Broker 的,绕过=OOM 风险                       |
| 静默改写语义相关子句(去 DISTINCT、改 ORDER BY)                                                                       | 同第一条                                           |
| 对 JDBC 流量设 `engine`                                                                                     | 被忽略(行为不确定),还掩盖决策失真                             |

允许的自动改写(语义不变):IN 数字→字符串引号化(R8)、OFFSET→游标(R9,需客户端配合)、外层聚合包裹(R7)、注释/空白归一化。

---

## 6. Stage 5 — 执行与协议处理

- **透传而非重组**:除决策注入的 context/格式字段外,原样转发用户请求体(保留用户自带 context——**用户显式 context 优先于网关默认,网关只补缺、只覆写自己负责的 key**;用户 SET 语句优先级天然最高,不用管);
- **`sqlQueryId` 由网关生成**(可追溯、可取消),并向上游客户端回传 `X-Druid-SQL-Query-Id`;
- **流式代理**:`*Lines`/`csv` 格式边收边转发;**实现截断检测**(结尾无换行=截断=报错);
- **取消传播**:客户端断开 → `DELETE /druid/v2/sql/{sqlQueryId}`(尽力而为语义);
- async 路径:提交后返回 `queryId` + 轮询句柄,分页拉结果(`page` 参数逐页转发,`filename` 透传)。

---

## 7. Stage 6 — 反馈闭环(digest 历史 + 阈值学习)

### 7.1 捕获(每次执行后)

| 信号                                | 来源                                               | 备注                                                                   |
| --------------------------------- | ------------------------------------------------ | -------------------------------------------------------------------- |
| 真实扫描行数 `NUM_SCANNED_ROWS`         | 响应头 `X-Druid-Response-Context`(native 查询响应上下文透传) | 校准 S2 估算偏差                                                           |
| 回收字节 `QUERY_TOTAL_BYTES_GATHERED` | 同上                                               | maxQueuedBytes/maxScatterGatherBytes 校准                              |
| CPU `CPU_CONSUMED_NANOS`          | 同上                                               | 向量化收益归因                                                              |
| 耗时/字节数/状态码                        | 网关自身计时                                           | 基础画像                                                                 |
| `sqlQuery/planningTimeMs`         | Druid 指标(若可接)                                    | EXPLAIN 探针成本监控                                                       |
| 向量化占比                             | `query/segment/time` 指标的 `vectorized` 维度         | R6 归因                                                                |
| 错误类型                              | 响应体 error code                                   | Resource limit exceeded / timeout / Cannot vectorize / DART fault 分类 |

### 7.2 历史表与学习

```
history[digest] = {
  n, p50_ms, p80_ms, p95_ms,          # 耗时分布(Dremio WLM 用 p50/p80 做阈值,成熟先例)
  avg_scanned_rows, scan_estimate_bias, # 真实/估算 比 → 修正 S2
  timeout_rate, oom_rate, engine_stats: {native: …, msq-dart: …},
  last_seen, sample_query
}
```

用法:

- **决策校准**:R5 的"重"阈值、R3/R11 的行数阈值,从拍脑袋值 → 按本集群 p50/p80 自适应;
- **A/B 引擎**:同 digest 在 native/DART 都有样本时,直接比较分布(轻量版 Bao 思路——per-query 学习型选择);
- **估算修正**:`scan_estimate_bias` 按 datasource×时间桶形状回归,修 S2 的 segment 粒度误差;
- **EXPLAIN 缓存命中率**与探针成本监控,动态调 §4.2 触发条件。

冷启动(无历史):纯规则表已可用(上界估算足够做量级决策),历史只让它更准。

---

## 8. 降级与容错

| 故障                                            | 动作                                                                         |
| --------------------------------------------- | -------------------------------------------------------------------------- |
| `engine=msq-dart` 查询失败(任何 DART fault/未启用/NPE) | 同一 `sqlQueryId` 降级 native 重试(幂等:SELECT 只读,安全);记录 DART 失败计数,超阈值自动暂停 R5 一段时间 |
| sync 超时(网关侧预算)                                | 可配策略:fail-fast 返回;或转 async 重提(用户可见性要处理好)                                   |
| async 提交失败 / MSQ 不可用                          | 降 sync 流式(行数允许时)                                                           |
| `Resource limit exceeded`(子查询)                | 不盲目重试;返回结构化错误 + 改写建议(外层聚合化/拆查询),附 digest 历史证据                              |
| EXPLAIN 探针失败/超慢                               | 跳过探针,用 S1+S2 决策(规则表本就允许无 EXPLAIN)                                          |
| 元数据缓存过期(sys 查询失败)                             | 用 stale 值 + 标记置信度下降;估算类规则(阈值判断)自动放宽一档                                      |
| Druid 集群整体熔断                                  | 网关侧限流/排队(可复用 lane 思路)                                                      |

**重试总预算**:最多 1 次降级重试,总时长受客户端超时约束;所有降级在响应头/日志中留痕(`X-Router-Decision: dart→native,reason=…`)。

---

## 9. 环境探测(启动 + 定期)

```
GET /druid/v2/sql/engines            → 引擎清单(是否有 msq-dart/msq-task)
GET /druid/v2/sql/statements/enabled → async 可用性
SELECT … FROM sys.servers            → 集群画像(§3.3)
SELECT … FROM sys.segments           → 元数据缓存初始化
Druid 版本(RESPONSE header 或 /status)→ 功能开关矩阵(如 DART 31 引入、engine 参数统一前的旧端点等,见调优报告 §10.5)
```

版本兼容原则:**能力探测优于版本判断**——有端点就探测,没有就保守缺省。

---

## 10. 业界参照(都有先例,不是发明轮子)

| 模式                                                    | 来源                                                                                | 借鉴点                                                  |
| ----------------------------------------------------- | --------------------------------------------------------------------------------- | ---------------------------------------------------- |
| 网关 + 规则路由(MVEL 规则、按 header/user/tag)                  | Trino Gateway(前 Lyft presto-gateway)                                              | 规则引擎形态;**只做语法级分析,不跑 EXPLAIN**——探针成本的另一种取舍            |
| 查询注释 hint 覆写路由                                        | Trino Gateway 社区实践(`-- higherlimit`)、Crunchproxy(`/* read */`)                    | §2.3 hint 协议                                         |
| SQL digest 归一化 + 工作负载画像(sum_time/count_star 聚合)→ 定向路由 | ProxySQL(query digest 系统)                                                         | §7 digest 历史表                                        |
| p50/p80 阈值的工作负载管理规则                                   | Dremio WLM                                                                        | §7.2 自适应阈值                                           |
| per-query 学习型决策(拦截每条查询,模型选优化档)                        | Bao(SIGMOD'21,PostgreSQL hint 集选择)                                                | §7.2 A/B 引擎的进阶形态                                     |
| 查询形状→查询类型分派(scan/topN/groupBy/timeseries)             | Metabase 旧版 Druid 驱动(MBQL→native 分派,已下线)、**Druid planner 自身**(sql-translation 规则) | §5.1 R2~R6 的形状判定就是同一套思想,且我们用 EXPLAIN 直接读 planner 的判定 |
| 全量 SQL 解析→路由                                          | Vitess vtgate(sqlparser→vindex)                                                   | 重解析路线的工程标杆                                           |
| SQL→预计算替换(代价最优计划选择)                                   | Apache Kylin(cuboid 选择)、Dremio Reflections                                        | 未来方向:网关层物化视图/预聚合路由                                   |

---

## 11. 实施路线(建议)

**MVP(1~2 周)**

- S1:sqlglot(或 JSqlParser)特征抽取:语句类型/表/聚合/时间谓词/IN/hint/digest;
- S2:sys.segments 缓存 + 扫描量上界估算;
- S4:规则表子集——R1/R2/R3(sync vs async、流式格式)/R4/R8/R10(timeout、priority、populateCache);
- S6:轻量历史(digest → p50/p95/超时率),只做展示与阈值参考;
- 不做 EXPLAIN、不做 DART、不做改写。

**V2(+2~4 周)**

- S3:EXPLAIN 探针(条件触发 + 缓存)→ intervals 精确化 + queryType 校准;
- R5/R11:DART 决策 + 降级链;R7 子查询守卫;
- 反馈闭环接入 `X-Druid-Response-Context`,估算偏差自校准。

**V3(演进)**

- 学习型决策(Bao 式:同 digest 多策略对比择优);
- 预聚合/物化视图路由(Kylin/Dremio 式);
- 多集群/多租户路由( lanes、Broker 组、租户配额)。

---

## 附:一次请求的完整决策示例

```sql
-- 输入(来自 BI 导出按钮)
SELECT user, page, COUNT(*), SUM(added)
FROM wikipedia
WHERE __time >= TIMESTAMP '2025-08-01 00:00:00'
  AND channel IN (#en.wikipedia, #fr.wikipedia, … 800 个值 …)
  AND page_id IN (123, 456, 789)          -- page_id 是字符串列!
GROUP BY 1, 2
ORDER BY 3 DESC
LIMIT 100
```

流水线:

1. **S1**:SELECT;两表列;GROUP BY 2 维(非单维 topN 形状,ORDER BY 聚合+LIMIT 但维度=2 → R4 不命中);`time_pred` 可解析(2025-08-01 起,开放区间→上界=now);IN(channel, 800, string) + IN(page_id, 3, **number-on-string**)→ R8 信号;无子查询;digest=…;

2. **S2**:sys.segments 区间求和 → scan_rows_upper ≈ 4200 万,realtime_share=3% → "中大扫描"信号;INFORMATION_SCHEMA:单表,无 join;

3. **S3**:est > 5000 万?否(4200 万 < 阈值)→ 跳过 EXPLAIN;

4. **S4**:R8 命中(page_id 数字→字符串,**语义不变改写**,自动执行);R11?否;R12 → 缺省 + 大扫描修饰;最终决策:
   
   ```json
   {
     "endpoint": "/druid/v2/sql", "engine": "native",
     "context": { "sqlQueryId": "gw-…", "timeout": 60000, "priority": 0 },
     "resultFormat": "array", "header": true,
     "rewrites": ["IN(123,456,789) → IN('123','456','789')"],
     "fallback": null
   }
   ```

5. **S5**:转发(用户若有自带 context,合并时用户优先);

6. **S6**:响应头读 `NUM_SCANNED_ROWS`(如 3.1 亿段级估算 vs 实际 3800 万,记录 bias=0.09)→ digest 历史更新 → 下次该 datasource 估算自动乘以形状系数。

---

*本文与《Apache Druid 查询调优深度洞察报告》配套:那份手册回答"有哪些优化手段、各自什么语义";本文回答"面对一条具体 SQL,如何自动选出该用的那几个"。Druid 侧事实基于 35.0.1 源码;外部模式出处见 §10。*
