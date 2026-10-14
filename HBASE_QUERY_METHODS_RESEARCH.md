# HBase 查询方式全景调研报告

> **调研日期**：2026-10-14
> **基线版本**：Apache HBase master 源码（3.x/4.0 血统）
> **调研方式**：基于 HBase 官方源码仓库（客户端 API、过滤器、协处理器、REST/Thrift 网关逐文件核对）、仓内官方参考文档（hbase-website/）、`hbase-default.xml` 默认配置，以及 2026 年 10 月核实的外部生态状态（Phoenix/Hive/Spark/JanusGraph 官方文档、GitHub/Maven Central/Apache Attic）。所有数值与语法均来自源码与官方文档，非记忆性内容。

---

## 目录

- [0. 前置：数据模型决定查询能力边界](#0-前置数据模型决定查询能力边界)
- [1. Java Native API —— 最核心的查询方式](#1-java-native-api--最核心的查询方式)
- [2. HBase Shell（JRuby）—— 最常用的交互查询界面](#2-hbase-shelljruby--最常用的交互查询界面)
- [3. 过滤器（Filters）—— 服务端条件查询的核心机制](#3-过滤器filters--服务端条件查询的核心机制)
- [4. 协处理器 Endpoint —— 服务端自定义查询/聚合](#4-协处理器-endpoint--服务端自定义查询聚合)
- [5. REST 网关（hbase-rest）](#5-rest-网关hbase-rest)
- [6. Thrift 网关（hbase-thrift）](#6-thrift-网关hbase-thrift)
- [7. 批处理查询：MapReduce / Spark（离线全量/大规模扫描）](#7-批处理查询mapreduce--spark离线全量大规模扫描)
- [8. SQL 与生态查询层（HBase 之上）](#8-sql-与生态查询层hbase-之上)
- [9. 全景对比：查询方式一览（接入层、执行层、生态层）](#9-全景对比查询方式一览接入层执行层生态层)
- [10. 约束、规格与选型建议汇总](#10-约束规格与选型建议汇总)
- [附：调研源码证据锚点](#附调研源码证据锚点)

---

## 0. 前置：数据模型决定查询能力边界

理解 HBase 的查询方式，必须先理解其数据模型约束——**所有查询方式的优劣都由此推导**：

| 概念               | 说明                                   | 对查询的影响                            |
| ---------------- | ------------------------------------ | --------------------------------- |
| Row Key          | 未解释的字节串，全表**字典序**排序                  | 唯一的索引维度；查询模式由建表时的 rowkey 设计决定     |
| Column Family    | 建表时声明，物理上与存储属性绑定                     | 查询的最小投影/调优单位                      |
| Column Qualifier | 无需预声明，任意字节                           | 宽表/稀疏表；但**列元数据不存储**，想知道"有哪些列"只能全扫 |
| Cell             | `{row, column, version}` 四元组，值为未解释字节 | 多版本机制是"免费"的时序查询能力                 |
| Timestamp        | 默认 RegionServer 写入时间，可自定义            | 时间范围查询天然支持                        |

**排序规则**（官方文档明确）：所有查询结果按 row → CF → qualifier → timestamp（**降序**，最新在前）排序。

**核心边界**（官方文档原文确认）：

- **没有二级索引**——官方 Reference Guide 对二级索引的建议是"模式"（双写、汇总表等），而非特性
- **不支持 SQL**（原生）；**不支持 Join**（文档原话："it doesn't [support joins]"，需自己在应用层或 MR 中实现）
- 行级原子性，无跨行事务（原生；Phoenix+Tephra/Omid 可补）
- 建议规格：单 cell ≤ 10MB（MOB 可到 50MB）、每表 1~3 个 CF（勿模仿 RDBMS）、Region 建议 10~50GB、CF 名尽量短

---

## 1. Java Native API —— 最核心的查询方式

所有其他方式（Shell/REST/Thrift/Spark）本质都是包装了这里的能力。

### 1.0 服务端读路径：一切查询的执行模型（前置知识）

无论走哪种接入方式，查询最终都在 RegionServer 上按同一路径执行。理解这条路径，才能解释后文所有参数、过滤器与性能建议的"为什么"：

```text
Client RPC (Get/Scan)
  → RegionScanner（目标 Region，行级归并；绑定 MVCC read point 保证一致性快照）
      → 每个 CF（Store）一个 StoreScanner
          → KeyValueHeap：MemStore Scanner + N × StoreFileScanner 归并
              （按序逐 Cell 吐出——天然实现"最新版本优先"的遍历顺序）
          → 每个 StoreFileScanner 读取前先查 Bloom（ROW / ROWCOL）决定是否跳过整个文件
          → 块读取优先走 Block Cache（默认占堆 40%，`hfile.block.cache.size`）
      → 过滤链：TTL 过期 → 删除标记遮蔽 → 列匹配/版本计数（含 CF max-versions）
      → 过滤器（§3）在以上检查全部通过后叠加执行（filterCell 看不到已删/过期数据）
  → 结果按 row → CF → CQ → ts 降序返回
```

由这条路径可直接推导全报告的关键结论：

- **为什么 rowkey 设计决定一切**：只有 rowkey 定义 HFile 的物理有序性，范围扫描才能退化为顺序读
- **为什么 Bloom 省磁盘 IO**：ROW 适配按行点查、ROWCOL 适配指定 `cf:cq` 的点查，未命中即跳过整个文件
- **为什么列投影影响性能**：稀疏选列在块内触发 seek（§1.2 的 `HINT_LOOKAHEAD` 即缓解此问题）
- **为什么 raw scan 语义特殊**：它跳过删除标记与 TTL 过滤，直接暴露存储层原始状态（含墓碑）
- **长扫描的干扰面**：scan 占用读队列、驱逐 block cache——治理手段见 §10 的队列隔离

### 1.1 Get：行点查

```java
Table table = connection.getTable(TableName.valueOf("users"));

// 最简：取整行最新版本
Get get = new Get(Bytes.toBytes("row1"));
Result r = table.get(get);
byte[] v = r.getValue(CF, CQ);   // 不存在则 r.isEmpty()

// 多版本 + 时间范围 + 列裁剪
Get get2 = new Get(Bytes.toBytes("row1"))
    .addColumn(CF, CQ)          // 列投影；addFamily 取整个 CF
    .readVersions(3)            // 最近 3 个版本
    .setTimeRange(t1, t2);      // [t1, t2) 左闭右开
Result r2 = table.get(get2);
List<Cell> versions = r2.getColumnCells(CF, CQ);  // 新→旧

// 存在性检查（服务端判断，不传数据）
boolean exists = table.exists(new Get(row));      // 底层 setCheckExistenceOnly

// 批量点查（顺序保持；部分失败=对应槽位为 null 且抛异常）
Result[] rs = table.get(Arrays.asList(g1, g2, g3));
```

**关键语义/约束**（源自 `Get.java`/`Query.java` javadoc）：

- 时间范围 `[minStamp, maxStamp)` **左闭右开**；`setTimestamp(ts)` 等价于 `[ts, ts+1)`
- `readVersions(n)` 要求 n>0 否则抛 IOException；实际返回还受 CF 的 `VERSIONS` schema 设置约束（默认 1）
- `addFamily` 与 `addColumn` 对同一 CF 互相覆盖（后者胜出）
- `setCheckExistenceOnly(true)`：服务端存在性判定，结果经 `Result.getExists()` 读取
- `setLoadColumnFamiliesOnDemand(true)`：配合列过滤可大幅省 IO，但**可能产生不一致结果**（官方 javadoc 警告）
- 默认读取 `READ_COMMITTED` 隔离级别

**优劣**：✅ 毫秒级、走 block cache+bloom、最廉价；❌ 只能按完整 rowkey 点查，无法按列值/前缀模糊查（前缀要用 Scan 或 Get+filter）。

### 1.2 Scan：范围查询（最重要、参数最多）

```java
// 基础范围扫描（startRow 含、stopRow 不含 —— 默认语义）
Scan scan = new Scan()
    .withStartRow(Bytes.toBytes("row-a"))     // 含（可传 false 改为不含）
    .withStopRow(Bytes.toBytes("row-z"))      // 不含（可传 true 改为含）
    .addColumn(CF, CQ);                       // 列投影
try (ResultScanner scanner = table.getScanner(scan)) {
    for (Result r : scanner) { ... }          // scanner 必须关闭（lease）
}

// 前缀扫描（内部计算 stopRow=prefix 的"下一个键"）
scan.setStartStopRowForPrefixScan(Bytes.toBytes("row"));  // 替代已废弃的 setRowPrefixFilter

// 反向扫描：startRow 为上界、向 rowkey 减小方向遍历
Scan rev = new Scan().withStartRow(high).withStopRow(low).setReversed(true);

// 大行防 OOM：允许部分结果 / 按列分页
Scan big = new Scan().setBatch(100)                    // 每次 next 最多 100 cells
                    .setAllowPartialResults(true);     // 不在客户端拼装整行
```

**Scan 全参数规格表**（源自 `Scan.java`，注意本分支 `setSmall()` 已移除）：

| 参数                                                   | 语义                                                          | 默认值                                                                           |
| ---------------------------------------------------- | ----------------------------------------------------------- | ----------------------------------------------------------------------------- |
| startRow / includeStartRow                           | 起始行（含，可改为不含；行不存在则从下一个键开始）                                   | 空（表头）/ true                                                                   |
| stopRow / includeStopRow                             | 终止行（**不含**，可改含）                                             | 空（表尾）/ false                                                                  |
| reversed                                             | 反向遍历（服务端用 ReversedRegionScanner，filter 自动 setReversed）      | false                                                                         |
| maxVersions (readVersions/readAllVersions)           | 每列返回版本数                                                     | 1                                                                             |
| timeRange / timestamp / CF 级 timeRange               | 版本时间过滤（CF 级优先于全局）                                           | 全部时间                                                                          |
| **caching**                                          | 每次 RPC 预取行数                                                 | 配置 `hbase.client.scanner.caching` = **Integer.MAX_VALUE**（实际由 result size 主导） |
| **batch**                                            | 每个 Result 最大 cells 数（行内分页）                                  | -1（不限）                                                                        |
| **maxResultSize**                                    | 每次 RPC 最大字节数                                                | -1 → 客户端默认 **2MB**（`hbase.client.scanner.max.result.size`），服务端上限 **100MB**    |
| allowPartialResults                                  | 客户端是否看到"部分行"                                                | false（自动拼装）                                                                   |
| limit                                                | 返回行数上限（替代旧 small scan；配合 `setReadType(PREAD)` 单次 RPC 完成小查询） | -1                                                                            |
| readType                                             | DEFAULT / STREAM / PREAD                                    | DEFAULT                                                                       |
| raw                                                  | 返回删除标记与未清理的已删数据                                             | false                                                                         |
| cacheBlocks                                          | 是否走 block cache                                             | true（**MR 任务应关**）                                                             |
| consistency / replicaId                              | STRONG / TIMELINE 读（见 §1.5）                                 | STRONG                                                                        |
| needCursorResult                                     | 心跳/部分结果时携带 Cursor，可断点续扫                                     | false                                                                         |
| isolationLevel                                       | READ_COMMITTED / READ_UNCOMMITTED                           | READ_COMMITTED                                                                |
| setScanMetricsEnabled / setEnableScanMetricsByRegion | 采集扫描指标（RPC 数、字节数、rows filtered 等）                           | false                                                                         |

**caching vs batch vs maxResultSize 三者区别**（最易混淆，务必区分）：

- `caching` = **每次 RPC 预取的行数**（吞吐 knob）
- `batch` = **每次 next() 返回的 cells 数**（行内分页；跨行边界由 `Result.mayHaveMoreCellsInRow()` 判断）
- `maxResultSize` = **每次 RPC 的字节上限**，超限时服务端把大行拆成部分 Result 返回

**硬约束**（源码验证）：

- rowkey 长度 ≤ `HConstants.MAX_ROW_LENGTH` = **32767 字节**
- `setBatch` 与实现了 `hasFilterRow()` 的过滤器（如 SingleColumnValueFilter、DependentColumnFilter、SkipFilter）**不能同用** → 抛 `IncompatibleFilterException`
- raw 模式下**不允许指定列限定符**（`addColumn`）——非仅文档约定，服务端强校验：`StoreScanner` 构造器直接抛 `DoNotRetryIOException("Cannot specify any column for a raw scan")`（StoreScanner.java:261）。**注意仅限 qualifier 级**：`addFamily`（族级投影）实际放行（familyMap 值为 null 不触发检查）；raw 扫描同时**绕过 TTL 过期**；raw+reversed 组合受支持（RawScanQueryMatcher 有显式 reversed 分支）
- prefix scan 辅助方法不能与 withStartRow/withStopRow 混用
- Scan 实例在扫描过程中会被修改（属性/指标累积），**勿复用同一 Scan 实例**
- scanner lease 默认 **60s**（`hbase.client.scanner.timeout.period`），长时间处理下一批前会过期（UnknownScannerException）

**优劣**：✅ 前缀/范围扫描的唯一原生手段，配合 filter 可下推大量条件；❌ 全表扫描代价极高；无二级索引意味着非 rowkey 维度查询只能全表扫+过滤。

### 1.3 Result / ResultScanner 语义

- `Result` 非线程安全；内部 `Cell[]` 按 row→CF→CQ→ts 降序排列
- `getValue(cf,cq)` 会**拷贝字节**；性能敏感场景用 `getValueAsByteBuffer`/`loadValue`
- `r.getMap()` → `Map<cf, Map<cq, Map<ts, value>>>`（全部版本）
- `ResultScanner.next()` 返回 null 表示扫完；`next(n)` 返回**空数组**（非 null）表示结束
- TIMELINE 读可用 `r.isStale()` 判断是否读到旧副本

### 1.4 异步客户端（AsyncTable，本分支推荐方向）

```java
AsyncConnection conn = ConnectionFactory.createAsyncConnection(conf).get();
AsyncTable<ScanResultConsumer> table = conn.getTable(TableName.valueOf("t1"));

// 单点/批量
CompletableFuture<Result> f = table.get(new Get(row));
List<CompletableFuture<Result>> fs = table.get(List.of(g1, g2)); // 个别失败不影响其他
CompletableFuture<List<Result>> all = table.getAll(gets);         // 任一失败则整体失败

// 小结果一次性拉取（内部 limit 保护）
CompletableFuture<List<Result>> rows = table.scanAll(
    new Scan().setStartRow(a).setStopRow(b).setLimit(100));

// 流式回调（onNext 返回 false 即终止扫描）
table.scan(scan, new ScanResultConsumer() {
    public boolean onNext(Result r) { process(r); return true; }
    public void onError(Throwable t) { ... }
    public void onComplete() { ... }
});
```

高级消费者 `AdvancedScanResultConsumer` 提供 `ScanController`（**suspend/resume、terminate、获取 Cursor 断点续扫**）和 `onHeartbeat`（长扫描心跳）。注意本分支（3.0+）**同步 Table 已基于 AsyncTable 实现**，`Table#coprocessorService`、`batchCallback` 等均已标记 @Deprecated。

### 1.5 读一致性：STRONG vs TIMELINE

```java
Get get = new Get(row).setConsistency(Consistency.TIMELINE);  // 读区域副本
Result r = table.get(get);
if (r.isStale()) { /* 结果来自 secondary 副本，可能滞后 */ }
```

语义（官方文档）：写永远走 primary（强一致）；TIMELINE 读先发 primary，`hbase.client.primaryCallTimeout.get`（默认 10ms）未响应则并发发 secondary 副本，谁先回用谁。**不是最终一致**：更新单点有序、副本按提交序回放，但客户端可能观察到"时间回退"（先后读到 x=2 再 x=1）。代价：副本 MemStore/块缓存翻倍、额外复制流量。适合读多写少、可容忍陈旧读的高可用读场景。Shell 里等价于 `{CONSISTENCY => 'TIMELINE'}`。

**读扩展的三种机制谱系**（TIMELINE 只是其一，选型时注意区分层级）：

| 机制                                            | 层级            | 原理                                                                                                                           | 一致性                          | 适用                       |
| --------------------------------------------- | ------------- | ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------- | ------------------------ |
| Region 副本 + TIMELINE（本节）                      | 单集群内，Region 级 | secondary 副本异步回放 WAL；客户端先 primary、超时后并发副本                                                                                    | 有界陈旧（可检测 `isStale()`）        | 读多写少、容忍毫秒级滞后             |
| **Read Replica Cluster**（4.0 新特性，HBASE-29081） | 整集群级          | 第二集群以**只读模式共享同一 `hbase.rootdir`**（同一份 HFile，零数据复制、**不走 replication**）；同步靠主动集群 flush + 副本侧 `refresh_meta`/`refresh_hfiles` 命令 | 最终一致（MemStore 数据 flush 前不可见） | 把重扫描/分析流量整体隔离到独立集群，不翻倍存储 |
| RPC 队列隔离（见 §10 实践 8）                          | 单集群进程内        | `read.ratio` 拆读/写队列，`scan.ratio` 再拆短读（get）/长读（scan）                                                                          | 不变（无副本）                      | 在线 get 与大 scan 混跑时的相互保护  |

### 1.6 快照扫描（绕过 RegionServer）

```java
Path restoreDir = new Path("/tmp/restore");  // 不能是 hbase.rootdir 子目录！
try (TableSnapshotScanner scanner =
        new TableSnapshotScanner(conf, restoreDir, "snapshot_name", scan)) {
    Result r;
    while ((r = scanner.next()) != null) { ... }
}   // close 时清理 restoreDir（快照本身不复制数据，只建引用链接）
```

直接读 HDFS 上的快照 HFile，**完全绕过 HBase 服务**，对大表导出/离线分析更快；权限上需要 HDFS 层面访问权（可配合 `SnapshotScannerHDFSAclController` 同步 ACL）。MapReduce 场景对应 `TableSnapshotInputFormat`。

### 1.7 原生 API 关键默认规格（hbase-default.xml）

| 配置                                     | 默认值                          |
| -------------------------------------- | ---------------------------- |
| `hbase.client.scanner.caching`         | 2147483647（由 result size 主导） |
| `hbase.client.scanner.max.result.size` | 2MB                          |
| `hbase.server.scanner.max.result.size` | 100MB（服务端 OOM 保护）            |
| `hbase.client.scanner.timeout.period`  | 60000ms（scanner lease）       |
| `hbase.server.keyvalue.maxsize`        | 10MB（单 cell 硬上限，含 key）       |
| `hbase.column.max.version`             | 1                            |

### 1.8 多版本与删除语义：查询结果的"隐形边界"

时间范围/多版本查询（§1.1 的 timeRange/readVersions）的语义正确性还受删除机制影响——官方 datamodel 文档明确警告的经典陷阱：

- **Delete 遮蔽 Put（tombstone masking）**：删除写入墓碑标记；此后即使再写入**时间戳更早或相同**的数据，读取时仍被墓碑遮蔽（写入不报错但读不到），直到 major compaction 清理墓碑
- **major compaction 会改变查询结果**：例如某列有 t3>t2>t1 三个版本、maxVersions=2 时读回 t3/t2；删除 t2 后 t1 重新可见——而 major compaction 之后 t1 可能被物理清除，同一查询在 compaction 前后结果不一致
- 删除整行在内部展开为**每 CF 一个墓碑**（而非每列一个）
- 缓解手段：CF 级 `KEEP_DELETED_CELLS`（窗口内保留已删数据，配合 raw scan 做审计/恢复）；HBase 2.0+ 的 `NEW_VERSION_BEHAVIOR=true`（修正"删除恒压过同时间戳 Put"的语义，代价 0~25% CPU）；`hbase.hstore.time.to.purge.deletes` 控制未来时间戳墓碑的延迟清理
- **实践规则**：业务侧始终用单调递增时间戳写入（如 currentTimeMillis），可规避绝大多数遮蔽陷阱

---

## 2. HBase Shell（JRuby）—— 最常用的交互查询界面

命令定义在 `hbase-shell/src/main/ruby/shell/commands/`，选项处理在 `hbase/table.rb`。以下选项表与默认值均从源码核对。

### 2.1 get

```ruby
hbase> get 't1', 'r1'
hbase> get 't1', 'r1', {COLUMN => 'c1'}
hbase> get 't1', 'r1', {COLUMN => ['c1','c2'], TIMERANGE => [ts1, ts2], VERSIONS => 4}
hbase> get 't1', 'r1', {FILTER => "ValueFilter(=, 'binary:abc')"}
hbase> get 't1', 'r1', {CONSISTENCY => 'TIMELINE', REGION_REPLICA_ID => 1}
hbase> get 't1', 'r1', 'c1'                      # 简写
hbase> get 't1', "key\x03\x3f\xcd"               # 二进制 key 用双引号十六进制转义
```

选项：`COLUMN/COLUMNS`、`TIMESTAMP`、`TIMERANGE`、`VERSIONS`（默认 1）、`FILTER`、`CONSISTENCY`、`REGION_REPLICA_ID`、`ATTRIBUTES`、`AUTHORIZATIONS`（可见性标签）、显示类 `MAXLENGTH/FORMATTER/FORMATTER_CLASS`。

### 2.2 scan（选项最全）

```ruby
hbase> scan 't1'
hbase> scan 't1', {COLUMNS => ['c1','c2'], LIMIT => 10, STARTROW => 'xyz'}
hbase> scan 't1', {ROWPREFIXFILTER => 'row2', FILTER =>
    "(QualifierFilter (>=, 'binary:xyz')) AND (TimestampsFilter ( 123, 456))"}
hbase> scan 't1', {REVERSED => true}
hbase> scan 't1', {RAW => true, VERSIONS => 10}       # 含删除标记；不能与 COLUMNS 同用
hbase> scan 't1', {FILTER =>
    org.apache.hadoop.hbase.filter.ColumnPaginationFilter.new(1, 0)}   # 直接传 Java 对象
hbase> scan 't1', {METRICS => ['RPC_RETRIES','ROWS_FILTERED']}         # 打印扫描指标
```

**源码核对的选项与默认值**：`STARTROW`('')、`STOPROW`(nil)、`ROWPREFIXFILTER`（**覆盖** start/stop）、`COLUMNS`([]=全部)、`VERSIONS`(1)、`LIMIT`(-1)、`REVERSED`(false)、`CACHE`(0=用集群默认)、`RAW`(false)、`BATCH`(-1)、`MAX_RESULT_SIZE`(-1)、`ALLOW_PARTIAL_RESULTS`(false)、`CACHE_BLOCKS`(**true**)、`READ_TYPE`(PREAD/STREAM/DEFAULT)、`ISOLATION_LEVEL`、`CONSISTENCY`、`ALL_METRICS`(false)、`AUTHORIZATIONS`。

### 2.3 count

```ruby
hbase> count 't1', INTERVAL => 100000, CACHE_BLOCKS => false
hbase> count 't1', FILTER => "PrefixFilter('abc')"     # 可接任意 scan 选项
```

实现细节（源码验证）：自动包装 `FirstKeyOnlyFilter + KeyOnlyFilter` 的 FilterList——每行只回一个无值 KV，服务端极大优化；默认 `CACHE_BLOCKS => false`（与 scan 相反，避免污染缓存）。大表建议用 MapReduce `RowCounter`。

### 2.4 其他查询相关命令

`exists 't1'`、`describe 't1'`（含 quota）、`list 'regex.*'`、`locate_region 't','key'`（定位行所在 Region）、`show_filters`、`get_table`（表对象链式调用）、`get_counter`。

**Shell 优劣**：✅ 零代码、适合运维验证与即席查询；❌ 单线程客户端、JRuby 语法门槛、不适合生产应用内嵌（应走 Java/网关 API）。

---

## 3. 过滤器（Filters）—— 服务端条件查询的核心机制

源码位置：`hbase-client/.../filter/`（46 个文件全部核对）。

### 3.1 框架与"裁剪"语义（为什么有的过滤器快、有的慢）

过滤器的调用链（`Filter.java`）：`reset → filterAllRemaining → filterRowKey → filterCell → transformCell → filterRowCells → filterRow`。不同实现裁剪层次不同，直接决定扫描代价：

| 裁剪层次                        | 含义                         | 代表过滤器                                                                                                          |
| --------------------------- | -------------------------- | -------------------------------------------------------------------------------------------------------------- |
| **filterRowKey**            | 读 rowkey 即可跳过整行（甚至跳 Block） | RowFilter、PrefixFilter、MultiRowRangeFilter、InclusiveStopFilter、RandomRowFilter                                 |
| **SEEK_NEXT_USING_HINT 提示** | 直接 seek 到下一个候选位置，跳过中间数据    | PrefixFilter、FuzzyRowFilter、MultiRowRangeFilter、ColumnPrefixFilter、ColumnRangeFilter、TimestampsFilter(canHint) |
| **filterCell**              | 逐 cell 判断（数据已读，只能省网络）      | ValueFilter、QualifierFilter、FamilyFilter、ColumnValueFilter                                                     |
| **filterRow（行级后置）**         | 整行读完才能判定                   | SingleColumnValueFilter、DependentColumnFilter、SkipFilter、PageFilter                                            |
| **filterAllRemaining**      | 提前终止整个扫描                   | PrefixFilter（越过前缀）、PageFilter（满页）、InclusiveStopFilter、WhileMatchFilter                                         |

**这个层次结构是过滤器选型的第一原则**：能用 rowkey 类过滤器就不要用 cell 类；`isFamilyEssential` 优化（SCVF/ColumnValueFilter）可让服务端跳过无关 CF 的读取。

### 3.2 过滤器全清单（按类别）

**① 比较类**（CompareFilter + 比较器）：

| 过滤器               | 比较对象   | 不匹配行为                                       |
| ----------------- | ------ | ------------------------------------------- |
| RowFilter         | rowkey | 丢整行（filterRowKey 层）                         |
| FamilyFilter      | 列族     | NEXT_ROW                                    |
| QualifierFilter   | 列名     | SKIP                                        |
| ValueFilter       | 值      | SKIP                                        |
| ColumnValueFilter | 指定列的值  | 只返回匹配的那个 cell（**与 SCVF 的关键区别**：SCVF 命中返回整行） |

**② 专用类**：

| 过滤器                                             | 用途                                | 关键语义                                                                          |
| ----------------------------------------------- | --------------------------------- | ----------------------------------------------------------------------------- |
| PrefixFilter                                    | rowkey 前缀                         | 越过前缀后提前终止                                                                     |
| PageFilter                                      | 每页 N 行                            | **仅 per-Region 生效**，跨 Region 无全局保证                                            |
| MultiRowRangeFilter                             | 多个显式行区间                           | 排序合并+seek，避免"多个 RowFilter OR"的低效                                              |
| FuzzyRowFilter                                  | 掩码匹配（`????_99_????_01`，0=固定 1=通配） | 优先队列计算下一个候选行，高效跳过                                                             |
| InclusiveStopFilter                             | 含停止行的扫描                           | 补齐 stopRow 排斥语义                                                               |
| ColumnPrefixFilter / MultipleColumnPrefixFilter | 列名前缀                              | seek 跳列                                                                       |
| ColumnRangeFilter                               | 列区间（min/max+闭开标志）                 | seek 跳列                                                                       |
| ColumnPaginationFilter                          | 行内列分页（limit+offset 或 byte[] 书签）   | 宽表翻页                                                                          |
| TimestampsFilter                                | 显式时间戳集合                           | **覆盖** Scan 的 timeRange；canHint 可跳墓碑（可能"复活"已删数据，opt-in）                       |
| SingleColumnValueFilter (SCVF)                  | "行内 WHERE 某列"（最常用）                | `setFilterIfMissing(true)` 默认 false（列缺失时**放出整行**——常见坑）；`setLatestVersionOnly` |
| SingleColumnValueExcludeFilter                  | SCVF 但隐藏被测列                       | 省回传                                                                           |
| DependentColumnFilter                           | 跨列时间戳匹配                           | 保留与依赖列同 ts 的 cells；与 batch 不兼容                                                |
| FirstKeyOnlyFilter                              | 每行首个 KV                           | 行计数标配                                                                         |
| KeyOnlyFilter                                   | 剥离 value（lenAsVal 可留长度）           | 只取 rowkey 场景（与 FirstKeyOnly 组合）                                               |
| RandomRowFilter                                 | 概率采样                              | chance ∈ [0,1]                                                                |
| ColumnCountGetFilter                            | 前 N 列后终止                          | **仅用于 Get**，做 Scan 会终止整个扫描                                                    |

**③ 装饰/组合类**：

| 过滤器              | 语义                                                                           |
| ---------------- | ---------------------------------------------------------------------------- |
| SkipFilter       | 包装后"任一 cell 失败 → 丢整行"                                                        |
| WhileMatchFilter | 包装后"一旦失败 → 终止整个扫描"（游标式读取）                                                    |
| FilterList       | `MUST_PASS_ALL`(AND，惰性求值) / `MUST_PASS_ONE`(OR)；hint 合并规则：AND 取最大步长、OR 需全部提供 |

### 3.3 比较器与操作符

- **操作符**（CompareOperator）：`< <= = != > >=` + `NO_OP`（全丢弃）
- **比较器**：BinaryComparator（字典序）、BinaryPrefixComparator、RegexStringComparator（仅 =/!=）、SubstringComparator（大小写不敏感 contains，仅 =/!=）、LongComparator（数值）、BitComparator（位运算）、NullComparator、BigDecimalComparator、BinaryComponentComparator
- CompareFilter 语义注意：regex/substring 比较器只支持 EQUAL/NOT_EQUAL（工厂方法强校验）

### 3.4 过滤器字符串语法（Shell/REST/Thrift 通用，ParseFilter 解析）

```text
"FilterName('arg', op, 'comparator')"          # 字符串参数单引号；数字/布尔/操作符不加引号
组合:  F1 AND F2 / F1 OR F2 / SKIP F / WHILE F / ( ... )
优先级: () > SKIP=WHILE > AND > OR
比较器: binary: / binaryprefix: / regexstring: / regexstringnocase: / substring:
转义:  参数内单引号用两个单引号 ''
```

```text
# 官方文档/帮助文本原例
"PrefixFilter ('Row') AND PageFilter (1) AND FirstKeyOnlyFilter ()"
"(RowFilter (=, 'binary:Row 1') AND TimeStampsFilter (74689, 89734)) OR ColumnRangeFilter ('abc', true, 'xyz', false)"
"SKIP ValueFilter (0)"                          # 行内任一值失败→跳过整行
```

⚠️ **两个源码核实的重要更正**：

1. **`long:` 前缀不被支持**——`ParseFilter.createComparator` 只认上述 5 种前缀；LongComparator/BitComparator 只能通过 Java 对象或 protobuf 序列化使用（很多老文档写有 long:，系误传）。
2. 字符串语法仅注册了 **19 个过滤器**（FuzzyRowFilter、MultiRowRangeFilter、RandomRowFilter 未注册，只能 Java 对象使用）。

**过滤器优劣**：✅ 服务端执行、省网络；✅ 与 seek hint 结合可跳过大量数据。❌ 本质是"扫描时过滤"，不能替代索引——非 rowkey 条件仍需读数据；❌ 过滤器写错代价巨大（全表扫描），SCVF filterIfMissing 默认值是经典陷阱。

---

## 4. 协处理器 Endpoint —— 服务端自定义查询/聚合

解决"把计算搬到数据处"的问题（类似存储过程）。源码：`hbase-endpoint` 模块。

### 4.1 内置聚合客户端 AggregationClient

> ⚠️ **前置条件（缺失即运行失败）**：`AggregationClient` 只是客户端封装，**必须先在目标表注册 `AggregateImplementation` 协处理器**，否则每个方法运行时抛 `UnknownProtocolException: No registered coprocessor service found for AggregateService in region ...`（服务端 `HRegion.execService`，HRegion.java:8546）。注册方式二选一：
> 
> - **表级**（免重启）：`alter 'mytable', METHOD => 'table_att', 'coprocessor' => '|org.apache.hadoop.hbase.coprocessor.AggregateImplementation||'`
> - **系统级**（重启生效、全部表）：hbase-site.xml 的 `hbase.coprocessor.region.classes`（默认为空）
> 
> 另两个工程要点：① `AggregationClient`/`AsyncAggregationClient` 位于 **hbase-endpoint** 模块，**`hbase-shaded-client` jar 不包含它**——客户端应用需显式添加 `hbase-endpoint` 依赖（服务端无需：发行版 `lib/` 已含）；② 该类官方文档零提及、源码中留有 `// TODO: This class is not used. Move to examples?`——属半废弃的示例级工具，**生产聚合优先 Phoenix（服务端下推）或自研 endpoint**。

```java
try (AggregationClient agg = new AggregationClient(conf)) {   // 自管连接需 close
    Scan scan = new Scan();                 // 约束：startRow < stopRow、恰好一个 CF
    LongColumnInterpreter ci = new LongColumnInterpreter();  // 还有 Double/BigDecimal 解释器

    long   rows  = agg.rowCount(table, ci, scan);
    Long   max   = agg.max(table, ci, scan);
    Long   min   = agg.min(table, ci, scan);
    Long   sum   = agg.sum(table, ci, scan);
    double avg   = agg.avg(table, ci, scan);
    double std   = agg.std(table, ci, scan);      // √(E[x²]−E[x]²)
    Long   med   = agg.median(table, ci, scan);   // 两阶段：先定位中位数 Region 再取值
}
```

机制：客户端把 Scan + ColumnInterpreter 类名序列化进 `AggregateRequest`，按 Region 扇出到各 Region 的 `AggregateImplementation`（coprocessor）服务端聚合，客户端合并。异步版 `AsyncAggregationClient`（推荐——同步版依赖 3.0 起已 @Deprecated 的 `Table#coprocessorService` 扇出路径）。

### 4.2 自定义 Endpoint 模式

```java
// 1) 定义 .proto 服务 → 2) 实现：继承生成的 Service 类 + 实现 RegionCoprocessor
public class RowCountEndpoint extends RowCountService implements RegionCoprocessor {
    @Override public Iterable<Service> getServices() { return Collections.singleton(this); }
    @Override public void getRowCount(RpcController c, CountRequest req,
                                      RpcCallback<CountResponse> done) {
        // 用 InternalScanner 遍历本 Region，done.run(response) 返回
    }
}
// 3) 注册（表级，动态）：
//    alter 't1', METHOD=>'table_att',
//      'coprocessor$1'=>'hdfs:///.../coprocessor.jar|com.foo.MyEndpoint|1073741823|arg1=1'
//    或 API: TableDescriptorBuilder.setCoprocessor(CoprocessorDescriptorBuilder...)
// 4) 系统级：hbase-site.xml 的 hbase.coprocessor.region.classes（重启生效，全表生效）
```

注册串格式：`jar路径|类全名|优先级|k1=v1,k2=v2`。仓库内可参考的 Endpoint 实现：`RowCountEndpoint`、`BulkDeleteEndpoint`（按 Scan 批删，hbase-examples）、`RefreshHFilesEndpoint`、`Export`（hbase-endpoint）、`MultiRowMutationEndpoint`、`TokenProvider`（安全令牌，hbase-server）。

### 4.3 调用方式（本分支重要变化）

- 同步 `Table#coprocessorService(Class, startKey, endKey, Batch.Call, Batch.Callback)` **已 @Deprecated（3.0.0 起）**——同步客户端已在 async 之上实现，需独立线程池
- 推荐 `AsyncTable#coprocessorService(stubMaker, caller, row)` → `CompletableFuture`；跨 Region 用 `CoprocessorServiceBuilder.fromRow(...).toRow(...).execute()`

**优劣**：✅ 数据不动计算动、聚合/批删/导出类任务网络省几个量级；❌ **官方明示"高级特性、自担风险"**：无资源隔离（坏协处理器可拖垮集群）、无防数据破坏机制；❌ 与 HBase 内部 API 耦合易碎（protobuf 版本变迁史上有过破坏性升级）；❌ 版本耦合——升级 HBase 常需重编。

---

## 5. REST 网关（hbase-rest）

启动：`bin/hbase rest start -p 8080`（默认 8080；Jetty+Jersey）。内容协商：`Accept: text/xml | application/json | application/x-protobuf | application/octet-stream`（值均为 Base64）。

### 查询端点全景（源码 + 官方文档双重核对）

```bash
# 元数据类
curl -H "Accept: application/json" http://host:8080/                     # 表清单
curl http://host:8080/users/schema                                       # 表结构
curl http://host:8080/users/regions                                      # Region 分布
curl http://host:8080/users/exists                                       # 表存在性

# 单行/单列（?v=N 多版本；?e=b64 二进制键 base64；带时间戳）
curl -H "Accept: application/json" "http://host:8080/users/row1"
curl -H "Accept: application/json" "http://host:8080/users/row1/cf:a/1458586888395"
curl -H "Accept: application/json" "http://host:8080/users/row1/cf:a?v=2"

# 行前缀 + multi-get（支持 filter 字符串 / filter_b64）
curl "http://host:8080/users/row*/cf"
curl "http://host:8080/users/multiget?row=row1&row=row2/cf:a"
curl "http://host:8080/users/multiget?row=row1&filter=PrefixFilter%28%27row1%27%29"

# 有状态扫描器（三步生命周期）
curl -X PUT -H "Content-Type: text/xml" -d '<Scanner batch="100"/>' \
     http://host:8080/users/scanner/
# → 201 Created, Location: .../scanner/1458690728243
curl -H "Accept: application/json" http://host:8080/users/scanner/1458690728243   # 取批
# → 204 扫完；410 扫描器过期被逐出；403 非所有者
curl -X DELETE http://host:8080/users/scanner/1458690728243                      # 关闭

# 无状态一次性扫描（小结果优化；全部走查询参数）
curl "http://host:8080/users/*?startrow=row1&limit=10&column=cf:a,cf:b&maxversions=2&reversed=true&filter=..."
```

扫描器 spec（XML/JSON/protobuf）字段：`startRow, endRow, columns, batch, caching, startTime, endTime, maxVersions, filter, labels, cacheBlocks, limit, includeStartRow/StopRow`。服务端扫描器缓存：默认 10000 个、1h 过期。

**优劣**：✅ 语言无关、HTTP 生态友好、防火墙友好；❌ 多一跳进程（吞吐/延迟损失）、Base64 膨胀 33%、无连接池语义、有状态扫描器故障需客户端重启扫描；❌ 默认无认证（需另行配 Kerberos/SPNEGO/doAs 代理）。

---

## 6. Thrift 网关（hbase-thrift）

**两套服务、四种线程模型**：

|     | Thrift1（legacy `Hbase`）                                                                                        | Thrift2（`THBaseService`）                                                                            |
| --- | -------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| 启动  | `bin/hbase thrift`                                                                                             | `bin/hbase thrift2`（支持 -ro 只读）                                                                      |
| 风格  | HBase 0.9x 风格，API 庞杂                                                                                           | 贴近新 Java API                                                                                        |
| 点查  | `get/getVer/getVerTs/getRow*/getRows*`（列、时间戳、版本组合共 **11** 个方法：cell 级 3 + 单行 4 + 多行 4）                          | `get/getMultiple/exists/existsAll`（TGet 内嵌 maxVersions/timeRange/filterString/consistency 等）        |
| 扫描  | `scannerOpenWithScan/scannerOpen/WithStop/WithPrefix/Ts/WithStopTs` + `scannerGet/scannerGetList/scannerClose` | `openScanner/getScannerRows/closeScanner` + 便捷方法 `getScannerResults`                                |
| 元数据 | `getTableNames/getColumnDescriptors/getTableRegions/getRegionInfo`                                             | `getTableDescriptors*/getTableNamesByPattern/getRegionLocation/getAllRegionLocations/getNamespace*` |
| 存在性 | **无**（thrift1 缺失）                                                                                              | `exists/existsAll`                                                                                  |

线程模型（`-` 参数或 `hbase.regionserver.thrift.server.type`）：`threadpool`（默认，TBoundedThreadPoolServer）、`nonblocking`、`hsha`、`threadedselector`（后三者隐含 framed 传输）；另有 HTTP 模式（`hbase.regionserver.thrift.http=true`，走 Jetty）。默认端口 **9090**。协议：默认 TBinaryProtocol，可 `-c` 切 TCompactProtocol。

Python 示例（thrift2 风格）：

```python
transport = TSocket.TSocket('host', 9090)
protocol = TBinaryProtocol.TBinaryProtocol(transport)
client = THBaseService.Client(protocol)
transport.open()

# 点查
result = client.get(b'users', TGet(row=b'row1',
                    columns=[TColumn(family=b'cf', qualifier=b'a')]))
# 扫描（filter 字符串语法与 Shell/REST 相同）
sid = client.openScanner(b'users', TScan(startRow=b'row',
                    filterString="PrefixFilter('row')"))
rows = client.getScannerRows(sid, 10)
client.closeScanner(sid)
```

**优劣**：✅ 跨语言 IDL 生成客户端、比 REST 紧凑高效；❌ 同样是代理进程（认证代理配置复杂：网关需代表终端用户 Kerberos 认证）；❌ Thrift1/2 API 割裂、社区新特性优先 Java/REST。

---

## 7. 批处理查询：MapReduce / Spark（离线全量/大规模扫描）

### 7.1 MapReduce（仓内原生支持）

```java
Scan scan = new Scan();
scan.setCaching(500);                 // 默认 1 对 MR 是灾难（官方性能章节原话）
scan.setCacheBlocks(false);           // MR 关闭块缓存
TableMapReduceUtil.initTableMapperJob(
    "users", scan, MyMapper.class, outKey.class, outVal.class, job);
// 快照版本：initTableSnapshotMapperJob(snapshotName, scan, ...)
```

特性：**每个 Region 一个 map split**（100 Region = 100 map task，与选列无关）；配套 `RowCounter` 工具类即官方计数作业；`TableSnapshotInputFormat` 走快照绕过线上集群。

### 7.2 Spark（hbase-connectors 仓库，HBaseContext + DataFrame）

```scala
// catalog 映射（rowkey 也须声明为列）
val catalog = """{"table":{"namespace":"default","name":"table1"},
  "rowkey":"key","columns":{
    "col0":{"cf":"rowkey","col":"key","type":"string"},
    "col1":{"cf":"cf1","col":"col1","type":"boolean"},
    "col2":{"cf":"cf2","col":"col2","type":"double"}}}"""

val df = spark.read
  .option(HBaseTableCatalog.tableCatalog, catalog)
  .format("org.apache.hadoop.hbase.spark").load()

// rowkey 范围谓词下推为 scan 范围
df.filter($"col0" <= "row050" && $"col0" > "row040").select("col0","col1").show
```

另有 `HBaseContext` 原语：`bulkGet`（并行点查生成 RDD）、`bulkPut/bulkDelete`、`hbaseBulkLoad`（直写 HFile）。特性：分区裁剪、列裁剪、rowkey 谓词下推、数据本地性；**列过滤下推需在每台 RegionServer 部署 scala/hbase-spark jar**（否则关闭 `hbase.spark.pushdown.columnfilter`）。

**优劣**：✅ 唯一合理的全表/超大规模扫描途径（并行度=Region 数）；❌ 分钟级延迟，非在线查询；❌ MR 版本重、Spark 版本需自编译对齐栈版本。

---

## 8. SQL 与生态查询层（HBase 之上）

### 8.1 Apache Phoenix —— 事实标准的 SQL 层（活跃维护）

**状态（2026-10 核实）**：最新 5.3.2（2026-07），兼容 HBase 2.5/2.6；5.2.x 线维护 HBase 2.4–2.6；仓库活跃。与 HBase 小版本强绑定，选型须配对版本。

**架构**：厚客户端 JDBC（SQL→客户端编译→并行 HBase Scan），聚合/投影经协处理器服务端执行；二级索引由协处理器在 WAL 钩子中维护；元数据存于 `SYSTEM.*` 表。另有 Phoenix Query Server（Avatica 薄客户端协议）。

**语法能力**（官方 grammar）：

```sql
CREATE TABLE prod_metrics (
    host CHAR(50) NOT NULL, created_date DATE NOT NULL,
    txn_count BIGINT, payload VARCHAR,
    CONSTRAINT pk PRIMARY KEY (host, created_date))
SALT_BUCKETS = 16, IMMUTABLE_ROWS = true, COMPRESSION = 'SNAPPY',
TTL = 86400, UPDATE_CACHE_FREQUENCY = 900000;

SELECT NAME, SUM(VAL) FROM TEST GROUP BY NAME HAVING COUNT(1) > 2;
SELECT d.dept_id, e.name FROM DEPT d JOIN EMPL e ON e.dept_id = d.dept_id;

UPSERT INTO TEST VALUES('foo','bar',3);                       -- 插入或更新
UPSERT INTO TEST(id,c) VALUES(123,0)
  ON DUPLICATE KEY UPDATE c = c + 1;                          -- 原子 RMW
UPSERT INTO target(col1,col2) SELECT col3,col4 FROM src WHERE col5 < 100;  -- 服务端 ETL
```

**二级索引体系**（核心价值）：

| 索引类型                             | 写路径                       | 读路径                              | 适用      |
| -------------------------------- | ------------------------- | -------------------------------- | ------- |
| Global（含 covered `INCLUDE`）      | 同步写独立索引表（强一致）             | 优化器自动改写；**查询必须 covered 否则回退全表扫** | 读多写少    |
| Local                            | 写入同表影子 CF（无网络跳）           | **需探测所有 Region**                 | 写多、空间敏感 |
| Functional / Uncovered / Partial | 表达式索引 / 索引+回表 join / 条件索引 |                                  | 特定优化    |

运维要点：`CREATE INDEX ... ASYNC` 后台构建（IndexTool）；失败可 `ALTER INDEX ... REBUILD`；事务表配 Tephra/Omid 获得跨表 ACID（有吞吐代价）。

**Rowkey 编码**（源码核实的常见误区更正）：组合键 = 各 PK 列序列化值**直接拼接**，可变长类型（VARCHAR 等）后跟**零字节分隔符**；`INTEGER` 4 字节**符号位翻转**（保证负数排前）；加盐表首字节 = `hash(key) % SALT_BUCKETS`（**1 字节盐**，≤256 桶）。——网上流传的"4 字节长度前缀"说法是错的。因此**裸 HBase 写入与 Phoenix 表互操作需字节级兼容，官方支持路径是只读 `CREATE VIEW` 映射既有表**。

**多租户**：`MULTI_TENANT=true` + 连接属性 `TenantId`——Phoenix 自动注入 `WHERE tenant_id=?`、自动填充 UPSERT，租户可建自己的视图/索引。

**优劣**：✅ 完整 SQL/JDBC、自动维护的二级索引、毫秒 OLTP~秒级分析、盐化治写热点；❌ 版本强绑定 HBase 小版本；❌ 全局索引同步写放大 + WAL codec + 失败处理运维负担；❌ Join 仍是广播哈希/排序合并，非任意大表 ad-hoc join；❌ 部署事务管理器才能拿全 ACID。

### 8.2 Hive HBaseStorageHandler（HiveQL 批查询，随 Hive 4.x 发布）

```sql
CREATE EXTERNAL TABLE hbase_users(key int, value string)
STORED BY 'org.apache.hadoop.hive.hbase.HBaseStorageHandler'
WITH SERDEPROPERTIES ("hbase.columns.mapping" = ":key,cf1:val")
TBLPROPERTIES ("hbase.table.name" = "users");
-- 整个 CF 映射为 MAP： "cf1:"；时间戳列 ":timestamp"；复合键 struct+分隔符 或自定义 key factory
```

能力：HiveQL 读写、可与原生 Hive 表 join；**谓词下推仅限 rowkey 列的范围分解**（`HiveStorageHandler implements HiveStoragePredicateHandler`），其余谓词在引擎侧全扫过滤。约束：恰好一个 `:key` 列、string/binary 序列化、`INSERT OVERWRITE` 不删旧行、重复键静默合并。状态：活跃（Hive 4.2.x，基于 HBase 2.5.6 构建）。

### 8.3 其他生态层（2026-10 状态核实）

| 层                      | 查询面                      | 状态                                                                                                                    |
| ---------------------- | ------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| **JanusGraph**         | Gremlin 图查询（HBase 作存储后端） | 活跃；1.1.z ↔ HBase 2.6。复合图索引存于 HBase 内（等值）；范围/全文需外接 ES/Solr（mixed index）；无索引则全图扫描                                       |
| **Lily HBase Indexer** | HBase 复制→Solr 近实时全文检索    | 上游开源已死，仅存于 Cloudera CDP（"Key-Value Store Indexer"）                                                                    |
| **Huawei HIndex**      | 服务端原生二级索引                | **2017 年起休眠，从未合入 Apache HBase**（本仓库 git 全史 `--grep=hindex` 零提交）                                                       |
| **Apache Trafodion**   | 事务 SQL-on-HBase          | **已退役**（2021-05 进 Attic），勿选                                                                                           |
| **Stargate**           | 早期 REST 网关               | 历史：2010 年（HBASE-2542）并入核心，即今天的 hbase-rest；与 DataStax 的 Cassandra Stargate 无关                                          |
| **Apache Calcite**     | SQL 优化框架                 | **无官方 HBase adapter**（核心仓不含 hbase adapter，已验证）；Calcite-over-HBase 的代表作是 Kylin（且 Kylin 4 已把存储从 HBase 迁往 Parquet+Spark） |

### 8.4 无框架方案：双写/反范式（官方 Reference Guide 的原始答案）

官方"二级索引与替代查询路径"章节列举的模式：① filter 查询（不建索引）② 周期性 MR 重建索引表 ③ **双写索引表**（应用在写入时同步维护，存量数据需 MR 引导）④ 汇总表 ⑤ 协处理器触发器式索引。配合 rowkey 手法规避索引需求：加盐桶（`ts % N` 前缀，Phoenix salting 的手工版）、**反转时间戳**（`Long.MAX_VALUE - ts`，"最新优先"扫描）、哈希替换定长键。

---

## 9. 全景对比：查询方式一览（接入层、执行层、生态层）

> 延迟为典型量级（受缓存命中与数据规模影响；Get 点查缓存命中典型为个位数 ms）。吞吐列区分两个不同度量轴：**单请求网络效率**（数据本地聚合、少传原始数据）与**全表总吞吐**（并行度）。

| 层          | 查询方式                     | 接口形态                    | 延迟               | 吞吐（轴见上注）                     | 语言         | 典型场景         | 主要劣势                |
| ---------- | ------------------------ | ----------------------- | ---------------- | ---------------------------- | ---------- | ------------ | ------------------- |
| 接入层·原生 API | **Get（含批量）**             | Java API                | **个位数 ms**（缓存命中） | 高                            | Java       | 按 rowkey 点查  | 仅整键点查               |
| 接入层·原生 API | **Scan+Filter**          | Java API                | ms~∞（随范围）        | 中                            | Java       | 前缀/范围/条件扫描   | 非键维度=全表扫            |
| 接入层·原生 API | **AsyncTable**           | CompletableFuture 回调/流式 | ms 级、尾延迟更优       | 高（≈同步多线程客户端；优势在线程模型效率而非峰值吞吐） | Java       | 高并发服务化客户端    | 编程模型门槛              |
| 接入层·交互     | **Shell**                | JRuby 命令                | 秒                | 低                            | 交互         | 运维/即席验证      | 单线程、不适合应用           |
| 接入层·网关     | **REST**                 | HTTP+JSON/XML/PB        | +一跳网关            | 中                            | **任意**     | 异构语言、网墙隔离    | Base64 膨胀、多一跳、默认无认证 |
| 接入层·网关     | **Thrift**               | IDL RPC                 | +一跳网关            | 中高                           | 任意（IDL 生成） | 跨语言性能敏感      | 1/2 双 API、代理认证复杂    |
| 执行层·服务端计算  | **Coprocessor Endpoint** | protobuf RPC            | 中                | **单请求网络效率最高**（数据本地聚合）        | Java       | 聚合/批删/导出     | 高风险、无隔离、版本耦合        |
| 执行层·批处理    | **MR/Spark**             | InputFormat/DataFrame   | **分钟级**          | **全表总吞吐最高**（并行=Region 数）     | Java/Scala | 离线全量、导出、ETL  | 非在线                 |
| 生态层·SQL    | **Phoenix**              | SQL/JDBC                | ms~s             | 中高                           | 任意(JDBC)   | OLTP+二级索引+分析 | 版本绑定、索引写放大          |
| 生态层·引擎/图   | Hive/JanusGraph 等        | HiveQL/Gremlin          | 批/图遍历            | —                            | 各自         | 数仓集成/图       | 各有强约束（见 §8）         |

---

## 10. 约束、规格与选型建议汇总

**硬性规格**：rowkey ≤ 32767B；单 cell ≤ 10MB（`hbase.server.keyvalue.maxsize`，MOB 例外）；客户端每 RPC 结果 ≤ 2MB（可调）、服务端 ≤ 100MB；scanner lease 60s；PageFilter 仅 per-Region；batch×hasFilterRow 过滤器互斥；raw 扫描不可指定列限定符（addColumn 抛异常、addFamily 放行）；Thrift1 无 exists。

**通用最佳实践**（官方性能章节+源码注释）：

1. **rowkey 设计=查询设计**——先用查询模式反推键结构，再谈其他
2. MR/Spark 扫描：`setCaching(500)` + `setCacheBlocks(false)`；交互/在线扫描保持 cacheBlocks=true
3. 只要 rowkey 时用 `FirstKeyOnlyFilter + KeyOnlyFilter`（官方推荐组合）
4. 少列宽行场景用 `Scan.HINT_LOOKAHEAD` 减少 seek
5. 永远 try/finally 关闭 ResultScanner；Scan 对象不复用
6. 长扫描用 `needCursorResult` + `Scan.createScanFromCursor` 做断点续扫
7. 开 Bloom filter（ROW/ROWCOL）省磁盘 IO——ROW 适配按行点查、ROWCOL 适配指定 `cf:cq` 的点查；HDFS hedged read 消尾延迟（⚠️ 副作用：接近吞吐上限时产生读放大与网络拥塞，见 HBASE-17083——延迟敏感型才开，吞吐优先型勿开）
8. 在线查询与重扫描混跑的集群，开启 RPC 队列隔离：`hbase.ipc.server.callqueue.read.ratio` 拆读/写队列，`hbase.ipc.server.callqueue.scan.ratio` 再把读队列拆为短读（get）/长读（scan）——**两者默认均为 0（不分离）**，这是"大 scan 拖垮在线 get"的标准治理手段（hbase-default.xml:214-259）
9. 读副本（TIMELINE）换读高可用，接受有界陈旧；重分析流量可评估 4.0 的 Read Replica Cluster 做整集群隔离（见 §1.5 谱系表）

**选型速查**：

- 在线点查/范围查，Java 栈 → **原生 API（AsyncTable）**
- 需要二级索引与 SQL → **Phoenix**（版本与 HBase 配对）
- 非 Java 客户端 → **REST**（简单/语言任意）或 **Thrift2**（性能敏感）
- 聚合/服务端计算 → **Endpoint 协处理器**（谨慎，或直接用 Phoenix 的聚合）
- 全表离线 → **MapReduce/Spark**（快照输入格式可绕过线上集群）
- 数仓/图生态 → Hive StorageHandler / JanusGraph
- 全文检索 → 外接 Solr/ES（HBase 复制或双写；Lily 仅存于 CDP）

---

## 附：调研源码证据锚点

- **客户端 API**：`hbase-client/.../client/{Get,Scan,Result,ResultScanner,Table,AsyncTable,Query}.java`
- **过滤器**：`hbase-client/.../filter/`（46 文件，含 `ParseFilter.java` 字符串语法）
- **协处理器**：`hbase-endpoint/.../AggregationClient.java`、`hbase-examples/.../RowCountEndpoint.java`
- **网关**：`hbase-rest/.../{RowResource,ScannerResource,MultiRowResource}.java`、`hbase-thrift/.../{Hbase.thrift,hbase.thrift}`
- **默认规格**：`hbase-common/src/main/resources/hbase-default.xml`
- **官方文档**：`hbase-website/.../(datamodel|thrift-filter-language|external-apis|performance|cp|spark|architecture/snapshot-scanner|architecture/timeline-consistent-reads).mdx`
- **外部生态**：phoenix.apache.org（/docs/grammar、/docs/features/secondary-indexes 等）、cwiki Hive/HBaseIntegration、github.com/apache/hbase-connectors、docs.janusgraph.org、attic.apache.org/projects/trafodion.html
