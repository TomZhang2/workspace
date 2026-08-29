# 方案调整:从"SQL 路由网关"到"C++ 联邦下推引擎"(Druid Connector)

> **前提变化**:你的引擎类 StarRocks——自带 SQL 解析、分析、校验、优化和 toSQL,C++ 实现。此前《druid-sql-router-design.md》假设的是一个"外置网关:拿别人写好的 SQL 文本做分类路由"。**这个假设不再成立,方案需要范式级调整。**
> 
> 三份前置文档:《druid-query-tuning-insights.md》(参数手册)、《druid-sql-router-design.md》(网关方案,本文的调整对象)。Druid 侧事实基于 35.0.1 源码核对。

---

## 0. 一页纸结论:改什么

| 维度         | 原网关方案                       | 调整后(联邦下推引擎)                                                                                              |
| ---------- | --------------------------- | -------------------------------------------------------------------------------------------------------- |
| **定位**     | SQL 文本分类器 + 路由器             | 优化器内嵌的 **Druid Convention/Connector**:自己规划,把计划切片下推                                                       |
| **解析**     | sqlglot/JSqlParser 解析用户 SQL | **删掉**。用你自己的 parser/planner,在 IR/计划上做决策                                                                  |
| **核心决策**   | 选端点/引擎/context              | **下推边界决策**(哪些算子下推 Druid、哪些拉回本地执行)+ 执行调优(context 注入)                                                      |
| **SQL 生成** | 只做受限改写(IN 引号化等)             | **全量 toSQL**:Druid 方言发射器,受能力矩阵约束,过校验器                                                                    |
| **数据获取**   | 转发结果给客户端                    | **结果摄取**进自己的执行管线(类型化行流,可选 Smile 二进制)                                                                     |
| **统计**     | sys.segments 扫描量粗估          | **CBO 适配器**:segmentMetadata 列级统计(基数/min/max/size)+ sys.segments,喂你自己的优化器                                 |
| **保留**     | —                           | context 注入决策表(timeout/priority/engine=DART)、异步 statements 大结果通道、元数据缓存、反馈闭环,**全部保留**,只是触发点从"请求入口"移到"计划节点" |

一句话:**原方案是"请求代理",新方案是"计算下推"**——参照物从 Trino Gateway 换成 DuckDB postgres_scanner + StarRocks JDBC Catalog + Calcite Convention。

---

## 1. 为什么是范式转变(先例与教训)

### 1.1 DuckDB postgres_scanner —— C++ SQL 发射下推的标杆

- **架构**:table function 拿到优化器下推下来的 filters(projection pushdown 天然获得 column_ids),C++ 代码把 filter 树翻译成 SQL `WHERE` 子句(`PostgresFilterPushdown::TransformFilter`),拼出 `SELECT <需要的列> FROM tbl WHERE …`,包进 `COPY (…) TO STDOUT (FORMAT BINARY)` 用二进制协议流式摄取(`postgres_binary_reader` 解析);
- **下推范围克制**:只做 **filter + projection 下推;聚合、join、排序全部拉回本地执行**。这是有意为之——跨引擎语义等价性验证成本高,先保证正确性;
- **调试通道**:`pg_debug_show_queries` 开关打印实际生成的 SQL——你的发射器必须内建等价物;
- **工程细节**:标识符全量转义、并行连接 + 预取(prefetch)、类型系统逐字段映射。

**借鉴**:SQL 发射器放在 scan 节点的 bind 阶段;filter→SQL 是最安全、收益最稳的第一层下推;二进制协议摄取值得做。

### 1.2 StarRocks JDBC Catalog —— 你这个体量的直接参照

- 外部表通过 catalog 接入,优化器(Nereids)里有专门的下推规则组:`PushDownPredicateJDBCScanRule`、`PushDownLimitJDBCScanRule`、`PushDownAggJDBCScanRule`(JDBCScanNode 生成带 WHERE/LIMIT/聚合的完整 SQL 下推到外部库);
- 聚合采用**两阶段**思路:可下推的部分聚合/合并下推,不可下推的本地收尾;
- 关键点:**下推决策是优化器规则,不是请求入口的 if-else**——规则作用于计划模式(plan pattern),由代价模型仲裁。

