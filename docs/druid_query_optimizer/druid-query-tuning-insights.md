# Apache Druid 查询调优深度洞察报告

> **基线说明**:本报告基于本地 Apache Druid 源码仓库(`35.0.1` 分支,commit `134cd08`,2025-12-04)与 druid.apache.org 官方文档交叉验证写成。所有参数名、默认值、端点路径均与源码核对(`QueryContexts.java`、`AvaticaServerConfig.java`、`SqlResource.java`、`SqlStatementResource.java`、`DartSqlEngine.java`、`SqlEngineRegistry` 等)。文中标注了版本差异(如 DART 在 31.0.0 引入、36/37 的新演进),使用时请对照自己的 Druid 版本。
> 
> **范围**:查询侧调优 —— SQL 接口(`/druid/v2/sql`、Avatica JSON/Protobuf、异步 statements)、Native 报文接口(`/druid/v2`)、query context 参数、向量化执行(vectorization)、DART 引擎,以及配套的场景化调优手法。

---

## 0. TL;DR 速查

| 你遇到的问题                            | 首选手段(详见对应章节)                                                                                       |
| --------------------------------- | -------------------------------------------------------------------------------------------------- |
| 交互式 Dashboard 高并发慢                | §11.1:priority/lane + 缓存策略 + TopN 近似 + 超时兜底                                                        |
| 大结果集导出/拉取慢                        | §11.2:`/druid/v2/sql` 的 `arrayLines/csv` 流式格式,或异步 statements + `durableStorage`;JDBC 则调 fetch size |
| 高基数精确 GROUP BY / COUNT DISTINCT 慢 | §11.3:DART 引擎(`engine=msq-dart`);或 native groupBy 的 spill/并行合并调优                                   |
| 大表 JOIN 慢                         | §11.4:DART + `sqlJoinAlgorithm=sortMerge`;或消除 join/预 join                                          |
| 短查询 JDBC 反而比 HTTP 慢一个量级           | §4.4:Avatica 协议开销,短查询/脚本直接用 `/druid/v2/sql`                                                        |
| 想确认向量化有没有生效                       | §9.5:`query/segment/time` 指标的 `vectorized` 维度 + `vectorize=force` 验证                               |
| 子查询报 Resource limit / Broker OOM  | §11.7:`maxSubqueryRows`/`maxSubqueryBytes` guardrails,重写查询                                         |
| IN 列表很大很慢                         | §11.6:字符串 IN(不是数字)、`inFunctionThreshold`、必要时 inline join                                           |
| 聚合查询 CPU 高                        | §9 向量化 + §8 `useApproximateCountDistinct`/`useApproximateTopN` + 查询改写(§11.8)                       |
| 想为某类查询隔离资源                        | §8 `priority`/`lane` + §7 Router 多 Broker 组 + §12 连接池                                              |

**一个心智模型**:Druid SQL 只是 Native 查询的"前端"——Broker 上的 Calcite planner 把 SQL 翻译成一个或多个 native 查询(scan/timeseries/topN/groupBy),再 scatter-gather 到 Historical 并行执行。所以 SQL 调优的本质是:**① 让 planner 翻译出更优的 native 查询(查询写法 + planner 参数);② 让 native 执行引擎跑得更快(向量化、groupBy 引擎参数、DART 换引擎);③ 管好资源与并发(context 限额、priority/lane、集群配置)**。用 `EXPLAIN PLAN FOR` 查看 SQL→native 的翻译结果,是一切调优的起点。

---

## 1. 查询链路总览

```
客户端                                Broker                          Historical / 摄取任务
──────                                ──────                          ──────────────────────
SQL(HTTP/JSON、JDBC/Avatica) ──►  Calcite planner ──► native query ──►  按 segment 并行计算
native JSON/Smile        ──►  (直接)              ──►  partial results ──►  (可被 per-segment 缓存)
                                                                      ◄──  Broker 合并、流式返回
```

关键事实(决定了很多调优逻辑):

1. **table 数据源走 scatter-gather**:Broker 按 `intervals` 剪枝 segment(时间分区),若摄入时做了 secondary 分区(如 `CLUSTERED BY`/range 分区)还能按 filter 进一步剪枝(context `secondaryPartitionPruning`,默认 `true`),然后把查询扇出到 serving 该 segment 的 Historical。
2. **子查询在 Broker 上物化**:除"groupBy 套 groupBy 可流式"这一例外,子查询结果会缓冲在 Broker 内存中、顺序执行,受 `maxSubqueryRows`(默认 100000)/`maxSubqueryBytes` 限制。
3. **JOIN 是 broadcast hash join**:右侧(lookup/inline/子查询)建哈希表;join 下面挂子查询是性能杀手(planner 无法把谓词下推过 join)。
4. **SQL 查询类型映射规则**(决定走哪个 native 引擎):
   - 无聚合 → **Scan**
   - 仅按 `FLOOR(__time TO …)`/`TIME_FLOOR` 分组、无 HAVING → **Timeseries**(利用 segment 按时间排序,全流式,最快)
   - 单维度 GROUP BY + ORDER BY + LIMIT → 默认 **TopN**(近似!可用 `useApproximateTopN=false` 关闭)
   - 其余聚合 → **GroupBy**(精确、支持 spill)
5. **DART 是另一条执行路径**:不走 scatter-gather,而是 Broker 当 controller、Historical 当 worker 的 MSQ profile(§10)。

---

## 2. 查询接口全景与选型

### 2.1 五个查询端点一览(源码核对)

| 端点                                    | 处理类(源码位置)                                                                             | 协议/形态                                        | 有状态?                            | 典型用途                                 |
| ------------------------------------- | ------------------------------------------------------------------------------------- | -------------------------------------------- | ------------------------------- | ------------------------------------ |
| `POST /druid/v2/sql`                  | `sql/…/sql/http/SqlResource.java`                                                     | HTTP + JSON(或纯文本 SQL)                        | 无状态                             | **重点**:脚本、应用集成、Dashboard、导出;DART 也走它 |
| `POST /druid/v2/sql/avatica`          | `sql/…/sql/avatica/DruidAvaticaJsonHandler.java`                                      | Avatica JSON wire protocol                   | **有状态**(连接/statement 驻留 Broker) | JDBC(Java/BI 工具)                     |
| `POST /druid/v2/sql/avatica-protobuf` | `sql/…/sql/avatica/DruidAvaticaProtobufHandler.java`                                  | Avatica Protobuf(`application/octet-stream`) | 有状态                             | JDBC 大结果集、Golang Avatica 驱动          |
| `POST /druid/v2/sql/statements`       | `multi-stage-query/…/SqlStatementResource.java`(`@Path("/druid/v2/sql/statements/")`) | HTTP + JSON,异步任务式                            | 任务态(Overlord)                   | 大结果集/深存储查询、fire-and-poll             |
| `POST /druid/v2`                      | `server/…/QueryResource.java`                                                         | Native 查询 JSON/Smile                         | 无状态                             | 完整控制 native 能力、ETL 下游                |

辅助端点:`GET /druid/v2/sql/engines`(列出可用引擎)、`GET /druid/v2/sql/queries`(运行中 SQL)、`DELETE /druid/v2/sql/{sqlQueryId}`(取消 SQL);native 侧 `DELETE /druid/v2/{queryId}`(取消)、Broker 上还有 `POST /druid/v2/candidates`(查看查询会扇出到哪些 segment/服务器,排障用)。

### 2.2 选型决策表

