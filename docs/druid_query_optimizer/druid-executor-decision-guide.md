# Druid 执行决策层手册:什么信号 → 设什么 context → 选什么引擎

> **定位**:这是《druid-federation-pushdown-design.md》(联邦下推方案)缺失的核心一章的完整展开——**下推规则你已经有了,弱的是 CBO**。本文回答:每个计划片段下发 Druid 前,执行器到底依据什么信号、注入哪些 query context、选择哪个引擎/通道,以及**每个参数在 Druid 侧的真实生效点**(什么时候设了等于白设)。
> 
> 所有生效机制均与 35.0.1 源码核对(括号内标注代码位置)。先例引用:Spark AQE、Trino dynamic filtering / adaptive plan optimizations、Snowflake QAS、DuckDB postgres_scanner。

---

## 0. 弱 CBO 下的三条设计公理(先例背书)

你的 CBO 不可靠 → 不要试图"算出最优",要**把决策体系设计成不依赖精确代价**:

1. **静态决策后移为运行时决策**(Spark AQE 的核心原则,官方原话:"re-optimizes the query plan in the middle of query execution, based on accurate runtime statistics… converting static plan optimizations to runtime ones")——凡是先验不可靠的决策(引擎选择、结果通道),都留**降级/重试**的口子,让第一次执行的真实结果纠正第二次;
2. **高置信才动作**(Snowflake QAS 的原则:"only marks a query as eligible if there is high confidence")——信号不足时,缺省决策=只加 `timeout`+`sqlQueryId`,其余不设;
3. **固定策略优于错误代价**(DuckDB postgres_scanner 的选择:永远下推 filter/projection、从不下推聚合,零统计依赖)——把"大概率正确"的规则写成硬规则,把"需要代价判断"的留给反馈闭环。

由此,决策输入分三层,可靠性递减、用途不同:

| 层            | 来源                                           | 可靠性        | 承担的决策                |
| ------------ | -------------------------------------------- | ---------- | -------------------- |
| **L1 计划形状**  | 你自己的逻辑计划(下推规则已产出)                            | **确定**     | 语义类参数、通道选择、DART 检查清单 |
| **L2 量级档位**  | sys.segments 区间求和(扫描上界)+ segmentMetadata 列统计 | 量级可信(±10x) | 档位判断(band),不做精确代价比较  |
| **L3 运行时反馈** | `X-Druid-Response-Context` 真实代价 + 历史         | 事后才知       | 校准档位、A/B 引擎、降级触发     |

**量级档位(band)表**——替代精确代价的判断单位:

| 档位   | 扫描行数上界(L2)    | 含义                       |
| ---- | ------------- | ------------------------ |
| B0 微 | < 10 万        | 怎么跑都快,零干预                |
| B1 小 | 10 万 ~ 1000 万 | 常规,默认参数                  |
| B2 中 | 1000 万 ~ 1 亿  | 开始注入保护类参数                |
| B3 大 | 1 亿 ~ 10 亿    | 保护参数 + 通道/引擎决策 + DART 候选 |
| B4 巨 | > 10 亿        | 异步通道 + 熔断上限,拒绝或强制 async  |

档位边界是初始先验,由 L3 按 datasource × 形状自校准(§5)。

---

## 1. 首要认知:两类参数,时机完全不同

这是整个决策层最重要的分类,搞反了就白做:

### 1.1 语义类(plan-time)——改变 Druid 生成的 native 查询本身

| 参数                                                        | 生效点                                                                                                      | 决策时机                                                                                                              |
| --------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `useApproximateCountDistinct`(默认 **true**)                | SQL planner(`PlannerContext` → `PlannerConfig.withOverrides(queryContext)`,plan 阶段读入)                    | **必须在 toSQL 下发前定**:true 时 `COUNT(DISTINCT)` 翻译成 HLL 聚合器;false 时翻译成精确 groupBy 路径——同一个 context 值产生**不同的 native 查询** |
| `useApproximateTopN`(默认 **true**)                         | 同上,planner 规则(单维 GROUP BY+ORDER BY+LIMIT 是否转近似 topN 查询)                                                  | 同上,plan-time                                                                                                      |
| `minTopNThreshold`(默认 1000)                               | `TopNQueryQueryToolChest` 的 `ThresholdAdjustingQueryRunner`:仅当查询 threshold **≤** 该值时,把 threshold 重写上去再合并 | plan 后 runtime 前,但影响的是 native 查询形态(阈值上送)                                                                          |
| `sqlJoinAlgorithm` / `finalizeAggregations` 等 MSQ/DART 参数 | MSQ/DART 引擎的 stage 规划                                                                                    | DART 路径专用(§4)                                                                                                     |