**借鉴**:把"下推边界"实现为一组 optimizer rule + 一个 DruidScan 节点(而不是网关的规则表);聚合可下推但要核对聚合函数语义。

### 1.3 Trino Druid Connector —— 反面教材

- 旧 Trino Druid connector 建立在 Druid **native select 查询**之上(select 早已废弃),只做时间区间谓词下推、不做聚合下推,最终随 select 一起被废弃移除;
- **教训**:① connector 不要锚定在 Druid 的边缘 API 上——锚定 **SQL HTTP 接口 /druid/v2/sql + scan**(它们是稳定一等公民);② 只做 scan 级下推、聚合全部拉回,等于放弃了 Druid 最大的价值(分布式聚合)——所以你必须做聚合下推,但要带着语义意识(见 §4)。

### 1.4 Apache Calcite —— 理论模型(且自带 Druid adapter)

- **Convention 模型**:每个物理算子携带 convention trait(本地 Enumerable vs 远程 JDBC/Druid),优化器通过 converter rule 和下推规则在"整段远程执行"与"拉回本地执行"之间做代价仲裁——这正是你要实现的东西的教科书表述;
- Calcite 自己有 **Druid adapter**(`org.apache.calcite.adapter.druid`:DruidQuery/DruidFilterRule/DruidProjectRule/DruidAggregateRule/DruidSortRule),把逻辑算子逐个尝试折叠进 DruidQuery 节点,折不进去就在本地执行——**下推边界 = 能折叠的上界**,这个模式直接抄;
- 注意:Lattice 是物化视图推荐,与联邦无关,别被名字带偏。

---

## 2. 新架构总览

```
用户 SQL
   │
   ▼
你的 Parser → Analyzer/Validator → Optimizer(CBO)
                                        │
                    ┌───────────────────┼────────────────────┐
                    │  新增:Druid 下推规则组(§5)              │
                    │  新增:DruidScan 远程计划节点(Convention)│
                    │  新增:代价模型接入 Druid 统计(§3)       │
                    └───────────────────┬────────────────────┘
                                        ▼
                          物理计划 = 本地算子树 + DruidScan 叶子
                                        │
                     ┌──────────────────┴──────────────────────┐
                     ▼                                         ▼
          DruidScan 执行器(§6)                       本地执行引擎
          ├─ toSQL 发射器(方言+能力矩阵)                (聚合收尾/join/
          ├─ 校验器(EXPLAIN 探针)                       窗口/排序/复杂表达式)
          ├─ context 注入器(沿用网关决策表)
          └─ 结果摄取(类型化行流,可选 Smile)
                     │
                     ▼
          Druid /druid/v2/sql(或 native scan /druid/v2)
```

原网关方案的 S1~S6 六阶段不再作为请求流水线存在,而是**溶解进优化器与执行器**:

| 原阶段           | 去向                                                            |
| ------------- | ------------------------------------------------------------- |
| S1 解析特征抽取     | 删除(直接用你的逻辑计划/IR;特征=计划模式)                                      |
| S2 元数据富化      | 升级为 **CBO 统计适配器**(§3),进代价模型                                   |
| S3 EXPLAIN 探针 | 保留并扩展:**发射产物验证** + 计划确认(§7)                                   |
| S4 决策表        | 拆两半:下推边界 → 优化器规则(§5);执行调优(context/引擎/端点)→ DruidScan 执行器(§6.3) |
| S5 执行协议       | 变为**结果摄取管线**(§6)                                              |
| S6 反馈闭环       | 保留:`X-Druid-Response-Context` 真实代价回填统计与代价系数                   |

---

## 3. 统计适配器(喂你的 CBO)

### 3.1 两个零扫描数据源

**① `sys.segments`(粗粒度、高频刷新)**