| 场景                                                | 推荐                                                          | 理由                                                            |
| ------------------------------------------------- | ----------------------------------------------------------- | ------------------------------------------------------------- |
| 服务端脚本、curl、简单集成                                   | `/druid/v2/sql`                                             | 无状态、5 种 resultFormat、流式响应;还支持 `text/plain` 直接 POST SQL 文本     |
| Java 应用 / BI 工具(Tableau、Superset 等)               | `/druid/v2/sql/avatica`(JDBC)                               | 标准 JDBC 语义、参数化查询、连接属性即 query context                          |
| JDBC 且结果集大(万行以上)                                  | `/druid/v2/sql/avatica-protobuf` + `serialization=protobuf` | Protobuf 压缩更好,大结果集开销更低                                        |
| 结果集巨大(10 万行以上)/ 拉取时长不可控 / 查深存储冷数据                 | `/druid/v2/sql/statements`(异步)                              | 结果落 durable storage 分页拉取,不占长连接;断连不丢结果                         |
| 需要 native 独有能力(ETag 增量、Smile、bySegment 调试、特殊查询类型) | `POST /druid/v2`                                            | SQL 无法表达的 native 特性都在这                                        |
| 高并发短查询(几毫秒级)                                      | `/druid/v2/sql`(HTTP)                                       | Avatica 每次查询要 prepare+execute+fetch 多轮 RPC,短查询下开销可达数倍(见 §4.4) |

多 Broker 部署注意:**Avatica/JDBC 连接状态不跨 Broker 共享**——要么连指定 Broker,要么走 Router(§7,按 connectionId 粘性路由)。`/druid/v2/sql` 与 `/druid/v2` 无状态,天然无此问题。

---

## 3. `/druid/v2/sql` 深度剖析(重点)

### 3.1 请求体:合法字段全集(`SqlQuery.java`)

`Content-Type: application/json` 时,请求体只有以下 6 个字段(其余字段会被忽略):

| 字段               | 类型     | 默认         | 说明                                                                                                                                                                    |
| ---------------- | ------ | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `query`          | string | **必填**     | SQL 文本。可内嵌多条 `SET key=value;` 语句设置 context(优先级高于 `context` 块)                                                                                                         |
| `resultFormat`   | string | `"object"` | `object` / `array` / `objectLines` / `arrayLines` / `csv` 五选一                                                                                                         |
| `header`         | bool   | `false`    | 首行返回列名                                                                                                                                                                |
| `typesHeader`    | bool   | `false`    | 追加 Druid 运行时类型行(要求 `header:true`,否则报错)                                                                                                                                |
| `sqlTypesHeader` | bool   | `false`    | 追加 SQL 类型行(要求 `header:true`)                                                                                                                                          |
| `context`        | object | `{}`       | query context(§8)                                                                                                                                                     |
| `parameters`     | array  | `[]`       | 参数化查询,元素为 `{"type": <SQL 类型>, "value": …}`,如 `{"type":"VARCHAR","value":"bar"}`、`{"type":"TIMESTAMP","value":"2020-01-01 00:00:00"}`、`{"type":"ARRAY","value":[1,2]}` |

**响应头**:

- `X-Druid-SQL-Query-Id`:本次 SQL 查询 ID(可用 context `sqlQueryId` 自定义;取消查询要用它);
- `X-Druid-Query-Id`:底层 native 查询 ID;
- `X-Druid-SQL-Header-Included: yes`:当 `header:true` 且服务端支持类型头时返回;
- HTTP trailer:`X-Druid-Response-Complete` / `X-Error-Message`(流式响应中途出错只能靠 trailer/截断检测)。

### 3.2 五种 resultFormat 怎么选

| format        | Content-Type       | 形态                   | 适用                                          |
| ------------- | ------------------ | -------------------- | ------------------------------------------- |
| `object`(默认)  | `application/json` | `[{col:val,…},…]`    | 通用、小结果集、需要按列名取值                             |
| `array`       | `application/json` | `[[v1,v2,…],…]`      | 列名已知、想省带宽(无重复 key)                          |
| `objectLines` | `text/plain`       | 每行一个 JSON 对象(NDJSON) | **大结果集流式消费**,无需流式 JSON 解析器                  |
| `arrayLines`  | `text/plain`       | 每行一个 JSON 数组(NDJSON) | 同上,更省带宽                                     |
| `csv`         | `text/csv`         | CSV                  | 直接落盘/进 Excel;`filename` 语义仅 statements 端点支持 |

**截断检测(务必实现)**:流中途出错时,`object`/`array` 会返回非法 JSON;`*Lines`/`csv` 格式每个完整响应以换行符结尾——**没有结尾换行 = 响应被截断,应视为错误**。

### 3.3 引擎选择:`engine` context 参数(35.x 起 DART 的入口)

`SqlResource` 从 context 读取 `engine` 并经 `SqlEngineRegistry` 分发:

| engine 值     | 引擎                                   | 说明                                     |
| ------------ | ------------------------------------ | -------------------------------------- |
| `native`(默认) | `NativeSqlEngine.NAME = "native"`    | 常规 scatter-gather                      |
| `msq-task`   | `MSQTaskSqlEngine.NAME = "msq-task"` | MSQ 任务引擎(异步 statements 的执行体)           |
| `msq-dart`   | `DartSqlEngine.NAME = "msq-dart"`    | **DART 引擎**(§10),同样从 `/druid/v2/sql` 进 |
| `view`       | `ViewSqlEngine`                      | SQL 视图定义校验                             |

未知 engine 名直接报错(`Unsupported engine[…]`)。DART 用法(两种等价写法):

```bash
# 写法一:context 块
curl -X POST 'http://router:8888/druid/v2/sql' -H 'Content-Type: application/json' -d '{
  "query": "SELECT user, COUNT(*) FROM wikipedia GROUP BY 1 ORDER BY 2 DESC",
  "context": { "engine": "msq-dart" }
}'

# 写法二:SET 语句(优先级更高)
curl -X POST 'http://router:8888/druid/v2/sql' -H 'Content-Type: application/json' -d '{
  "query": "SET engine = '\''msq-dart'\''; SELECT user, COUNT(*) FROM wikipedia GROUP BY 1 ORDER BY 2 DESC"
}'
```

> 版本提示:DART 在 31.0.0 引入时用的是专用端点 `/druid/v2/sql/dart`,后已统一为上述 `engine` 参数方式(旧端点不再支持)。当前版本(35.x)即为本节用法。

### 3.4 纯文本请求体(小技巧)

`Content-Type: text/plain` 或 `application/x-www-form-urlencoded` 时,body 就是 SQL 文本本身,`resultFormat` 固定 `object`:

```bash
echo 'SELECT 1' | curl -H 'Content-Type: text/plain' http://router:8888/druid/v2/sql --data @-
echo 'SELECT 1' | curl http://router:8888/druid/v2/sql --data-urlencode @-   # 自动 URL 编码
```

### 3.5 取消与排查

```bash
# 取消(sqlQueryId 可自定义;若多条查询共用同一 ID 会全部取消,尽力而为语义)
curl -X DELETE 'http://router:8888/druid/v2/sql/my-query-id'      # 202 成功 / 404 不存在或已结束
# 查看在跑的 SQL
curl 'http://router:8888/druid/v2/sql/queries'
```

**看 SQL 翻译成了什么 native 查询**:对同一条 SQL 加 `EXPLAIN PLAN FOR` 前缀再发 `/druid/v2/sql`(返回 `PLAN` 列的 JSON native 查询 + `RESOURCES` + `ATTRIBUTES`)。这比猜测有效得多——验证时间过滤是否变成了 `intervals`、join 是否插入了隐式子查询、TopN 是否被选中,全靠它。

### 3.6 `/druid/v2/sql` 的固有限制

- **无分页/游标**:整个结果是一次流式 HTTP 响应;客户端慢会拖住连接(靠 `maxQueuedBytes` 背压缓解,见 §8)。
- **流中错误不可靠表达**:状态码 200 先行,中途错误只有 trailer/截断可查。
- 交互式分页需求 → 用 SQL `LIMIT/OFFSET`(注意 OFFSET 只有 scan/groupBy 支持,且深翻页代价高,§11.2)或异步 statements 分页。