**规则:语义类决策 = 产品语义决策,不下沉给"调优逻辑"**。你的下推规则遇到 `COUNT(DISTINCT)`/TopN 形状时,必须先查会话/租户的"允许近似"配置:

- 允许近似 → 不注入(吃 Druid 默认),发射器照常翻译,结果打 `approximate: true` 标记;
- 要求精确 → **必须**显式注入 `useApproximateCountDistinct: false`(或 `useApproximateTopN: false`),并接受精确路径的代价(高基数时是 DART 候选信号);
- **红线(重申)**:自动决策永不把 false 改 true(不为提速静默降语义)。

### 1.2 执行类(runtime)——不改查询语义,只改执行行为

`timeout`/`priority`/`lane`/`maxQueuedBytes`/`maxScatterGatherBytes`/`maxSubqueryRows`/缓存开关/`vectorize`/`enableParallelMerge`/`engine`。这些才是"调优逻辑"的地盘,逐个展开(§2–§3)。

---

## 2. 执行类参数决策卡(每张含:何时注入 / 生效点 / 何时白设 / 失败模式)

### 2.1 `timeout` —— 唯一"永远注入"的参数

- **生效点**:Broker 收到查询即设墙钟 deadline(`SetAndVerifyContextQueryRunner` 写入 `ResponseContext` 的 fail-time),逐跳**衰减传播**(`DirectDruidClient` 把剩余 timeLeft 作为新 timeout 发给数据服务器),Historical 侧 `future.get(timeout)` 强制取消 → `QueryTimeoutException` → **HTTP 504**。默认 300000ms(5 分钟)。
- **注入规则**:
  - 交互流量(短聚合/面板,SLA 敏感):`timeout = min(SLA, 10~30s)` 快速失败;
  - 下推大扫描(B3/B4):按历史 p80 × 3 起步,有下限(如 120s);
  - async 通道:宽松(轮询模型不占连接)。
- **白设场景**:无——timeout 永远生效,区别只在值。**这是唯一无条件的注入项。**

### 2.2 `priority` —— 只在"有排队竞争"时不是白设

- **源码事实(35.x,重要纠偏)**:Broker/Historical HTTP 入口的 `QueryScheduler` 用 resilience4j **Bulkhead,maxWaitDuration=0——没有排队、没有 LIFO 优先级队列**(旧版本的 LIFO 队列已移除),满了直接 `QueryCapacityExceededException` → **HTTP 429**。priority 真正的两个生效点:
  1. **HiLo lane 判定**:`priority < 0` 的查询进 "low" lane(受 lane 并发限制约束);
  2. **Historical 处理池排队**:priority 随查询传播到数据服务器,进 `PrioritizedExecutorService` 的 `PriorityBlockingQueue`(数值大=优先),**只在处理线程池饱和有排队时改变顺序**——单查询独跑时完全无感。
- **注入规则**:多租户混合负载才有意义——交互租户 `priority: 10`,批量导出/回填 `priority: -1`(顺带进 low lane);纯单租户集群**不注入**(no-op)。
- **失败模式**:无(纯调度提示,不失败)。

### 2.3 `lane` —— 需要集群配合的租户隔离

- **生效点**:lane 是集群配置(`druid.query.scheduler.laning.*`:manual lanes / hilo)里的概念;查询 context 的 `lane` 值只在集群配了 `ManualQueryLaningStrategy` 时被引用。lane 耗尽 → `QueryCapacityExceededException` → **429**。
- **注入规则**:探测到集群启用 manual laning(启动探测,§6)才注入(如 `lane: "interactive"`);未配置时是白设。429 要在错误分类器里映射为"可重试(退避)"。

### 2.4 `maxQueuedBytes` —— 大结果拉取的 Broker 背压