```sql
SELECT datasource, "start", "end", SUM("size") sz, SUM("num_rows") nr,
       SUM("num_rows" * is_realtime) / GREATEST(SUM("num_rows"),1) rt_share
FROM sys.segments WHERE is_active = 1 GROUP BY 1,2,3
```

- 用途:表级行数/字节基数(按时间桶)、实时占比;`num_rows` 是 Broker 缓存值(可能为 0 或滞后,要容错);
- 注意:实际列名 `curr_size`/`num_rows` 以代码为准(文档里 `current_size` 是笔误);sys 表只有 native 引擎能查。

**② segmentMetadata native 查询(列级统计,低频刷新)**

`POST /druid/v2`(`queryType: segmentMetadata`),关键参数:

```json
{
  "queryType": "segmentMetadata",
  "dataSource": "foo",
  "merge": true,
  "toInclude": {"type": "all"},
  "analysisTypes": ["cardinality", "interval", "minmax", "size"]
}
```

返回(merged 为每数据源一行;字段名与源码核对):

| 字段                                             | 说明                                               |
| ---------------------------------------------- | ------------------------------------------------ |
| `numRows`                                      | 行数(merge 时求和)——**不受 analysisTypes 控制,恒返回**       |
| `size`                                         | 估算字节数(注意是 flat 估算,非磁盘实际;磁盘实际用 sys.segments.size) |
| `columns.<col>.typeSignature` / `type`         | 列类型(`STRING/FLOAT/DOUBLE/LONG/COMPLEX<name>`)    |
| `columns.<col>.cardinality`                    | **仅 STRING 列**(来自字典,merge 后取各段最大值);其余为 null      |
| `columns.<col>.minValue/maxValue`              | **仅 STRING 列**(需 `minmax`)                       |
| `columns.<col>.hasMultipleValues` / `hasNulls` | 多值列/空值标记——**多值标记直接影响下推合法性**(§4.6)                |
| `columns.<col>.size`                           | 列级估算(需显式加 `size`;默认 analysisTypes 不含它)           |
| `errorMessage`                                 | 非 null 时该列其他字段不可信                                |

默认 `analysisTypes = ["cardinality","interval","minmax"]`(可被 `druid.query.segmentMetadata.defaultAnalysisTypes` 覆盖)——**想要列 size 必须显式传**。`mergeSourceLevel`/`usingSegmentCacheManager` 在 35.0.1 已不存在,别发。老参数 `lenientAggregatorMerge` 已废弃,用 `aggregatorMergeStrategy`。

### 3.2 接入代价模型

- 表基数:merged `numRows`(或 sys.segments 按区间聚合);
- 选择率:只有 **STRING 列**有 cardinality/min/max——等值选择率 ≈ 1/cardinality,范围用 min/max 插值;数值列无 min/max,回退默认选择率(如 1/3、1/10 分级)或用 `X-Druid-Response-Context` 的 `NUM_SCANNED_ROWS` 历史校准;
- **没有直方图**(Druid 不提供)——别幻想精确选择率,Druid 自己的 planner 也不用行数统计(`DruidTable.getStatistic()` 返回 UNKNOWN)。你的估算精度上限就是"基数×区间剪枝",反馈闭环是唯一精度来源;
- 网络代价:预估返回行数(下推聚合后的基数)× 平均行宽(列 size / numRows);
- 刷新策略:sys.segments 30–60s;segmentMetadata 5–15min(它本身要跑后台元数据查询,别高频打);EXPLAIN/执行反馈实时回填。

---

## 4. 下推能力矩阵(校验器规则 + 发射器白名单)

> 你的校验器(validate 阶段 + 下推规则内部)用这张表裁决"这个计划切片能否整体下推";发射器(tosql)只允许生成白名单构造。全部条目与 35.0.1 文档/源码核对。

### 4.1 JOIN(最危险区)