---

## 4. Avatica 接口:`/druid/v2/sql/avatica` 与 `/druid/v2/sql/avatica-protobuf`

### 4.1 两者区别与连接串

二者都是原始 Jetty handler(仅 POST),共用 `DruidMeta` 元数据逻辑,只是 wire protocol 不同:

|       | JSON 端点                   | Protobuf 端点                                      |
| ----- | ------------------------- | ------------------------------------------------ |
| URL   | `…/druid/v2/sql/avatica/` | `…/druid/v2/sql/avatica-protobuf/`               |
| 请求/响应 | Avatica JSON              | Avatica protobuf 字节流(`application/octet-stream`) |
| 驱动侧   | 默认                        | 连接串加 `serialization=protobuf`                    |

```java
// JSON
String url = "jdbc:avatica:remote:url=http://router:8888/druid/v2/sql/avatica/;transparent_reconnection=true";
// Protobuf(大结果集更低开销;也兼容官方 Golang Avatica 驱动)
String url = "jdbc:avatica:remote:url=http://router:8888/druid/v2/sql/avatica-protobuf/;transparent_reconnection=true;serialization=protobuf";

// 连接属性 = query context(除 user/password 外全部透传为该连接的 context)
Properties props = new Properties();
props.setProperty("sqlTimeZone", "Asia/Shanghai");
props.setProperty("timeout", "60000");          // 毫秒,会成为该连接所有查询的 context
props.setProperty("user", "admin");             // 认证,不进 context
props.setProperty("password", "password1");
Connection c = DriverManager.getConnection(url, props);
```

要点:

- **`transparent_reconnection=true`**:Avatica 1.21.0+ 必加,否则 Broker 池变更/重启会出现间歇性失败;
- **连接属性即 context**:`DruidMeta.openConnection` 把 info map 全部并入会话 context(`user`/`password` 除外),并强制 `sqlStringifyArrays=false`;`SET` 语句在 JDBC 中**不可用**,要设 context 只能走连接属性;
- **JDBC 只支持 SELECT**(statement type 强制),无 DML/批写。

### 4.2 Fetch(分帧)机制与调优

Avatica 的 ResultSet 分帧拉取,帧大小由服务端配置夹紧(旧代码常量 `DEFAULT_FETCH_SIZE` 已不存在,以配置为准,`AvaticaServerConfig.java`):

| 配置(`druid.sql.avatica.*`)    | 默认       | 说明                                                |
| ---------------------------- | -------- | ------------------------------------------------- |
| `maxConnections`             | **25**   | 每 Broker 逻辑连接上限,超出报 "Too many connections"        |
| `maxStatementsPerConnection` | **4**    | 每连接并发 statement 上限                                |
| `minRowsPerFrame`            | **100**  | 客户端 `setFetchSize` 的下限夹紧值                         |
| `maxRowsPerFrame`            | **5000** | 客户端 `setFetchSize` 的上限夹紧值(=服务端默认帧大小)              |
| `connectionIdleTimeout`      | `PT5M`   | 空闲连接回收                                            |
| `fetchTimeoutMs`             | **5000** | 单次 fetch 最多等 5s,超时返回**空帧**让客户端轮询——把长查询与 HTTP 超时解耦 |

**大结果集 JDBC 调优**:客户端 `Statement.setFetchSize(n)` 实际生效值 = clamp(n, 100, 5000)。要减少 RPC 轮数:

1. 服务端调大 `druid.sql.avatica.maxRowsPerFrame`(如 20000~50000,注意 Broker 内存与 GC);
2. 或调整 `minRowsPerFrame` 抬高下限;
3. 配合 §11.2 的查询侧手段(只 SELECT 需要的列、避免 ORDER BY 非时间列)。

### 4.3 已知坑清单

| 坑              | 现象                                                                | 处置                                                                         |
| -------------- | ----------------------------------------------------------------- | -------------------------------------------------------------------------- |
| 连接粘性           | 多 Broker 直连时结果集拉一半报错/丢状态                                          | 走 Router(按 connectionId 粘性,§7)或 LB sticky                                  |
| 连接数 25 上限      | 高并发 BI 报 "Too many connections"                                   | 调大 `druid.sql.avatica.maxConnections`,并评估 Broker 堆                         |
| 短查询协议开销        | 简单查询 JDBC 明显慢于 HTTP(prepare+execute+fetch 多轮往返;社区实测短查询可差数倍到 10 倍) | 高频短查询改用 `/druid/v2/sql`;JDBC 留给需要标准接口的场景                                   |
| fetch size 不生效 | 设了小值也没变化                                                          | 受 min/maxRowsPerFrame 夹紧(见上表)                                              |
| 长查询 HTTP 超时    | 大结果集中途断开                                                          | `fetchTimeoutMs` 空帧轮询机制 + `transparent_reconnection=true`;或改走异步 statements |

---

## 5. 异步接口:`POST /druid/v2/sql/statements`

MSQ 扩展提供的异步 SQL(同样可用 `/druid/v2/sql/statements/enabled` 探测是否开启):

```bash
# 1) 提交:context 必须带 executionMode: "async"(否则 400)
curl -X POST 'http://router:8888/druid/v2/sql/statements' -H 'Content-Type: application/json' -d '{
  "query": "SELECT * FROM wikipedia WHERE __time >= TIMESTAMP '\''2025-01-01 00:00:00'\''",
  "resultFormat": "arrayLines",
  "context": { "executionMode": "async", "selectDestination": "durableStorage" }
}'
# 返回 { "queryId": "query-…", "state": "ACCEPTED", "schema": [...], "createdAt": … }

# 2) 轮询状态(?detail=true 额外返回 stages/counters/warnings,相当于执行计划+进度)
curl 'http://router:8888/druid/v2/sql/statements/query-…?detail=true'

# 3) 拉结果(分页;resultFormat 同 /druid/v2/sql 五种;filename 可触发附件下载头)
curl 'http://router:8888/druid/v2/sql/statements/query-…/results?page=0&resultFormat=csv&filename=export.csv'

# 4) 取消
curl -X DELETE 'http://router:8888/druid/v2/sql/statements/query-…'
```

**调优要点**:

- 结果集 **> 3000 行时官方建议 `selectDestination: "durableStorage"`**(需开启 MSQ durable storage),否则结果塞在 task report 里只有单页;
- 不传 `page` 会一次性顺序返回所有页,大结果集会顶到 `druid.router.http.readTimeout`——务必分页;
- 深存储查询(未被 load rule 加载到 Historical 的 segment)只有这条路能查(要求该 datasource 至少有一个 segment 在 Historical 上以便 planner 工作);
- 断连/客户端崩溃不丢结果,poll 模式天然适合超大规模导出。

---

## 6. Native 接口:`POST /druid/v2`

```bash
curl -X POST 'http://broker:8082/druid/v2?pretty' \
  -H 'Content-Type: application/json' \
  -d '{
    "queryType": "timeseries",
    "dataSource": "wikipedia",
    "granularity": "day",
    "aggregations": [{"type": "count", "name": "cnt"}],
    "intervals": ["2025-08-01/2025-08-28"],
    "context": { "timeout": 60000, "priority": 1, "queryId": "ts-001" }
  }'
```

native 侧独有/差异能力:

- **Content-Type 协商**:`application/json` 或 `application/smile`(`application/jacksmile`)二进制,大结果集 Smile 更省;
- **ETag 增量拉取**:响应带 `ETag`,下次请求带 `If-None-Match` 头,数据未变则跳过计算(等价于客户端条件请求缓存);
- **响应头** `X-Druid-Query-Id`、`X-Druid-Response-Context`(含 segment 数等执行上下文,尺寸受 `druid.query.context` 相关配置限制);
- **取消**:`DELETE /druid/v2/{queryId}`;
- **排障**:`POST /druid/v2/candidates`(Broker)直接看到查询会命中哪些 segment/服务器;
- **并发治理**:查询经 `QueryScheduler`,配置前缀 `druid.query.scheduler.*`(`numThreads`、`laning`、`prioritization`),配合 context `priority`/`lane` 实现 QoS;
- 超时仍走 context `timeout`(没有 `X-Druid-Timeout` 请求头,源码中不存在)。