- **生效点**:每查询限额,按查询命中的服务器数**均分**(`CachingClusteredClient` 把 broker 的 `druid.broker.http.maxQueuedBytes` 除以扇出数);超限时 Historical 侧 channel 写阻塞 = **背压,不失败**(`DataServerResponseHandler`)。
- **注入规则**:下推**明细拉取**(你本地要做 join/窗口而拉明细)时注入:经验值 ≈ 2MiB × Historical 数,或按"预估结果行数 × 行宽 × 2"给上界;聚合下推(结果集小)不需要。
- **失败模式**:不失败,只拖慢——所以它是"防 Broker OOM"的保护,代价是吞吐。

### 2.5 `maxScatterGatherBytes` —— 熔断(会失败!)

- **生效点**:跨所有数据服务器的累计回收字节超限 → `ResourceLimitExceededException` → **HTTP 400**,查询失败。
- **注入规则**:与 `maxQueuedBytes` 语义相反(一个背压一个熔断),给"不可控的导出型下推"设熔断上限(如 B4 档拉明细:`预估行数 × 行宽 × 3`);常规查询不设(吃集群默认 `Long.MAX_VALUE`)。

### 2.6 `maxSubqueryRows` —— 你自己发射的子查询护栏

- **生效点**:Broker 物化子查询结果时检查;**累加器是整个主查询共享的**(所有子查询合计,不是每子查询独立,`ClientQuerySegmentWalker` 的共享 `AtomicInteger`);超限 → "Resource limit exceeded"。
- **源码级例外**:groupBy 套 groupBy(整链均为 groupBy,`canPerformSubquery`)会作为子查询**流式下推到数据服务器执行,不物化、不受此限**。
- **注入规则**:能力矩阵(§联邦方案 4.3)原则上让发射器避免子查询;确实要发(如 `IN (SELECT …)` 形态)时:EXPLAIN 确认物化规模,预估超 10 万行就重写(或改走"两侧下推+本地 join"),**不要**注入更大的 `maxSubqueryRows` 绕护栏(红线:不超集群上限)。

### 2.7 缓存四开关 —— 先问"这个查询缓存得了吗"

- **源码事实**:Broker 段缓存命中的三重与门:`集群配置 useCache` ∧ `查询 context useCache(默认 true)` ∧ **查询本身可缓存**——而 `dataSource.isCacheable(broker)` 对 **QueryDataSource(子查询)和 JoinDataSource 直接为 false**(`CacheUtil.isUseSegmentCache` → `DataSource.isCacheable`);含这些形态的查询在 Broker 上**根本不缓存,设什么都没用**。结果级缓存(whole-query)的 key 由各 toolchest 从 **native 查询**计算(`computeResultLevelCacheKey`),同样受 cacheable 约束。
- **注入规则**:
  - 下推为纯 table scan/聚合(可缓存)→ 全默认(读写在)即可,无需注入;
  - 一次性大导出(B3/B4)→ `populateCache: false`(防污染缓存,官方用法);
  - 调试/基准 → `useCache: false, populateCache: false` 取真实性能;
  - **join/子查询下推 → 一个缓存参数都不用设**(白设)。
- 高频重复 digest(面板刷新)在可缓存形态下自然吃缓存——决策层的贡献只是"别把它路由成 join 形态"。

### 2.8 `vectorize` / `vectorSize` —— 不干预,只观测