| 规则           | 内容                                                                                                                                                                                  |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ✅ 类型白名单      | **只发射 INNER / LEFT**。RIGHT/FULL OUTER **语法能过 planner 但结果不保证正确**(官方明示"not fully implemented")——校验器必须按"语义不支持"拒绝,而不是语法拒绝                                                               |
| ✅ 条件形态       | 仅**等值**,两侧表达式类型一致;允许一侧是函数(`LOWER(t1.x)=t2.x`);操作符 `=` 或 `IS NOT DISTINCT FROM`(null 匹配语义不同,注意区分)                                                                                    |
| ❌ 条件黑名单      | 条件含**常量**、join 列是**多值列**、非等值条件——都不支持                                                                                                                                                |
| ⚠️ 隐式子查询     | 右侧是**普通表**时,planner 自动包一层子查询物化(右表数据全量拉 Broker);右侧是 lookup 才是真正高效的 broadcast join。用 `INFORMATION_SCHEMA.TABLES.IS_JOINABLE` 判定                                                       |
| ⚠️ 无 join 重排 | Druid 不重排 join、不做谓词穿越 join 的下推——join 下的子查询是性能杀手                                                                                                                                     |
| 推论           | **两表 Druid join 只有"右表是 lookup"或"小表可物化"才值得整体下推**;其余情况(两 Druid 大表 join)考虑:① 下推两侧各自聚合后的子查询,本地 join(两阶段);② 或注入 `engine=msq-dart`(DART 的 sort-merge join 正是干这个的);③ 或本地 join(拉明细)——代价模型裁决 |

### 4.2 聚合(收益最大区,但语义陷阱集中)

| 规则                          | 内容                                                                                                                                                                                                       |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ✅ 可下推                       | `SUM/COUNT/MIN/MAX/AVG`(数值)、`GROUP BY` 任意列、`HAVING`;timeseries 形状(`FLOOR(__time TO …)` 单列分组)自动走最快引擎                                                                                                      |
| ⚠️ **COUNT(DISTINCT) 默认近似** | Druid SQL 的 `COUNT(DISTINCT x)` 默认翻译为 HLL 近似!想精确必须 `useApproximateCountDistinct=false`(代价:转精确 groupBy)。**下推含 DISTINCT 聚合前必须做产品决策**:① 接受近似 → 发射器原样下推并打标"近似语义";② 要精确 → 注入 context 或改为本地精确去重。**绝不允许静默改变语义** |
| ⚠️ 近似函数                     | `APPROX_COUNT_DISTINCT*` 恒近似;`DS_HLL/DS_THETA/DS_QUANTILES_SKETCH` 返回 **sketch 对象**(COMPLEX 类型)——除非你会解析 sketch,否则别把这类函数放进下推 SELECT                                                                       |
| ⚠️ TopN 形状                  | 单维 GROUP BY + ORDER BY 聚合 + LIMIT → 默认走**近似 TopN**。要么接受,要么 `useApproximateTopN=false`,要么 `minTopNThreshold` 调大。校验器遇到该形状必须走"近似语义检查"                                                                       |
| ✅ 两阶段                       | Druid partial-agg 本来就是分布式的,常规 GROUP BY 整体下推即可,无需人为两阶段;真正的两阶段场景是"下推两个子聚合 + 本地 join/合并"(§4.1 推论)                                                                                                           |

### 4.3 集合操作与子查询

- **只有 UNION ALL**(且仅顶层或表级两种受限形态);**INTERSECT/EXCEPT/UNION DISTINCT 不支持**——发射器永不生成,校验器拒绝;本地实现去重/交差;
- `WHERE x IN (SELECT …)` 会被 planner 改写成 inner join(§4.1 的推论适用);
- **标量子查询(SELECT 列表里的子查询)不支持**——文档无任何支持声明,校验器直接拒绝下推、拉回本地;
- 子查询物化上限 `maxSubqueryRows` 默认 100000:下推的 SQL 里若嵌子查询,EXPLAIN 确认物化规模,超限改写(外层聚合化)或拉回本地。

### 4.4 表达式与函数