调优视角:SQL 用户一般不需要手写 native,但**读懂 EXPLAIN PLAN 里的 native 查询、偶尔直接发 native(绕过 planner 的不确定性、启用 bySegment 调试、享受 Smile/ETag)** 是高级手段。`bySegment: true`(仅 native)返回按 segment 的分片结果,是定位"哪个 segment 拖慢了查询"的利器。

---

## 7. Router 与连接粘性

- Router 挂载 `/druid/v2/*` 代理(`AsyncQueryForwardingServlet`);
- **`druid.router.sql.enable`(默认 `false`)**:开启后 `/druid/v2/sql` 的 POST 按 SQL 路由策略(`findServerSql`)选 Broker;关闭则走默认 Broker;
- **Avatica 请求始终按 connectionId 粘性路由**(Rendezvous Hash,`druid.router.avatica.balancer.type`,默认 `rendezvousHash`,可选 `consistentHash`)——多 Broker JDBC 必须走 Router 或 sticky LB;
- Router 会在转发 SQL 时自动补 `sqlQueryId`/`queryId`(若缺失),保证可取消、可追踪。

---

## 8. Query Context 参数全目录(按用途分类)

### 8.1 设置方式与优先级(低 → 高)

1. **内置默认值**(下表);
2. **运行时属性**:`druid.query.default.context.<参数>=<值>`(集群级默认,注意部分参数有专属配置项如 `druid.server.http.defaultQueryTimeout`);
3. **HTTP `context` 块 / native 查询的 `context` 字段**;
4. **`SET key = value;` 语句**(仅 HTTP API 与控制台;JDBC 不支持)。

JDBC 通过连接 `Properties` 设置(等价于 context 层)。

### 8.2 通用参数(所有查询,`QueryContexts.java` / 官方 query-context-reference)

| 参数                                                 | 默认                                                              | 作用与调优建议                                                                                                                                                                                              |
| -------------------------------------------------- | --------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `timeout`                                          | 300000(5 分钟,源码 `DEFAULT_TIMEOUT_MILLIS`)                        | 毫秒;0=不限(受 `druid.server.http.maxQueryTimeout` 上限约束)。交互查询建议显式设小(如 30s)快速失败                                                                                                                            |
| `perSegmentTimeout`                                | 0(不限)                                                           | 单 segment 处理超时,应 ≤ `timeout`;可防单个坏/大 segment 拖死整条查询                                                                                                                                                  |
| `priority`                                         | 0                                                               | 越大越优先(调度器排序用)。慢报表降权、交互查询提权                                                                                                                                                                           |
| `lane`                                             | 无                                                               | 配合 `druid.query.scheduler.*` 的 lane 策略做限流隔离(如 `lane: "interactive"`)                                                                                                                                 |
| `queryId`                                          | 自动生成                                                            | native 查询 ID;SQL 层请用 `sqlQueryId`(对 SQL 请求设 `queryId` 无效)                                                                                                                                            |
| `sqlQueryId`                                       | 自动生成                                                            | SQL 查询 ID,响应头 `X-Druid-SQL-Query-Id` 返回;取消用它                                                                                                                                                         |
| `brokerService`                                    | 无                                                               | 手动路由策略下指定目标 Broker 服务                                                                                                                                                                                |
| `maxScatterGatherBytes`                            | `Long.MAX_VALUE`                                                | 单查询从数据进程回收的字节上限,**超限即失败**(熔断保护);可按查询收紧                                                                                                                                                               |
| `maxQueuedBytes`                                   | 取 `druid.broker.http.maxQueuedBytes`(默认 max(25MB, Broker 堆 2%)) | 每查询排队字节上限,超限触发**背压**(Historical 写通道阻塞)而非失败。经验值 ≈ 2MiB × Historical 数;大结果集可适当上调                                                                                                                       |
| `maxSubqueryRows`                                  | `druid.server.http.maxSubqueryRows`(默认 **100000**)              | 所有子查询合计可物化行数上限,防 Broker OOM;超限报 Resource limit。重写查询或(谨慎)上调                                                                                                                                           |
| `maxSubqueryBytes`                                 | disabled(不启用)                                                   | 子query 物化字节上限(更新的 guardrail),启用后可观测 `subquery/bytes` 指标                                                                                                                                              |
| `useCache` / `populateCache`                       | `true`                                                          | 读/写 per-segment 缓存;调试或"一次性大报表不污染缓存"时 `populateCache:false`                                                                                                                                           |
| `useResultLevelCache` / `populateResultLevelCache` | `true`                                                          | 读/写 Broker 整查询结果缓存(需集群配置开启该缓存)                                                                                                                                                                       |
| `bySegment`                                        | `false`                                                         | 仅 native;按 segment 返回,排障用                                                                                                                                                                            |
| `finalize`                                         | `true`                                                          | false 时返回聚合中间态(如 HLL sketch 原文),调试/链路传递用                                                                                                                                                             |
| `enableParallelMerge`                              | `true`                                                          | Broker 并行结果合并(需 `druid.processing.merge.useParallelMergePool`);配合 `parallelMergeParallelism`(默认取 `druid.processing.merge.parallelism`)、`parallelMergeInitialYieldRows`、`parallelMergeSmallBatchRows` |
| `useFilterCNF`                                     | `false`                                                         | 过滤器转 CNF 以利用更多位图索引;**谨慎**,CNF 计算本身可能很贵,需实验验证                                                                                                                                                         |
| `secondaryPartitionPruning`                        | `true`                                                          | Broker 依据 secondary 分区(hash/range)按 filter 剪枝 segment;摄入时做了 `CLUSTERED BY` 才有收益                                                                                                                      |
| `serializeDateTimeAsLong(Inner)`                   | `false`                                                         | 结果/内部传输把 DateTime 序列化为 long,微优化                                                                                                                                                                      |
| `cloneQueryMode`                                   | `excludeClones`                                                 | 是否让 Broker 查询 clone Historical(`excludeClones`/`includeClones`/`preferClones`)                                                                                                                       |
| `realtimeSegmentsOnly`                             | `false`                                                         | 仅查实时 segment(调试)                                                                                                                                                                                     |
| `sqlPlannerBloat`                                  | 1000                                                            | Calcite Project 合并阈值(规避 planner 报错的 workaround,一般不动)                                                                                                                                                 |
| `setProcessingThreadNames`                         | `true`                                                          | 线程名带查询信息便于 thread dump;超高 QPS 低耗时场景可关掉省一点开销                                                                                                                                                          |
| `debug`                                            | `false`                                                         | 额外打印异常栈                                                                                                                                                                                              |

### 8.3 SQL planner 参数(`sql-query-context.md`)