- **生效点**:groupBy/timeseries/**timeBoundary**(注意:timeBoundary 也向量化,常被漏记;scan/topN 不向量化)在**每个 segment 上独立判定**(`QueryableIndexCursorHolder.canVectorize`:聚合器/虚拟列/filter matcher/时间序),默认 `true` = 不满足就**静默回退行式**。
- **注入规则**:**永远不注入**。`force` 是生产炸弹(实时 segment 永不可向量化);`false` 无收益。决策层只做两件事:① 下推规则保证聚合形状落在向量化集合内(聚合器白名单、无多值分组列);② 反馈层监控 `query/segment/time` 的 `vectorized` 维度占比,低占比=形状问题信号(归因给下推规则修)。
- `vectorSize`(默认 512):B3+ 纯数值聚合可实验 1024,但**先有反馈数据再动**(弱 CBO 公理:先观测后调参)。

### 2.9 `enableParallelMerge` / `parallelMergeParallelism` —— 大结果合并

- **生效点**:Broker 侧并行合并池(默认开启;`parallelism` 默认取集群配置)。**只在 Broker 合并是瓶颈时**有意义——典型:下推聚合返回大量分组行(高基数 groupBy 结果数十万行级)。
- **注入规则**:反馈显示 Broker 侧耗时占比高(`query/time` 远大于 `query/segment/time` 总和)且结果行数大 → 才考虑 `parallelMergeParallelism` 调整;否则白设。

---

## 3. 引擎选择决策树(native / msq-dart / async 三轴)

先明确:**引擎与通道是两个正交决策**——native 或 DART 都可以走 sync 或 async;async statements 端点本身用 MSQ 任务引擎(`msq-task`)执行。决策顺序:先引擎,后通道。

### 3.1 引擎轴:native vs `msq-dart`

**DART 前置检查清单(全过才进入候选,L1/L2 信号)**:

1. 集群已启用(启动探测 `GET /druid/v2/sql/engines` 返回含 `msq-dart`;注意该端点只回引擎名列表);
2. 语句是 SELECT(你的下推必然是);
3. 走 HTTP `/druid/v2/sql` 通道(你的执行器天然满足;**JDBC/Avatica 路径会忽略 engine 参数**——源码确认 avatica 包零处引用 engine);
4. 不依赖 DART 不支持的东西:查询缓存、priority/laning(DART 不参与——所以**给 DART 查询注入调度类参数是白设**)、近似 TopN(恒精确);
5. 实时占比低(L2:`realtime_share` < ~30%,规避已知实时缺陷:复杂类型 NPE、广播 join 需 sortMerge)。

**触发条件(满足任一,DART 候选)**:

| 信号                               | 来源              | 阈值(初始先验,可校准)                   |
| -------------------------------- | --------------- | ------------------------------ |
| 精确 COUNT DISTINCT / 高基数 GROUP BY | L1 形状 + 语义决策=精确 | 无阈值,形状命中即候选                    |
| 大 JOIN(右侧非 lookup,两侧都大)          | L1 + L2         | 右侧行数 > 100 万                   |
| 大扫描聚合                            | L2              | B3+(> 1 亿行)                    |
| 历史慢                              | L3              | 同 digest p80 > 30s 且 native 引擎 |

**否决条件(DART 强制降级 native)**:

- B0/B1 档(轻查询 native 快 ~2x,设计者基准:25 并发点查 native 1529 QPS vs DART 730);
- 需要吃缓存的重复查询(DART 无缓存);
- 高 QPS 短查询(DART controller 并发默认 1,排队);
- 实时占比高(检查清单第 5 条)。

**DART 命中时的注入包**:`engine: "msq-dart"` + `targetPartitionsPerWorker: <探测到的 Historical numThreads>`(最重要的调优项,默认 1 等于单线程)+ 可选 `maxConcurrentStages`(2→3 提流水线);左表含实时数据的 join 追加 `sqlJoinAlgorithm: "sortMerge"`。**注意调度类参数(timeout 除外)对 DART 白设。**

### 3.2 通道轴:sync `/druid/v2/sql` vs async `/druid/v2/sql/statements`

| 条件(任一)                  | 通道           | 附加注入                                                                                |
| ----------------------- | ------------ | ----------------------------------------------------------------------------------- |
| 预估结果 < 10 万行 且 预计 < 60s | **sync**(默认) | `resultFormat: arrayLines`(流式)+ `header/typesHeader`(类型自描述)                         |
| 预估结果 ≥ 10 万行(导出/拉明细)    | sync 大结果档    | 同上 + `populateCache: false` + `maxQueuedBytes`(§2.4)+ `maxScatterGatherBytes`(§2.5) |
| ≥ 3000 行且不可控/超长/断连敏感    | **async**    | `executionMode: "async"` + `selectDestination: "durableStorage"`(>3000 行官方建议),分页拉取  |
| 深存储冷数据(未加载 segment)     | async(唯一选择)  | 同上                                                                                  |
| B4 巨档                   | async 强制     | 同上 + 熔断评估,必要时直接拒绝并要求改写                                                              |

**async 的代价**(为什么它不是默认):提交-轮询延迟、MSQ 任务开销(小查询反而慢一个量级)、需要 MSQ 扩展。**弱 CBO 补偿**:sync 超时 → 自动转 async 重提(决策后移,Spark AQE 精神)。

### 3.3 完整决策树(伪代码)

```
decide(fragment):                     # fragment = 下推规则产物的单个 DruidScan 切片
  ctx = { sqlQueryId: gen(), timeout: budget(L1流量类型, L3历史p80) }   # 永远注入

  # --- 语义类(L1, plan-time, 决策在 toSQL 之前!) ---
  if fragment.has_count_distinct or fragment.topn_shape:
      ctx.useApproximate* = semantic_policy(tenant)    # 只允许 false 或不设,永不改 true
      if 要求精确 and 高基数: dart_candidate = true

  # --- 量级(L2) ---
  band = estimate_band(fragment.tables, fragment.time_range)   # sys.segments 上界

  # --- 引擎 ---
  engine = "native"
  if dart_checklist_pass() and (dart_candidate or band >= B3 or history_slow(digest)):
      engine = "msq-dart"
      ctx += { engine, targetPartitionsPerWorker: probed_num_threads }
      if fragment.join and realtime_share > 0: ctx.sqlJoinAlgorithm = "sortMerge"
      if band <= B1 or needs_cache(fragment) or high_qps(digest): engine = "native"  # 否决

  # --- 通道 ---
  if band >= B4 or est_rows > 100k and unbounded or deep_storage(fragment):
      channel = async; ctx += { executionMode: async, selectDestination: durableStorage }
  else:
      channel = sync; ctx.resultFormat = "arrayLines"; ctx.header = true
      if est_rows > 100k: ctx += { populateCache: false, maxQueuedBytes: …, maxScatterGatherBytes: … }

  # --- 调度类(仅当集群有竞争信号) ---
  if multi_tenant and engine == "native":
      ctx.priority = traffic_class(tenant)        # 交互 +10 / 批量 -1
      if manual_laning_probed: ctx.lane = tenant_lane

  return Decision(engine, channel, ctx, fallback_chain)
```

**缺省原则重申**:任何信号缺失(L2 统计拉不到、L3 无历史)→ 输出退化为 `{timeout, sqlQueryId}` + sync + native,即 Druid 默认行为 + 可观测性。**弱 CBO 不会导致错误决策,只会导致保守决策。**

---

## 4. 失败模式 → 降级链(把运行时当优化器用)

| 失败(HTTP/错误)                                     | 含义              | 自动动作                                                                  |
| ----------------------------------------------- | --------------- | --------------------------------------------------------------------- |
| 504 / QueryTimeoutException                     | 超预算             | sync→async 重提(预算放宽 3x);连续超时 → digest 打"重"标                            |
| 429 / QueryCapacityExceededException            | lane/bulkhead 满 | 退避重试(指数,≤2 次);持续 429 → 该 lane 限流自己的入口                                 |
| 400 / Resource limit exceeded(maxScatterGather) | 回收超熔断           | 拒绝 + 改写建议(加时间过滤/列裁剪);不重试                                              |
| Resource limit exceeded(maxSubqueryRows)        | 子查询物化超限         | 重写(外层聚合化/拆分);不盲目调大                                                    |
| DART 任意 fault / NPE / 18340                     | DART 缺陷         | 同 `sqlQueryId` 降级 native 重试(SELECT 幂等);DART 失败率超阈值 → 暂停 DART 候选 30min |
| "Cannot vectorize!"(仅 force 验证时)                | 向量化形状不满足        | 归因给下推规则(形状修),非运行时问题                                                   |
| 流截断(行格式无结尾换行)                                   | 中途错误            | 同步路径按失败处理,重试一次                                                        |

所有降级在响应头留痕(`X-Router-Decision: dart→native, reason=…`),并写反馈表。

---

## 5. 反馈闭环:弱 CBO 的"事后优化器"

**(1) 档位校准**:每次执行记录 `(datasource, 形状类, band_guess, 真实 NUM_SCANNED_ROWS / QUERY_TOTAL_BYTES_GATHERED / CPU_CONSUMED_NANOS)`(来自 `X-Druid-Response-Context`)→ 按 datasource×形状 维护 `guess→real` 回归系数,修正 band 边界。**这替代了你 CBO 缺失的选择率统计**(Druid 本身只有 STRING 列 cardinality/min-max,无直方图——精确选择率谁都算不出)。

**(2) A/B 引擎(Spark AQE "把 join 策略决策推迟到运行时"的廉价版)**:同 digest 的查询在 native/DART 都有样本时,比较 p50/p80 分布,直接更新该 digest 的引擎偏好;冷启动期可按 10% 流量做探索性双发(结果一致才切换,聚合结果逐行比对成本可接受时)。

**(3) 超时预算学习**:`timeout = p80(同 digest) × 2`,上下限夹紧。

**(4) 通道升级触发**:同 digest sync 超时率 > 20% → 自动迁移 async。

**(5) 白设检测**(工程护栏):所有"注入了但生成环境显示无效果"的参数(如对 join 查询设缓存、对 DART 设 priority)在日志里标记——防止决策表腐化。

---

## 6. 启动探测与环境画像(决策层的静态输入)

```
GET  /druid/v2/sql/engines              # {"engines":[{"name":"native"},…]} → DART/MSQ 可用性
GET  /druid/v2/sql/statements/enabled   # async 通道可用性
SELECT … FROM sys.servers               # Historical 数量/tier/numThreads 近似 → targetPartitionsPerWorker、maxQueuedBytes 基数
SELECT … FROM sys.segments              # L2 量级估算源(§联邦方案 3)
Scheduler/laning 配置探测               # priority/lane 是否有意义(无管理端点,从 429 行为反推 + 运维登记)
EXPLAIN PLAN FOR 探针                    # 发射产物验证(§联邦方案 7)
```

版本漂移防护:**能力探测优于版本判断**(有端点就探测);本文所有默认值基于 35.0.1,升级时重点复核 scheduler 行为(35.x 已无 LIFO 队列,这是个曾变过的点)与 DART 限制清单。

---

## 7. 决策速查表(贴墙版)

| 信号                               | 注入                                                                                 | 为什么(生效点一句话)                               |
| -------------------------------- | ---------------------------------------------------------------------------------- | ----------------------------------------- |
| 永远                               | `timeout` + `sqlQueryId`                                                           | 墙钟从 Broker 收查询起算,不设=5 分钟默认                |
| 含 COUNT(DISTINCT)/TopN 形状        | `useApproximate*`(仅"要精确"时设 false)                                                  | plan-time:设不设生成**不同的 native 查询**          |
| 精确 distinct / 大 join / B3+ / 历史慢 | `engine=msq-dart` + `targetPartitionsPerWorker=numThreads`                         | DART 全流水线并行;轻查询否决(native 快 2x)            |
| DART + 左表实时                      | `sqlJoinAlgorithm=sortMerge`                                                       | 实时广播 join 不支持                             |
| 结果 >10 万行/不可控                    | async + `executionMode=async` + `selectDestination=durableStorage`                 | >3000 行官方建议;断连不丢                          |
| sync 大结果                         | `populateCache:false` + `maxQueuedBytes`(2MiB×Hist 数)+ `maxScatterGatherBytes`(熔断) | 背压不失败 / 熔断会失败,分工使用                        |
| 多租户 + native                     | `priority`(交互+/批量−)                                                                | 只在 Historical 处理池排队与 HiLo 判定时生效;DART 查询白设 |
| 集群配了 manual lanes                | `lane`                                                                             | 未配置=白设;耗尽 429                             |
| 下推 SQL 含子查询                      | 先 EXPLAIN 估物化量;默认护栏 10 万行总量                                                        | 累计计,groupBy套groupBy 流式例外                  |
| 含 join/子查询                       | **任何缓存参数都不设**                                                                      | `isCacheable(broker)=false`,物理不可缓存        |
| 任何时候                             | **不碰** `vectorize`/`forceLimitPushDown`/超上限的 `maxSubqueryRows`                     | 自动回退已最优;force 是炸弹;红线                      |
| 结果行多且 Broker 合并慢                 | `parallelMerge*`                                                                   | 只在合并是瓶颈时有感                                |

---

*本文补齐《druid-federation-pushdown-design.md》的执行器决策层:下推规则决定"发什么 SQL",本文决定"怎么把它跑好"。与《druid-query-tuning-insights.md》(参数语义手册)配合使用——那里有每个参数的完整语义与默认值表。*