- 函数清单用 `INFORMATION_SCHEMA.ROUTINES` 动态发现(`ROUTINE_NAME`/`IS_AGGREGATOR`/`SIGNATURES`),**启动时拉取并缓存**——比硬编码函数表更抗版本漂移;注意查 INFORMATION_SCHEMA 本身不能用 Druid 特有函数;
- 时间过滤**只发射白名单形态**:`__time >=/< TIMESTAMP`、`__time >= CURRENT_TIMESTAMP - INTERVAL …`、`FLOOR(__time TO unit) = TIMESTAMP …`、`TIME_IN_INTERVAL(__time, 'start/end')`(官方推荐的最好写法,区间左闭右开);其他形态(函数包 __time 的比较等)可能无法转成 intervals → 剪枝失效,校验器应降级处理或重写;
- 不等式/LIKE/REGEXP 过滤很贵(CPU 型)——可作为"下推不如拉回"的代价信号。

### 4.5 排序/分页/窗口

- **非聚合查询 ORDER BY 只支持 `__time`**——下推明细扫描时只可 `ORDER BY __time [DESC]`,其他排序本地做;
- `OFFSET` 只被 scan/groupBy 支持,且**迫使查询离开 topN/timeseries 引擎** + 跳过行仍要生成——发射器原则上不生成 OFFSET,分页用"时间游标 + LIMIT";
- 窗口函数:Druid 支持(OVER/PARTITION BY/ROWS|RANGE 帧),但**必须带 GROUP BY**(先聚合后开窗)、帧边界不能是表达式——校验器按此裁决,通常窗口算子拉回本地更简单。

### 4.6 类型与标识符(发射器硬规则)

| 规则    | 内容                                                                                                                                                                                     |
| ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 标识符   | **大小写敏感、无隐式转换**;发射器对**所有**标识符无条件 `"双引号"` 包裹(内嵌 `"` 双写转义)。这是 C++ 发射器最常见的 bug 源                                                                                                          |
| NULL  | 32.0+ 恒 ANSI 语义(null ≠ ''/0),三值逻辑;只有联邦到老集群(<28/32 且开了 legacy)才要考虑脏数据                                                                                                                   |
| 多值列   | `hasMultipleValues=true` 的列:GROUP BY 会**隐式 unnest**(行数>组数)、`x='a' AND x='b'` 可能被 planner 错误优化为 false、join 条件禁止。**校验器规则:下推切片里出现多值列(分组/join/复杂谓词)→ 拉回本地**,或先 `MV_TO_ARRAY` 转 ARRAY 语义再下推 |
| ARRAY | 一等公民(`ARRAY_CONTAINS` 等),但结果序列化默认是 JSON **字符串**(`sqlStringifyArrays=true`)——摄取时要么发 `sqlStringifyArrays=false` 拿真数组,要么按字符串解析                                                            |
| 类型映射  | 发射器需内置 SQL→Druid 映射:DECIMAL→DOUBLE(非定点!)、BOOLEAN→LONG、TINYINT..BIGINT→LONG、TIMESTAMP/DATE→LONG(millis)、REAL→DOUBLE;跨类型 join 条件两侧必须显式同型(否则 Druid 插隐式子查询)                                |
| 关键字   | Calcite 全集 + `CLUSTERED`/`PARTITIONED`,标识符转义规则已覆盖                                                                                                                                      |

---

## 5. 下推边界决策(优化器规则组)

### 5.1 规则组设计(Calcite/StarRocks 模式)

| 规则                       | 模式(match)                                      | 动作                                                                         |
| ------------------------ | ---------------------------------------------- | -------------------------------------------------------------------------- |
| `DruidFilterIntoScan`    | Filter over DruidScan                          | filter 谓词逐个过 §4.4 白名单 → 并入 scan 的 WHERE(可下推部分);不可下推谓词留在本地 Filter           |
| `DruidProjectIntoScan`   | Project over DruidScan                         | 列裁剪进 scan(projection pushdown,永远安全)                                        |
| `DruidAggregateIntoScan` | Aggregate over (Filter/Project) over DruidScan | §4.2 全过(聚合函数白名单、DISTINCT 语义决策、无多值分组列)→ 整体折叠成"远程聚合 scan";否则本地聚合 + 远程明细 scan |
| `DruidSortLimitIntoScan` | Sort/Limit over DruidScan/Aggregate            | 仅 `ORDER BY __time` 或聚合列排序 + LIMIT → 下推;其他本地                               |
| `DruidJoinDecision`      | Join whose 两子树全为 DruidScan                     | 三路决策(§4.1 推论):lookup/broadcast 型下推 / 两侧下推+本地 join / DART 上下文;由代价 + 能力矩阵裁决  |
| `DruidUnionPushdown`     | UnionAll over DruidScans                       | 表级 UNION ALL 形态下推,否则本地拼接                                                   |