| 参数                                                                   | 默认                      | 作用与调优建议                                                                                         |
| -------------------------------------------------------------------- | ----------------------- | ----------------------------------------------------------------------------------------------- |
| `sqlTimeZone`                                                        | UTC                     | 连接/查询时区,影响时间函数与字面量                                                                              |
| `sqlStringifyArrays`                                                 | `true`(JDBC 恒 `false`)  | 结果数组列序列化为 JSON 字符串                                                                              |
| `useApproximateCountDistinct`                                        | **`true`**              | `COUNT(DISTINCT x)` 默认走 HLL(快);要精确计数设 `false`(变 groupBy,代价大)或用 `useGroupingSetForExactDistinct` |
| `useGroupingSetForExactDistinct`                                     | `false`                 | 多个精确 distinct 聚合时用 grouping sets 优化                                                             |
| `useApproximateTopN`                                                 | **`true`**              | 单维 GROUP BY + ORDER BY + LIMIT 自动转**近似 TopN**(快);要精确排名设 `false`                                 |
| `useLexicographicTopN`                                               | `false`                 | 按维度字典序排序时是否仍用 TopN                                                                              |
| `enableTimeBoundaryPlanning`                                         | `false`                 | `MIN/MAX(__time)` 类查询转高效率的 timeBoundary 查询,纯元数据查询建议开                                            |
| `maxNumericInFilters`                                                | -1(不限)                  | 限制"对字符串列做数字 IN 比较"的数量上限,**建议集群设 100**;数字比较走逐值 bound filter 极慢(见 §11.6)                          |
| `inFunctionThreshold`                                                | 100                     | SQL `IN` 值数 ≥ 阈值时改写为 `SCALAR_IN_ARRAY` 表达式(省规划时间,损失部分下推优化)                                      |
| `inFunctionExprThreshold`                                            | 2                       | 表达式层 `scalar_in_array` 的触发阈值(兼容参数)                                                              |
| `inSubQueryThreshold`                                                | 2147483647(禁用)          | 值数达到阈值时把 `IN` 改写为对 inline 表的 JOIN                                                               |
| `useNativeQueryExplain`                                              | `true`                  | EXPLAIN PLAN 输出 native 查询 JSON                                                                  |
| `sqlFinalizeOuterSketches`                                           | `false`                 | DS_HLL 等函数是否在结果层 finalize(兼容旧行为)                                                                |
| `sqlUseBoundAndSelectors`                                            | `false`                 | 27.0+ 默认用 equals/null/range filter(数组过滤必须保持 `false`)                                            |
| `sqlReverseLookup` / `sqlReverseLookupThreshold` / `sqlPullUpLookup` | `true` / 10000 / `true` | LOOKUP 函数的反向改写与上拉优化                                                                             |
| `enableJoinLeftTableScanDirect`                                      | `false`                 | 默认"左表简单 scan+filter 先在 Broker 执行为子查询再 join";置 `true` 把 join 下推到数据节点(省 Broker 合并,数据节点压力大时慎用)     |

### 8.4 按查询类型的参数

**TopN**
| 参数 | 默认 | 说明 |
|---|---|---|
| `minTopNThreshold` | 1000 | 每 segment 本地保留的 top-K 行数。结果不准(漏真 top)时可调大;值越大越接近精确但越慢 |

**Timeseries**
| 参数 | 默认 | 说明 |
|---|---|---|
| `skipEmptyBuckets` | `false` | 关闭零填充,只返回有数据的桶(结果集更小更快) |

**Scan**(SQL 无聚合查询的载体)
| 参数 | 默认 | 说明 |
|---|---|---|
| `maxRowsQueuedForOrdering` | 100000 | 按 `__time` 排序时结果行数上限(优先队列策略) |
| `maxSegmentPartitionsOrderedInMemory` | 50 | 排序时单 Historical 并发打开的分区数上限(n 路归并策略) |
| (body)`batchSize` | 20480 | 每批返回行数,native 直接调 scan 时可调 |
| (body)`offset` | 0 | 深翻页代价高(跳过行仍需生成),见 §11.2 |

**GroupBy**(详见 groupBy 官方文档;下列为 query context 覆盖项,集群属性为 `druid.query.groupBy.*`)
| 参数 | 默认 | 说明 |
|---|---|---|
| `maxOnDiskStorage` | 0(禁用) | 单查询聚合 spill 磁盘上限(字节)。高基数聚合 OOM 时开启(集群属性/context 均可) |
| `maxMergingDictionarySize` | 0=自动(min(30% 堆/numMergeBuffers, 1GB)) | 查询级字符串字典堆上限 |
| `maxSelectorDictionarySize` | 0=自动(min(10% 堆/numMergeBuffers, 1GB)) | segment 级字典堆上限 |
| `numParallelCombineThreads` | 1(禁用) | Historical 上聚合结果的**并行合并**线程数(>1 开启;实际取 min(该值, `druid.processing.numThreads`));开启后每查询占 **2 个 merge buffer**。高基数重聚合的提速利器 |
| `intermediateCombineDegree` | 8 | 并行合并树的叉数 |
| `forceLimitPushDown` | `false` | ORDER BY 含非分组列时也下推 LIMIT(结果近似,换速度) |
| `applyLimitPushDownToSegment` | `true`(context) | LIMIT 继续下推到 segment 扫描层 |
| `groupByIsSingleThreaded` | `false` | 单线程合并(兼容开关) |
| `bufferGrouperInitialBuckets` / `bufferGrouperMaxLoadFactor` | 0(→1024) / 0(→0.7) | off-heap 哈希表初始桶/负载因子(hash 碰撞严重时调) |
| `groupByEnableMultiValueUnnesting` | `true` | 多值列做分组键时的隐式 unnest 开关 |
| `deferExpressionDimensions` | `fixedWidthNonNumeric` | 表达式维度求值推迟到 merge 阶段(仅向量化 groupBy 生效),减少行级表达式计算 |

**Join 过滤器下推**
| 参数 | 默认 | 说明 |
|---|---|---|
| `enableJoinFilterPushDown` | `true` | join 查询过滤下推,减少参与 join 的行 |
| `enableJoinFilterRewrite` | `true` | 非基表列的 filter 重写为基表 filter |
| `enableJoinFilterRewriteValueColumnFilters` | `false` | 非键列 filter 重写(需扫右表,默认关) |
| `enableRewriteJoinToFilter` | `true` | join 部分/完全转为基表运行时 filter |
| `joinFilterRewriteMaxSize` | 10000 | filter 重写用关联值集合的内存上限 |

### 8.5 MSQ/DART 相关 context(DART 会用到的 MSQ 参数,§10 详述)

`finalizeAggregations`(true)、`includeSegmentSource`(REALTIME)、`removeNullBytes`(false)、`maxConcurrentStages`(2)、`maxNonLeafWorkers`(1)、`sqlJoinAlgorithm`(broadcast|sortMerge)、`targetPartitionsPerWorker`(1)。

### 8.6 "老文章里有、当前版本没有"的参数(避免踩坑)

以下参数在老博客/旧版本文档中出现,**在 35.0.1 源码中已不存在**,照抄会无效(或被静默忽略):`numParallelFetches`、`chunkPeriod`、`useFilteringJoin`、`maxQueryValueLength`、`useApproximateTimeGranularity`、`enableUnnest`。引用二手资料时务必对照所用版本的官方 query context 文档。

---

## 9. 向量化执行(Vectorization)调优

### 9.1 三个参数与语义(源码 `QueryContexts.java`)

| 参数                        | 默认      | 语义                                                                                                                      |
| ------------------------- | ------- | ----------------------------------------------------------------------------------------------------------------------- |
| `vectorize`               | `true`  | `false`=禁用;`true`=**能用就用,不能用逐 segment 静默回退行式**;`force`=不能用直接报错(`Cannot vectorize!`),仅测试用(实时 segment 永不可向量化,生产 force 必炸) |
| `vectorSize`              | **512** | 向量批大小(每次处理的行数批次)                                                                                                        |
| `vectorizeVirtualColumns` | `true`  | 虚拟列(表达式列)是否参与向量化,取值同 `vectorize`                                                                                        |

**只有 GroupBy 和 Timeseries 会向量化**;TopN、Scan、Search 等**完全忽略** `vectorize` 参数(即使 `force`)。

### 9.2 向量化生效条件清单(全部满足才生效)