### 5.2 代价仲裁(简化模型,够用)

```
下推成本 ≈ 远程扫描行数(§3 估算)×远程行宽×网络系数 + Druid 侧计算(可由历史校准)
拉回成本 ≈ 远程扫描行数×全行宽×网络系数 + 本地计算成本(你自己的 CBO 已有)
```

经验先验(冷启动,来自调优手册):

- filter+projection:几乎总是下推(网络缩减是纯赚);
- 聚合:下推后基数缩小 ≥10x 才显著,否则两可;
- 明细拉回:超过 ~100 万行/单查询要警惕(走异步通道或拒绝);
- 表达式 CPU 密集(不等式/LIKE/复杂函数):下推给 Druid(Historical 并行)通常优于拉回——除非表达式不在 Druid 函数白名单。

### 5.3 执行调优注入(DruidScan 执行器内,沿用网关决策表)

下推 SQL 确定后,执行器按网关方案的决策表注入 context(全量见调优报告 §8):

- 常规:`sqlQueryId`(网关生成)、`timeout`(默认预算);
- 重查询(大扫描/精确 distinct/大 join):`engine=msq-dart` + `targetPartitionsPerWorker=<numThreads>`(须过 DART 检查清单:非 JDBC、SELECT、集群已启用、实时占比低);
- 大结果集:`arrayLines` 流式或异步 statements + `selectDestination=durableStorage`;
- 语义决策产物:`useApproximateCountDistinct`/`useApproximateTopN`(只在用户侧明确接受近似时才允许默认值);
- 面板型流量:`priority`/`lane`/缓存开关。
- 降级链不变:DART 失败 → native 重试(SELECT 幂等),决策留痕。

---

## 6. 结果摄取管线(C++ 侧)

### 6.1 通道选择(与源码核对的结论)

| 通道                              | 协议                                                                       | 适用                                                                                        | 备注                                                                                                         |
| ------------------------------- | ------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| **A. SQL HTTP `/druid/v2/sql`** | JSON(`resultFormat=array/arrayLines`)                                    | **默认**。`header+typesHeader` 拿列类型,逐行流式解析                                                   | **无 Smile**——SQL 端点只产 JSON(源码确认);`arrayLines` 是 NDJSON 流式首选,结尾无换行=截断                                       |
| **B. native Scan `/druid/v2`**  | JSON 或 **Smile 二进制**(`Accept: application/x-jackson-smile`,由 Accept 头协商) | 高吞吐摄取。`resultFormat=compactedList` + Smile + `rowSignature` 类型自描述;`__time` 恒为 millis long | 发射器需要产 native JSON(或先 SQL EXPLAIN 拿到 native 查询再改 dataSource 为 scan?——更实际的做法:对"纯明细拉取"场景直接构造 native scan 查询) |
| **C. 直连 Historical scan**       | 同 B                                                                      | 超大导出:并行打多个 Historical,绕过 Broker                                                           | 排序受限;运维复杂,后期再做                                                                                             |

建议:**A 为主**(你的 toSQL 天然输出 SQL,协议简单、类型头齐全),B 作为性能档(需要 C++ Smile 解析器,jackson-smile 格式有成熟 spec,工作量可控),C 按需。

### 6.2 摄取细节

- 类型映射按 `typesHeader`(运行时类型 LONG/DOUBLE/FLOAT/STRING/ARRAY/COMPLEX<name>)+ §4.6 静态表双保险;`COMPLEX` 列(sketch 等)原则上不进下推 SELECT;
- `__time`:SQL 通道默认 ISO-8601 字符串(要 millis 可下发 `serializeDateTimeAsLong`,native 通道恒 millis);
- ARRAY 列:`sqlStringifyArrays=false` 拿真 JSON 数组,否则按字符串解析后自行 split;
- 流式消费 + 背压:你的执行管线本来就是拉取式(pull-based),Druid 的 HTTP 响应也是流式——天然匹配;实现截断检测(行格式结尾换行);
- 连接池与并发:并行下推多个子查询时注意 Druid 侧约束——`druid.broker.http.numConnections`(每 Broker→每 Historical 连接数即并发查询上限)、`druid.server.http.numThreads`、`druid.query.scheduler.*`;网关侧限流(每 Broker 的在途查询数 ≤ numConnections×0.8 之类)。

---

## 7. EXPLAIN 验证闭环(原 S3 的进化)

你自己的 toSQL 产出的是"你自以为 Druid 能跑"的 SQL。**发射 ≠ 可信**——用 EXPLAIN 做发射器自检:

1. **发射验证(开发期 + 灰度期)**:对每类下推模式,把发射 SQL 加 `EXPLAIN PLAN FOR` 发 `/druid/v2/sql`,断言 `PLAN` 里的 native 查询符合预期(`queryType`、`intervals` 非空且覆盖正确区间、无意外 `query` 型 dataSource=隐式子查询、无 `dataSource.type=join` 退化形态)——这是**发射器的单元测试/回归测试**,直接打真实 Druid;
2. **运行期抽查**:新形状/低频模式首次执行前 EXPLAIN 一次(按计划 digest 缓存,命中免查);EXPLAIN 只规划不执行,成本 ms 级;
3. **统计回填**:EXPLAIN 的 `RESOURCES`/`ATTRIBUTES` + 执行后 `X-Druid-Response-Context`(`NUM_SCANNED_ROWS`/`QUERY_TOTAL_BYTES_GATHERED`/`CPU_CONSUMED_NANOS`)→ 校准 §3 估算系数(按计划 digest × datasource × 形状分组);
4. **失败分类**:`Cannot vectorize`/Resource limit/DART fault → 分别触发不同降级路径(去向量化重试/改写/换引擎)。

---

## 8. 与前两版方案的差异清单(工程执行视角)

**删除**

- sqlglot/JSqlParser 依赖(自有 parser);
- SQL digest 历史表 → 替换为**计划片段 digest**(下推 SQL 的归一化文本,粒度更细、直接对应执行);
- 网关式 hint 注释协议 → 换成引擎原生 hint(你的 parser 直接支持,例如 `/*+ PUSHDOWN(druid, agg) */`),但保留"白名单校验"思想。

**新增**

- DruidScan 计划节点 + Convention 表达(哪些算子属于远程);
- §5 下推规则组 + §5.2 代价仲裁接入你的 CBO;
- §3 统计适配器(segmentMetadata/sys.segments → 统计 catalog);
- §4 能力矩阵落成两份 C++ 资产:`PushdownValidator`(规则表)与 `DruidDialectEmitter`(白名单发射器),以及 `INFORMATION_SCHEMA.ROUTINES` 驱动的函数表加载器;
- §6 结果摄取管线(含 Smile 解析器可选包);
- §7 EXPLAIN 自检(发射器回归测试框架)。

**保留(触发点后移)**

- context 注入决策表(§5.3,在 DruidScan 执行器里);
- DART 检查清单与降级链;
- 异步 statements 大结果通道;
- 元数据缓存(TTL 策略);
- 反馈闭环(§7.3);
- 语义安全红线:**任何自动决策不得改变结果语义**——联邦场景的红线更锋利:近似聚合、多值列、隐式子查询(可能超限失败)、类型隐式转换,这四类是"看起来能下推、实际语义变了/会炸"的高危区。

---

## 9. C++ 工程要点清单