1. 查询级 filter 可走位图索引或有向量化 row-matcher(`selector`、`bound`、`in`、`like`、`regex`、`search`、`and`、`or`、`not`);filtered aggregator 里的 filter 同理;
2. 所有聚合器有向量化实现:`count`、`longSum/doubleSum/floatSum`、`longMin/longMax/doubleMin/doubleMax/floatMin/floatMax`、`longFirst/longLast/doubleFirst/doubleLast/floatFirst/floatLast/stringFirst/stringLast`、`longAny/doubleAny/floatAny/stringAny`、`doubleMean`、`hyperUnique`、`cardinality`、`filtered`、`grouping`,以及扩展中的 `approxHistogram`/`approxHistogramFold`/`fixedBucketsHistogram`、variance 系列、DataSketches(theta/hll/quantiles/kll)。**没有**向量化实现的常见聚合:字符串 `min/max`、JavaScript 聚合;
3. 虚拟列表达式可向量化:常量/列引用、数值与字符串 `cast`、算术(`+ - * / % ^`)、逻辑与比较、约 40 个数学函数(`abs/ceil/floor/log/pow/sqrt/…`)、`timestamp_floor`(常量 granularity)、`isnull/notnull`、`nvl`、`concat`/`+`、`parse_long`;
4. GroupBy:维度 spec 必须是"默认"形式(无 extractionFn、无 filtered dimension spec),**且无多值列**;
5. Timeseries:不允许 `descending`;
6. 只作用于**不可变 segment**(Historical 上的;实时 segment 一律行式);
7. 数据源必须是 table(join、子查询、lookup、inline 都不行)。

### 9.3 静默回退:调优者最需要知道的行为

`vectorize=true`(默认)时,**每个 segment 独立判定**:条件不满足就静默用行式游标,不报警、不改计划。常见"以为向量化了其实没有"的写法:

- GROUP BY 一个多值列;
- `SUM(CASE WHEN … END)` 这类复杂表达式聚合(表达式含不支持向量的函数);
- GROUP BY 带提取函数的维度;
- 谓词里用了向量化不支持的 filter 形态。

### 9.4 调优手法

1. **先看翻译**:EXPLAIN PLAN 确认走的是 groupBy/timeseries(而不是 topN/scan);
2. **查询改写以"落入"向量化集合**:如把字符串 min/max 换成 `stringFirst`(配合排序语义评估正确性)或前置到摄入;复杂表达式尽量拆成数值算术与受支持函数;避免多值列直接 GROUP BY(先 `MV_FILTER`/unnest 处理或摄入期展平);
3. **`vectorSize` 实验**:默认 512;列宽较窄的纯数值聚合可以试 1024/2048,列多表达式重的有时 256 更好——用 §13 指标对比 CPU 时间;
4. **保持 `vectorize=true` 即可**;`force` 只用于测试验证;
5. 若查询无法向量化且高基数重聚合,考虑 DART(§10,DART 走 MSQ 执行模型,不依赖该参数)。

### 9.5 验证向量化是否生效

- `query/segment/time` 指标带 **`vectorized` 维度**(`true/false`)——Historical 指标里直接统计向量化占比;
- 测试环境发 `vectorize=force` 的同款查询:能跑 = 满足向量化条件;
- `query/cpu/time` 对比:向量化生效的典型收益是 CPU 明显下降(Druid 0.16 引入向量化引擎时的官方基准为 1.3–3x,后续表达式向量化、TopN 虚拟列等持续增强)。

---

## 10. DART 引擎(`engine=msq-dart`)

### 10.1 是什么、何时用

DART(Distributed Asynchronous Runtime Topology)是 **MSQ 的一个 profile**:Broker 当 controller、Historical 当 worker,SELECT 查询整条流水线全并行(含 join、聚合、合并),替代 native 引擎的 scatter-gather 合并模型。**官方定位:实验性**,适合:

- **大 JOIN**(并行 sort-merge join);
- **高基数精确 GROUP BY**;
- **高基数精确 COUNT DISTINCT**。

**不适合**:轻量高并发点查(native 的 per-segment 直扫更快、并发吞吐更高——设计者的基准里轻查询 native 反而快一倍左右)、需要查询缓存/优先级/laning 的场景(见 10.4)。