1. **发射器**:纯函数式 `PlanFragment → string`;标识符全转义;字面量按类型格式化(TIMESTAMP 字面量、数组字面量);内置"方言测试夹具"(golden SQL 文件 + EXPLAIN 断言,CI 里打真实 Druid);
2. **函数映射表**:你的内置函数 → Druid 函数(启动时用 ROUTINES 表校验覆盖度,未覆盖函数出现在下推切片 → 该算子拉回本地);
3. **类型系统桥**:§4.6 映射表 + DECIMAL 精度丢失告警(Druid DOUBLE);
4. **HTTP 客户端**:连接复用、流式读(curl/libcurl 或自研)、每 Broker 并发上限(§6.2)、取消传播(网关 `sqlQueryId` + `DELETE /druid/v2/sql/{id}`);
5. **摄取解码**:JSON 行流(快速 JSON 解析器)+ 可选 Smile 二进制;`typesHeader` 驱动的列解码器工厂;
6. **错误分类器**:Druid 错误 JSON(`error/errorMessage/errorClass/host`)→ 可重试(超时/暂时性)/可降级(DART/向量)/不可重试(语法/语义)三类;
7. **统计缓存**:sys.segments/segmentMetadata 双源,失效策略 + 容错(统计缺失时退化为"仅 filter/projection 下推"的保守模式)。

---

## 10. 实施路线(调整版)

**M1(地基,2~3 周)**:DruidScan 节点 + filter/projection 下推规则 + JSON 通道摄取(arrayLines)+ 统计适配器 v1(sys.segments 表级)+ EXPLAIN 发射验证框架。→ 已能拿到 80% 的联邦收益(谓词/投影下推是最大头)。

**M2(聚合,2 周)**:聚合下推规则(含 DISTINCT 语义决策、TopN 形状处理)+ `useApproximate*` 策略配置 + segmentMetadata 列级统计进 CBO + 反馈闭环 v1。

**M3(连接与执行调优,2~3 周)**:join 三路决策(lookup/两侧下推/DART)+ DART 降级链 + 异步 statements 大结果通道 + 直连 Historical scan(可选)。

**M4(性能档,按需)**:Smile 二进制摄取 + 计划片段级代价学习(历史校准系数)。

---

## 附:一段计划的下推决策示例

```
用户 SQL(本地计划):
  Aggregate[SUM(revenue), COUNT(DISTINCT user)] 
    └─ Join(orders ⋈ users ON orders.uid = users.id)
         ├─ DruidScan(orders)        [orders: 5000 万行, uid 为多值列? 否]
         └─ DruidScan(users)         [users: 10 万行, IS_JOINABLE=NO]

决策走查:
  1. JoinDecision:右表非 lookup、两表均 Druid → 三选一;
     users 仅 10 万行 → "两侧各自下推 + 本地 hash join" 胜出
     (若 users 5 亿行 → DART 候选;若 users 是 lookup → 整体下推 join)
  2. 两侧下推切片:
     orders → SELECT uid, revenue FROM orders WHERE __time >= … (filter+projection,安全)
     users  → SELECT id FROM users (安全)
  3. COUNT(DISTINCT user) 留在本地(语义红线:默认下推会变 HLL 近似;
     用户开了"允许近似"才改为下推 APPROX_COUNT_DISTINCT + 打标)
  4. 注入:sqlQueryId、timeout;若走 DART 路径则 engine=msq-dart+targetPartitionsPerWorker
  5. EXPLAIN 自检两条发射 SQL(首跑);结果头 NUM_SCANNED_ROWS 回填统计
```

---

*本文与《druid-query-tuning-insights.md》(参数手册)、《druid-sql-router-design.md》(网关方案)构成三件套:手册回答"Druid 有什么",网关方案回答"外置路由怎么做",本文回答"自有 C++ 优化器如何联邦下推"。Druid 侧事实基于 35.0.1 源码;业界先例:DuckDB postgres_scanner(C++ SQL 发射 + COPY BINARY)、StarRocks JDBC Catalog(下推规则组)、Trino Druid connector(反面教材)、Calcite Convention/Druid adapter(理论模型)。*