**实测参考**(DART 作者 gianm 在设计 issue #17139 的基准,TPC-H 风格 100 万亿级 wikipedia):

- Q6(重 join+聚合):native 284.3s → DART 42.7s(**约 6.7x**);
- Q5:native 37.2s → DART 25.1s;
- 轻查询 Q3/Q4:native 185ms/611ms,DART 414ms/1312ms(native 更快);
- 25 并发点查:native 1529 QPS,DART 730 QPS。
  Imply 官方博客称重查询场景"最高 2000% 提升"。结论:**重查询用 DART,轻并发留给 native**。

### 10.2 启用与配置

```properties
# _common/common.runtime.properties(全集群)
druid.msq.dart.enabled = true
```

Broker(controller 侧):
| 属性 | 默认 | 说明 |
|---|---|---|
| `druid.msq.dart.controller.concurrentQueries` | 1 | Broker 上并发 controller 数,超出排队。**注意**:全集群该值总和超过单个 Historical 的 `worker.concurrentQueries` 会导致查询互相卡死 |
| `druid.msq.dart.query.context.targetPartitionsPerWorker` | 1(多线程关闭) | **调优关键**:设为 Historical 的 `druid.processing.numThreads`,把每个 Historical 的并行度拉满 |

Historical(worker 侧):
| 属性 | 默认 | 说明 |
|---|---|---|
| `druid.msq.dart.worker.concurrentQueries` | = merge buffer 数 | 每 Historical 并发 worker 数。官方建议保持默认;如需调整应 ≥ Broker 侧 `controller.concurrentQueries` 总和,且不超过 merge buffer 数 |
| `druid.msq.dart.worker.heapFraction` | 0.35 | 所有 DART 查询可用的堆比例 |

### 10.3 运行方式与专属 context 参数

入口就是 `/druid/v2/sql`(或控制台引擎选 **SQL (Dart)**),见 §3.3 示例。DART 额外支持这些 MSQ context 参数:

| 参数                          | 默认          | 说明                                                                        |
| --------------------------- | ----------- | ------------------------------------------------------------------------- |
| `targetPartitionsPerWorker` | 1           | 每 worker 的分区数 = 查询内并行度(也可用上面的集群属性统一设)                                     |
| `sqlJoinAlgorithm`          | `broadcast` | `broadcast`(广播哈希 join)/ `sortMerge`(排序归并 join)。**左表含实时数据时必须 `sortMerge`** |
| `includeSegmentSource`      | `REALTIME`  | `NONE`=只查已发布 segment(不含实时);`REALTIME`=包含实时任务数据                            |
| `maxConcurrentStages`       | 2           | 查询内并发 stage 数;更高提升流水线并行但每 stage 内存更少                                      |
| `maxNonLeafWorkers`         | 1           | 叶子 stage 之外的 worker 数(1=scatter-gather 式)                                 |
| `finalizeAggregations`      | `true`      | 复杂聚合是否在结果层 finalize                                                       |
| `removeNullBytes`           | `false`     | MSQ 遇字符串含空字节会抛 `InvalidNullByteFault`,置 true 自动剔除                         |

典型调优上下文(重 join、含实时数据):

```json
{
  "query": "SELECT … FROM orders o JOIN users u ON o.user_id = u.id GROUP BY …",
  "context": {
    "engine": "msq-dart",
    "sqlJoinAlgorithm": "sortMerge",
    "targetPartitionsPerWorker": 15,
    "maxConcurrentStages": 3,
    "timeout": 300000
  }
}
```

### 10.4 已知限制(35.x)

- **不用查询缓存**;**不做优先级/laning**(多租户 QoS 体系对它无效);
- TopN 恒为精确(`useApproximateTopN` 不生效);
- **JDBC 不支持**:`engine` 参数经 JDBC 传递会被忽略(必须走 HTTP `/druid/v2/sql`);
- 实时数据上的广播 join 不支持(左表实时 → 必须 sortMerge);
- 实时 segment 读复杂类型(如 HLL sketch)可能 NPE(issue #18340);
- `controller.concurrentQueries` 配置过高会导致查询互卡(无保护校验);
- 实验性成熟度:升级/生产引入需自行验证。

### 10.5 版本时间线(外部资料核实)

| 版本                 | DART 相关变化                                                                      |
| ------------------ | ------------------------------------------------------------------------------ |
| 31.0.0(2024-10-22) | 实验性引入;当时用专用端点 `/druid/v2/sql/dart`                                             |
| ~33/34             | 默认包含实时任务数据;统一改走 `/druid/v2/sql` + `engine=msq-dart`(旧专用端点废弃)                   |
| 36.0.0(2026-02)    | 查询报告(query reports)与留存配置(`druid.msq.dart.controller.maxRetainedReportCount` 等) |
| 37.0.0(2026-05)    | 实验性 `sys.queries` 系统表(查询运行/历史信息)                                               |

---

## 11. 场景化调优 Recipes

### 11.1 高并发交互式 Dashboard

```json
{
  "query": "SELECT countryName, COUNT(*) cnt, SUM(added) FROM wikipedia WHERE __time >= CURRENT_TIMESTAMP - INTERVAL '1' DAY AND channel = '#en.wikipedia' GROUP BY 1 ORDER BY 2 DESC LIMIT 10",
  "context": {
    "sqlQueryId": "dash-main-{{tile}}",
    "timeout": 10000,
    "priority": 10,
    "lane": "interactive",
    "useApproximateTopN": true,
    "populateCache": true, "useCache": true
  }
}
```

配套动作:

- 查询形状:**单维 GROUP BY + ORDER BY 聚合列 + LIMIT** → 自动 TopN(近似、每 segment top-`minTopNThreshold` 上送);纯时间桶 → timeseries;只查明细小样本 → scan + LIMIT(LIMIT 对 scan/topN 恒下推);
- 时间过滤写可翻译成 `intervals` 的形式(`__time >= TS`、`__time BETWEEN`、`TIME_IN_INTERVAL(__time, '…/…')`),用 EXPLAIN 验证;
- 缓存:批量数据 → Historical per-segment 缓存(默认开)为主;纯批量/低变更数据集 → Broker 整查询缓存(`useResultLevelCache`);**含子查询的查询、join 不吃(部分)缓存**——高频面板避免子查询;
- 集群:慢报表降 `priority` + 单独 `lane`,或 Router 分流到独立 Broker 组(§7);segment 尺寸保证单 segment 计算量 ~≤500ms(`query/segment/time` 监控);连接池按"每 Historical 50 查询 + 10 非查询"起步(§12)。

### 11.2 大结果集导出/拉取

优先级从高到低:

1. **`/druid/v2/sql` + `arrayLines`/`csv` + 只选需要列 + 不 ORDER BY(或仅 `ORDER BY __time`)**——流式 NDJSON 可边收边写盘;
2. **超大规模/怕断连** → `/druid/v2/sql/statements` + `selectDestination: durableStorage`,按 page 拉取(§5);>3000 行即建议 durableStorage;
3. **必须 JDBC** → avatica-protobuf + 服务端调大 `maxRowsPerFrame`(§4.2);
4. **native 直接发 scan 到 Historical**(可绕过 Broker,多线程并行拉多个 Historical)——运维级导出手段。

避免:深 `OFFSET` 翻页(跳过行仍要生成,且迫使查询用 scan/groupBy 而非 topN/timeseries);导出查询记得 `populateCache:false` 防止污染缓存;给导出查询设低 `priority`。

### 11.3 高基数精确 GROUP BY / COUNT DISTINCT

- 首选试 **DART**:`engine=msq-dart` + `targetPartitionsPerWorker = numThreads`(§10);精确 distinct 本就是它的强项;
- native 路线:
  - `useApproximateCountDistinct: false`(COUNT DISTINCT 精确化)+ 接受 groupBy 代价;
  - 防护与提速:`maxOnDiskStorage` 开 spill(集群属性或 context)、`numParallelCombineThreads` > 1 开并行合并(注意每查询 2 个 merge buffer)、必要时 `forceLimitPushDown: true`(近似换速度);
  - `maxMergingDictionarySize`/`maxSelectorDictionarySize` 字典超限触发 spill,spill 满再报 Resource limit——逐级放宽而不是一上来就加堆;
  - 评估近似是否可接受:`APPROX_COUNT_DISTINCT_DS_HLL(x)` 永远走 sketch,比关闭 `useApproximateCountDistinct` 的精确路径便宜一个量级;
  - 保证向量化条件满足(§9)——`COUNT/SUM/MIN/MAX` 类聚合 + 单值维度,收益直接。

### 11.4 大表 JOIN

1. **最佳 join 是没有 join**:摄入期预 join(宽表)、lookup 维表、或数据建模期打平;
2. join 下的**子查询是性能杀手**(planner 不做谓词下推穿越 join;类型不匹配/右侧表达式会隐式插入子查询)——EXPLAIN 检查;
3. 谓词尽量写在**基表(左表)侧**、避免逗号 join;
4. `enableJoinLeftTableScanDirect: true` 可把"左表 scan+filter"的 join 下推进数据节点(省 Broker);
5. 大-大 join:上 **DART + `sqlJoinAlgorithm: sortMerge`**(并行排序归并,这是 DART 的招牌场景);
6. join 过滤下推参数组(`enableJoinFilterPushDown` 等,§8.4)默认已开,一般不动;`joinFilterRewriteMaxSize` 防重写内存失控。

### 11.5 实时数据查询(近实时面板)

- 实时 segment **不可向量化**——查询会混合执行(Historical 部分向量化,实时部分行式),属正常;
- 缓存策略:per-segment 缓存(Historical)天然适合"实时+历史"混合查询(历史段命中缓存、实时段现算,Broker 合并);**不要**对实时占比高的查询用整查询缓存(数据不断失效);
- `includeSegmentSource`(DART)与 `realtimeSegmentsOnly`(native,调试)控制实时数据可见性;
- 摄入侧配合:Kafka producer 按 tenant/dim hash 分区,让"filter + 时间"的剪枝更有效(§11.1)。

### 11.6 大 IN 列表

- **字符串列用字符串值比较**:`WHERE s IN ('123','456')`,不要 `WHERE s IN (123,456)`——数字对字符串列会退化为逐值 bound filter,社区实测 100 万行 segment 上 1000 值 IN:2165ms vs 字符串 38ms(5 百万行:9542ms vs 56ms);用 `maxNumericInFilters`(建议 100)在集群侧强制拦截;
- 值数 ≥ `inFunctionThreshold`(100)自动改写 `SCALAR_IN_ARRAY`(规划更快,但部分下推优化失效);
- 超大值集(数千以上)考虑把值集落成临时表用 JOIN/子查询,或摄入期物化为维度并 `WHERE dim =`;
- `inSubQueryThreshold`(默认禁用)定义"IN 改写为 inline join"的阈值,按需实验。

### 11.7 子查询与 Resource limit

- 默认 `maxSubqueryRows=100000`:子查询合计行数超限直接报错(防 Broker OOM),伴随指标 `subquery/bytes`(启用 bytes guardrail 时);
- 例外:**groupBy(groupBy(…)) 可流式**,不受该限制——重写技巧之一是让外层也是聚合;
- 常见改写:子查询变 join、子查询结果预计算成表、`WHERE x IN (SELECT …)` 本身就是 inner join(注意 §11.4);
- 确需大子查询:临时上调 context `maxSubqueryRows`(不能超过 Broker 系统上限),同时盯 Broker 堆与 GC。

### 11.8 查询形状通用守则(官方 tips-good-queries 精要)

① 只 SELECT 需要的列;② 永远带 `__time` 过滤且写成可转 `intervals` 的形式;③ 少用不等式过滤(资源密集);④ 拆长查询(大区间按月/周分段,客户端再合并);⑤ 尽量单表;⑥ GROUP BY 低基数列、先 filter 再 group;⑦ 减少表达式/函数层级(能摄入期算的别查询期算);⑧ ORDER BY 非时间列仅聚合查询支持(非聚合只能 `ORDER BY __time`)。

---

## 12. 集群侧配套调优(查询视角速览)

| 配置                                        | 建议起点(官方 basic-cluster-tuning)                                                                                       | 对查询的意义                                                                                               |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `druid.processing.numThreads`(Historical) | **核数 − 1**                                                                                                          | 单机 segment 并行度;DART 的 `targetPartitionsPerWorker` 参照它                                                |
| `druid.processing.buffer.sizeBytes`       | 500MiB(自动默认上限 1GiB)                                                                                                 | off-heap 中间结果/groupBy 哈希表                                                                            |
| `druid.processing.numMergeBuffers`        | max(2, numThreads/4);经验 1:4                                                                                         | groupBy 并发上限:Historical 每查询 1 个(并行合并 2 个);Broker 嵌套子查询 1~2 个、subtotals 再 +1;**也是 DART worker 并发默认值** |
| Historical 直接内存                           | (numThreads + numMergeBuffers + 1) × buffer                                                                         | 规划 JVM MaxDirectMemorySize                                                                           |
| `druid.broker.http.maxQueuedBytes`        | ≈ 2MiB × Historical 数(默认 max(25MB, 堆 2%))                                                                           | Broker 背压;查询级用 context `maxQueuedBytes` 微调                                                           |
| Broker 堆                                  | 小集群 4–8GiB;百节点级 30–60GiB                                                                                            | 子查询物化、合并、Avatica 帧、缓存都在堆上                                                                            |
| Historical 堆                              | 0.5GiB × 核 + 2×lookup 总量 + 缓存                                                                                       | —                                                                                                    |
| HTTP 连接池                                  | 每 Historical/Task:50 查询 + 10 非查询;`druid.server.http.numThreads` ≥ 全部 Broker 的 `druid.broker.http.numConnections` 之和 | 并发查询槽位;池太小集群空转,太大 OOM                                                                                |
| Broker : Historical                       | ≈ 1 : 15                                                                                                            | —                                                                                                    |
| segment 尺寸                                | 300–700MB / ~5M 行;单段计算 ≤500ms                                                                                       | 剪枝粒度与并行度的平衡;配合 secondary 分区(`CLUSTERED BY`/range)让 `secondaryPartitionPruning` 生效                    |
| 缓存                                        | Historical per-segment 默认开(caffeine,容量默认 min(1GiB, JVM 最大内存 10%));Broker 段缓存仅小集群(<5 节点);整查询缓存适合批量/低变更负载             | TopN/timeseries 收益最大;groupBy 若瓶颈在 Broker 合并则缓存无感                                                     |

多租户:datasource-per-tenant(隔离好、查询快)vs 共享表 + `tenant_id` 分区(Kafka 按租户 hash 分区);Historical 分层热/冷 + Router 把交互/报表路由到不同 Broker 组;慢查询 `priority` 降级 + lane 限流。

---

## 13. 可观测性与排障

**核心指标**(Historical/Broker):

- `query/time`、`query/bytes`、`query/cpu/time`(目标:交互查询 `query/time` < 1s);
- `query/segment/time`(**含 `vectorized` 维度**;单段几百 ms 内)——定位慢 segment & 向量化覆盖率;
- `subquery/bytes` —— 子查询物化量(启用 bytes guardrail 后);
- `query/timeout/count`、`query/cancelled/*`;
- `cache/*`(命中率、驱逐)。

**排障路线**:

1. `EXPLAIN PLAN FOR <SQL>` → 看 native 翻译(intervals?topN 还是 groupBy?隐式子查询?join 形态?);
2. 有疑义看 **request log**(记录实际执行的 native 查询原文);
3. `query/segment/time` 找慢 segment → `bySegment: true`(native)直接定位;检查该 segment 是否过大/未剪枝/未向量化;
4. GroupBy "Resource limit exceeded" → 对照 §8.4 字典/buffer/spill 三件套,通常是 `maxMergingDictionarySize` 或 buffer 满(`maxOnDiskStorage=0` 时直接失败);
5. Broker 侧:子查询行数/字节、合并耗时(并行合并参数)、`maxQueuedBytes` 背压日志;
6. 查询卡死/堆积:`GET /druid/v2/sql/queries`、`DELETE` 取消;检查 `druid.query.scheduler.*` 与优先级;
7. DART 查询:36.0.0+ 有 query reports;35.x 用 `GET /druid/v2/sql/statements/{id}?detail=true` 看 stage/counter(仅 statements 路径)。

---

## 14. 附录

### 14.1 端点速查

```
POST   /druid/v2/sql                      SQL(JSON/文本;engine context 可选 native|msq-task|msq-dart|view)
DELETE /druid/v2/sql/{sqlQueryId}         取消 SQL
GET    /druid/v2/sql/engines              可用引擎
GET    /druid/v2/sql/queries              运行中 SQL
POST   /druid/v2/sql/avatica              JDBC(Avatica JSON)
POST   /druid/v2/sql/avatica-protobuf     JDBC(Avatica Protobuf)
POST   /druid/v2/sql/statements           异步 SQL(executionMode=async)
GET    /druid/v2/sql/statements/{id}      状态(?detail=true)
GET    /druid/v2/sql/statements/{id}/results   结果分页
DELETE /druid/v2/sql/statements/{id}      取消
POST   /druid/v2                          Native(JSON/Smile;?pretty)
DELETE /druid/v2/{queryId}                取消 native
POST   /druid/v2/candidates               (Broker)查询扇出目标
```

### 14.2 Avatica 配置速查(`druid.sql.avatica.*`)

`enable=true` · `maxConnections=25` · `maxStatementsPerConnection=4` · `minRowsPerFrame=100` · `maxRowsPerFrame=5000` · `connectionIdleTimeout=PT5M` · `fetchTimeoutMs=5000`

### 14.3 参考资料出处

- 官方文档(与源码 35.0.1 内置 docs 一致):query-context-reference、sql-query-context、query-context(set context)、sql-api、sql-jdbc、query-execution、caching/using-caching、multitenancy、groupbyquery、scan-query、topnquery、tips-good-queries、sql-translation(best practices)、basic-cluster-tuning、metrics、design/router、querying/dart —— 均可从 `druid.apache.org/docs/latest/…` 访问;
- DART:设计 issue 与基准 `github.com/apache/druid/issues/17139`;引入博客 `imply.io/blog/introducing-apache-druid-31-0/`;各版本 release notes(31.0.0 引入 → 36.0.0 reports → 37.0.0 sys.queries);
- Avatica 性能:issue #12930(JDBC vs HTTP)、PR #10880(minRowsPerFrame)、PR #13196(fetchTimeoutMs 异步拉取);
- IN filter:PR #12195(数字 IN 对字符串列的退化实测);
- 源码锚点:`sql/src/main/java/org/apache/druid/sql/http/SqlResource.java`、`sql/…/avatica/{DruidAvaticaJsonHandler,DruidAvaticaProtobufHandler,DruidMeta,AvaticaServerConfig}.java`、`multi-stage-query/…/msq/sql/resources/SqlStatementResource.java`、`multi-stage-query/…/msq/dart/controller/sql/DartSqlEngine.java`、`sql/…/calcite/run/{NativeSqlEngine,MSQTaskSqlEngine}.java`、`server/…/QueryResource.java`、`processing/…/query/QueryContexts.java`。

---

*报告完。参数名与默认值以 Druid 35.0.1 源码为准;升级版本时请对照官方 query-context-reference 与 release notes 复核(尤其 DART 相关,仍在快速演进)。*
