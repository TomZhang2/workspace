# Databend 代码架构分析

> 基于 `databendlabs/databend` 仓库（HEAD `0cfc0f50`）源码 + 官方架构文档（docs.databend.com）的综合分析。

---

## 一、总体架构概览

Databend 是一个用 Rust 编写的**云原生企业级数据仓库**，采用 **Cargo workspace** 多 crate 组织形式（35+ 成员 crate）。核心设计理念（来自 README + 官方架构文档）：

| 设计原则 | 说明 |
|---------|------|
| **存算分离** | 计算层无状态、弹性伸缩；存储层基于廉价对象存储（S3/GCS/Azure） |
| **Git 式数据版本** | Fuse Engine 用 Snapshot/Segment/Block 三级不可变结构，支持 Time Travel、Branching、零拷贝 schema 演进 |
| **统一优化器** | 分析、JSON 搜索、向量检索、地理空间共用一条 DP+Cascades 优化流水线 |
| **Cascades 规则系统** | `SExpr`/`Memo`/`Group` 抽象 + RBO 启发式前置 + CBO 代价优化 |
| **分布式即本地优化** | `ScatterOptimizer` 将计划树重写为 `StagePlan`，跨节点走 Arrow Flight RPC |
| **Catalog→Database→Table trait 抽象** | 可插拔 catalog（Hive/Iceberg），由 metasrv 支撑 |
| **条件事务元服务** | metasrv 提供 condition/if-then/else-then 事务原语，保证快照原子写入 |

系统采用**三层架构**：Meta-Service 层（Raft 一致性、多租户）→ Compute 层（弹性 Warehouse 集群）→ Storage 层（FuseEngine + 对象存储）。

---

## 二、顶层目录结构

```
databend/
├── src/                  # Rust workspace 源码（核心）
│   ├── common/           # 21 个共享基础库 crate
│   ├── query/            # 查询引擎（22 个子目录/crate）
│   ├── meta/             # 元数据服务（client 类型 + protos + 工具 + 二进制）
│   ├── binaries/         # databend-query (EE/OSS) + table-meta-inspector
│   ├── bendpy/           # PyO3 Python 绑定（嵌入式/本地模式）
│   ├── bendsave/         # 备份/恢复工具
│   └── tests/            # planner_replay 测试 crate
├── tests/                # 集成测试：SQL suites、sqllogictests、meta-cluster、compat 等
├── scripts/              # 构建/CI/部署/测试脚本
├── docker/               # Dockerfile + 集成测试环境（hive、iceberg catalogs）
├── benchmark/            # 性能实验（tpch、hits、load、merge_into）
├── docs/                 # 设计文档（docs/designs/）
├── agents/               # 仓库开发指南（coding-style、commit-and-pr 等）
├── .github/              # CI 工作流（18 个 workflow）
├── Cargo.toml            # workspace 根配置
└── Makefile              # 顶层任务入口
```

**重要边界**：`src/meta/README.md` 明确指出——自 v1.2.874 起，**核心 meta-service 实现已迁移至独立的 `databend-meta` 仓库**，本仓库仅保留 client 类型、protos、转换、API、插件、工具和二进制。

---

## 三、`src/common/` — 21 个共享基础库

这是整个 workspace 的基石层，被 query/meta/binaries/bendpy/bendsave 共同复用。

| Crate | 职责 | 关键内容 |
|-------|------|---------|
| **base** | 运行时基础 | tokio Runtime、ThreadTracker、GlobalInstance、TrackingGlobalAllocator（jemalloc）、rangemap、containers |
| **building** | build.rs 辅助 | vergen-gix 环境变量、git HEAD 监控、license 嵌入 |
| **cache** | 缓存抽象 | Cache trait、LruCache、MemSized |
| **cloud_control** | 云控制面 gRPC 客户端 | billing/task/notification/worker client（tonic protobuf） |
| **column** | 底层列式类型 | binary/binview/bitmap/buffer/offset/types，启用 portable_simd |
| **compress** | 压缩/解压 | CompressAlgorithm、DecompressCodec，支持 zstd/lz4/brotli/zip |
| **exception** | 统一错误处理 | ErrorCode、Result、ResultExt、backtrace、flight 序列化 |
| **frozen_api** | **编译期哈希校验 proc-macro** | `#[frozen_api("hash")]` 防止关键结构体序列化格式意外破坏 |
| **grpc** | gRPC 连接工具 | RpcClientConf、TLS、DNSResolver、ConnectionFactory |
| **hashtable** | 高性能哈希表 | 从 ClickHouse 移植的线性探测哈希表，用于 group-by & join |
| **http** | HTTP handler 工具 | 健康检查、调试端点、优雅关闭 |
| **io** | 二进制序列化 & 格式辅助 | binary_read/write、HybridBitmap、decimal、datetime、wkb |
| **license** | 企业许可证管理 | JWT-based LicenseManager、OssLicenseManager |
| **metrics** | Prometheus 指标 | auth/cache/cluster/http/mysql/session/storage 等域指标族 |
| **statistics** | 列统计 | KLL sketch、histogram、typed_histogram、stat_estimate |
| **storage** | **存储抽象核心** | DataOperator（opendal 封装 S3/GCS/Azure 等 10+ 后端）、OperatorRegistry、三类 operator（data/cache/temporary） |
| **telemetry** | 匿名遥测 | report_node_telemetry（单函数） |
| **timezone** | 时区查找表 | 1900-2299 年 LUT、DST 转换、jiff-based |
| **tracing** | 日志/追踪 | loggers、panic_hook、crash_hook、OTLP、structlog、query_log_collector |
| **vector** | 向量距离计算 | cosine/l2/l1/inner_product/angular（f32 + f64） |
| **version** | 构建期版本常量 | VERGEN_GIT_SHA、BUILD_INFO、embedded enterprise license |

---

## 四、`src/query/` — 查询引擎（核心）

22 个子目录，包含 19 个直接 crate + `storages/`（13 引擎 crate + 8 共享 crate）+ `ee_features/`（10 企业 crate）+ `common/component/`。

### 4.1 SQL 编译流水线

**`ast/`** (`databend-common-ast`) — SQL 解析器 + AST
- `src/ast/`：AST 节点类型（statements/、query.rs、expr.rs、common.rs）
- `src/parser/`：tokenizer + 递归下降解析器（token.rs、parser.rs、expr.rs、query.rs、statement.rs、copy.rs、stage.rs、stream.rs、script.rs）
- `src/visit/`、`visit_derive/`（proc-macro）、`fuzz/`（fuzzer）

**`sql/`** (`databend-common-sql`) — **规划器/绑定器/优化器**（即用户所说的"planner"）
- `src/planner/` — 核心：
  - `binder/`（50 文件）：名称解析 & 语义绑定（bind_query/、bind_table_reference/、bind_mutation/、ddl/）
  - `plans/`（48 文件）：逻辑算子（scan、filter、join、aggregate、sort、limit、window、exchange、mutation）
  - `optimizer/`：
    - `optimizers/`：**Cascades**（`cascades/`）+ **Hypergraph DP**（`hyper_dp/`）+ CSE（`cse/`）+ `distributed/` + `recursive/` + `rule/`
    - `ir/`：Memo（`memo.rs`、`group.rs`）、`expr/`、`property/`、`stats/`、`cost/`、`statistics/`
  - `metadata/`、`semantic/`、`expression/`、`execution/`、`format/`
- `src/executor/`、`src/evaluator/` — SQL 侧执行/求值辅助

**`expression/`** (`databend-common-expression`) — **运行时标量表达式 + 内存列/值框架**
- `Expr`/`ColumnRef`/`Constant`/`FunctionCall`、DataType 系统、DataBlock（列批）
- `evaluator.rs`、`type_check.rs`、`constant_folder.rs` — 求值/类型推断/常量折叠
- `kernels/`（向量化计算核）、`filter/`、`function/`（FunctionRegistry）
- `aggregate/`、`hilbert/`、`sampler/`、`geographic/`、`hash_util.rs`、`row_encoding.rs`

**`functions/`** (`databend-common-functions`) — 内置函数实现
- `scalars/`（24 类）：arithmetic、decimal、mathematics、timestamp、string、array、map、variant、binary、bitmap、boolean、comparison、control、geographic、hash、hilbert、tuple、vector 等
- `aggregates/`（39 个）：count、sum、avg、min_max、quantile（disc/cont/tdigest）、stddev、histogram、retention、window_funnel、bitmap 等
- `srfs/`（集合返回函数）、`cast_rules.rs`
- 注：arithmetic/datetime/decimal/geo/math 的实现进一步拆分为独立 `databend-functions-scalar-*` crate

**`pipeline/`** (`databend-common-pipeline` + `-transforms`) — **执行流水线 & 调度框架**
- `src/core/`：pipeline.rs、pipe.rs、port.rs、processor.rs（Processor trait）、profile.rs、waker.rs
- `src/basic/`、`src/sinks/`、`src/sources/`
- `transforms/`（独立 crate）：processors/transforms/（物理算子实现）+ processors/traits/

### 4.2 存储层

**`storages/`** — 13 个表引擎 crate + `common/`（8 共享 crate）：

| 引擎 | 说明 |
|------|------|
| **`fuse/`** | **旗舰云原生存储引擎**：fuse_table、fuse_column、io/、operations/（read/insert/merge/mutation/compact/purge/recluster/analyze/vacuum）、pruning/ + pruning_pipeline/（segment/block 裁剪）、statistics/ |
| `system/` | 系统表（`system.*`） |
| `information_schema/` | INFORMATION_SCHEMA |
| `parquet/`、`orc/` | 文件格式表引擎 |
| `iceberg/`、`delta/`、`hive/` | 外部 catalog/表集成 |
| `stream/` | 流表 |
| `stage/` | Stage 后端表 |
| `basic/` | Memory/Null/View 等简单引擎 |
| `factory/` | storage_factory.rs 引擎选择分发 |

`common/`（8 共享 crate）：blocks、cache、index（倒排/向量/聚合索引）、io、pruner、session、stage、table_meta（segment/block 元格式）

### 4.3 顶层集成 crate

**`service/`** (`databend-query`) — **集成层，依赖所有其他 crate**：
- `servers/` — 协议前端：`http/v1/`（**REST API**：query/catalog/session/stage/streaming_load/users/roles）、`mysql/`、`flight/`（Arrow Flight）、`flight_sql/`、`admin/`、`metrics/`
- `sessions/` — session_mgr、query_ctx（+ query_ctx/）、session_ctx、session_privilege_mgr、queue_mgr、runtime_filter_state
- `interpreters/` — **185 文件**，每种 SQL 语句一个解释器（interpreter_factory 分发）：interpreter_select、interpreter_insert、interpreter_mutation、interpreter_copy_into_table、interpreter_table_create/drop/alter、interpreter_explain、interpreter_vacuum_*、interpreter_txn_* 等
- `pipelines/` — pipeline_builder、builders/、executor/、processors/（物理计划→可执行 processor 流水线）
- `schedulers/` — scheduler.rs、fragments/（分布式 fragment 调度）
- `physical_plans/` — 物理计划节点定义
- `catalogs/`、`clusters/`、`databases/`、`locks/`、`auth.rs`、`spillers/`、`stream/`、`history_tables/`、`table_functions/`、`builtin/`

### 4.4 辅助/企业 crate

| Crate | 职责 |
|-------|------|
| `management/` | 管理 API 类型：user/role/warehouse/workload/task/procedure/quota/stage/udf |
| `users/` | 运行时用户/角色/访问控制：user_api、role_mgr、visibility_checker、jwt、password_policy |
| `config/` | 服务配置类型 & 解析 |
| `settings/` | 全局 & 会话设置 |
| `datavalues/` | **遗留**内存值/列层（正被 `expression` 取代） |
| `formats/` | 外部格式序列化（CSV/JSON/Parquet/NDJSON） |
| `codegen/` | 代码生成器（算术结果类型生成） |
| `script/` + `script_udf_support/` | 脚本执行 + Python UDF 沙箱（feature-gated） |
| `storage_stage_support/`、`task_support/` | 可选集成（feature-gated） |
| `ee/` + `ee_features/`（10 crate） | 企业功能：attach_table、data_mask、fail_safe、hilbert_clustering、storage_encryption、vacuum_handler 等 |

---

## 五、`src/meta/` — 元数据层

**关键边界**：核心 meta-service 已迁至独立 `databend-meta` 仓库。本仓库保留：

| 层 | Crate | 职责 |
|----|-------|------|
| **应用层** | `api` | KVApi、DatabaseApi、TableApi、fetch_id、txn_put |
| | `app` | 元数据类型（TableMeta、schema、id_generator、tenant） |
| | `app-storage` | 存储相关类型（从 app 拆出） |
| | `store` | MetaStore 实现：本地嵌入或 gRPC 客户端 |
| **序列化** | `protos` | protobuf 消息定义 |
| | `proto-conv` | FromToProto 转换 + `META_CHANGE_LOG` 版本追踪 + 兼容性测试 |
| **高级功能** | `admin` | databend-meta 的 Admin HTTP API |
| | `plugins/cache` | 分布式缓存（watch API 同步） |
| | `plugins/semaphore` | 分布式信号量 |
| | `control` | export/import/filter_tenant/keys_layout/lua |
| | `process` | 元数据处理工具 |
| **配置/运行** | `cli-config` | CLI 配置解析 |
| | `runtime` | 运行时适配器（桥接 databend-meta → common-base） |
| | `ver` | Query↔Meta 版本兼容常量 |
| **二进制** | `binaries/` | databend-meta（EE/OSS）、metabench、metactl、metaverifier |

---

## 六、二进制入口

| 二进制 | 路径 | 功能 |
|--------|------|------|
| `databend-query` | `binaries/query/ee_main.rs` | 企业查询节点（EnterpriseServices::init） |
| `databend-query-oss` | `binaries/query/oss_main.rs` | OSS 查询节点（OssLicenseManager） |
| `databend-meta` / `-oss` | `meta/binaries/meta/` | 元服务节点 |
| `databend-metactl` | `meta/binaries/metactl/main.rs` | 元服务控制 CLI（export/import/dump/lua/状态管理，738 行） |
| `databend-metabench` | `meta/binaries/metabench/main.rs` | 元服务基准测试 |
| `databend-metaverifier` | `meta/binaries/metaverifier/main.rs` | 数据校验 |
| `databend-bendsave` | `bendsave/src/main.rs` | 备份/恢复（backup/restore 子命令） |
| `bendpy` | `bendpy/`（cdylib） | Python 嵌入式模块，PyO3，in-process DataFrame API |
| `table-meta-inspector` | `binaries/tool/` | v3 表元解码工具 |

查询二进制共享 `cmd.rs` + `entry.rs`，后者启动完整服务栈：FlightService、MySQLHandler、HttpHandler、MetricService、AdminService、FlightSQLServer、ClusterDiscovery 注册。

---

## 七、依赖层次关系

从各 crate 的 `Cargo.toml` 推导出的分层（底层→顶层）：

```
L0 基础原语（src/common/ + src/meta/）
   base, exception, io, column, hashtable, datavalues, statistics, metrics,
   tracing, timezone, grpc, storage, license, meta-api/app/store/client/runtime

L1 ast ──────► L0                          （近叶节点解析器）
   settings, config ──► L0

L2 expression ──► ast + L0                 （运行时值/表达式层）

L3 functions ───► expression + L0          （通过 ctor 注册进 expression 的 FunctionRegistry）

L4 catalog ─────► expression, ast, pipeline, settings, users,
                   meta-api/app/store/client, storage, storages-common-*
                   （定义 Catalog/Database/Table/TableContext trait — 元数据桥接）

L5 sql (planner) ──► ast, expression, functions, catalog, pipeline,
                      settings, management, users, storages-basic, meta, ee_features
                      （在 catalog/functions 之上；不依赖 service）

L5 storages/* ────► catalog, expression, pipeline, storages-common/*
                   （各引擎实现 catalog::Table trait）

L6 service (databend-query) ──► 一切：sql, catalog, expression, functions,
                                  pipeline, pipeline-transforms, 全部 storages,
                                  management, users, formats, script, meta, 全部 ee_features
                                  ← 顶层集成 crate
```

**请求运行时流程**映射到分层：
```
servers/{http,mysql,flight} → sessions/query_ctx → interpreters/interpreter_factory
→ sql/planner (binder→planner→optimizer) → physical_plans + pipelines/pipeline_builder
→ schedulers (分布式 fragments) → pipeline/transforms processors → storages/* (via catalog::Table)
```

---

## 八、架构优势评估

### 1. 清晰的 trait 解耦（两大关键接缝）
- **Catalog 接缝**：`catalog` 定义 `Catalog`/`Table`/`Database` trait，`storages/*` 实现。`sql` 和 `service` 面向抽象编程，不依赖具体引擎——新增表引擎只需实现 trait + 在 `factory` 注册。
- **Function 接缝**：`expression` 拥有 `FunctionRegistry`，`functions` 通过 `ctor` 注册——让规划器/类型检查器引用函数而无循环依赖。控制反转设计干净。

### 2. 严格分层无环
`service` 是唯一依赖所有 crate 的顶层集成点；`sql` 从不依赖 `service`；`expression` 从不依赖 `functions`。层次清晰，编译可并行。

### 3. 企业版/OSS 分离优雅
通过**双 main 文件**（`ee_main.rs` / `oss_main.rs`）共享 `cmd.rs`/`entry.rs`。企业代码在 `databend-enterprise-*` crate 后，由 `EnterpriseServices::init` vs `OssLicenseManager` 切换。meta 侧同理。

### 4. 元数据兼容性纪律严明
protobuf protos → `FromToProto` 转换 → 版本化 `META_CHANGE_LOG` → 每版本兼容性测试。`frozen_api` proc-macro 将此理念扩展到内存结构体（编译期 SHA256 哈希）。on-disk DataVersion V0→V004 自动升级。

### 5. 存储集中化
`common-storage` 是 opendal 的单一 chokepoint（10+ 后端），三类 operator（data/cache/temporary）+ endpoint 策略注册——干净抽象。

### 6. Meta 服务外迁解耦
核心 meta-service 迁至独立仓库，通过 `databend-meta` / `databend-meta-client` 外部 crate 引入——允许独立演进和发布。

### 7. Feature-gated 可选集成
`script-udf`、`task-support`、`storage-stage` 隔离在独立 crate，通过 cargo feature 接入 `service`，保持默认构建精简。

### 8. 性能导向选择
`hashtable` 从 ClickHouse 移植线性探测哈希表；`column` 维护自有列式类型（parallel to arrow）+SIMD；`vector` 提供向量化距离计算——针对 OLAP 场景的刻意选择。

---

## 九、架构劣势与关注点

### 1. `service` crate 过重（编译负担）
`databend-query`（service）依赖**一切**：185 个 interpreter 文件 + 所有存储引擎 + 所有 ee_features。这是已知的集成重量级——任何存储/函数变更都可能触发 service 重编译。全量 debug 构建约 20 分钟（AGENTS.md 已记录）。

### 2. `datavalues` 遗留层迁移未完成
README 明确标注 `datavalues/` 为 **legacy**，正被 `expression/` 取代。两层并存是技术债——维护者需清楚何时用哪个，新代码应避免 datavalues。

### 3. `common/` 粒度过细
21 个 crate 中有几个极小：`telemetry`（1 函数）、`timezone`（1 LUT 模块）、`vector`（仅距离函数）。利于编译并行，但增加 workspace 复杂度和认知负担。

### 4. `common-exception` 依赖方向异常
`common-exception` re-exports `common-ast` 的 `ParseError`/`span`——一个"common" crate 依赖 `query/ast`，违反了 common 在 query 之下的概念层次。值得在依赖方向分析中关注是否有其他类似 `common/* → query/*` 边。

### 5. Meta 服务跨仓库协作成本
核心 meta-service 在独立仓库意味着某些服务端修复需跨仓库变更（`src/meta/AGENTS.md` 已明确提示此边界）。`src/meta/ee` crate 目前近乎空壳，暗示企业 meta 功能仍在孵化或位于外部仓库。

### 6. 双优化器复杂度
`sql/src/planner/optimizer/optimizers/` 同时维护 **Cascades**（`cascades/`）和 **Hypergraph DP**（`hyper_dp/`）两套优化器。这是架构上最复杂的组件——维护两条路径的成本高，需明确何时用哪条。

### 7. 仓库内架构文档稀疏
历史上存在的 `docs/overview/architecture.md` 和 `docs/rfcs/` 已全部迁移至 docs.databend.com。仓库内仅剩 `docs/designs/proxy-engine-design.md` + 2 个根级 RFC。新贡献者需跨站点查阅架构文档——降低了仓库自包含性。新 RFC 约定（根级日期前缀文件）尚未形成规模。

### 8. 二进制版本兼容面复杂
两套机制并行：`src/meta/ver` 常量（Query↔Meta semver）+ on-disk `DataVersion`（V0→V004）自动升级。丰富的兼容矩阵增加运维复杂度。

---

## 十、总结

Databend 的架构体现了**成熟的工程纪律**：

**强项**在于 trait 解耦（catalog/function 两大接缝）、严格无环分层、企业/OSS 干净分离、元数据兼容性纪律、以及性能导向的基础设施选择（ClickHouse 哈希表、自有列式类型、SIMD）。依赖层次 L0→L6 清晰可推理，请求流程与分层完美映射。

**需关注**的是 `service` crate 的集成重量（编译时间）、`datavalues` 遗留迁移未完成、`common/` 粒度可能过细、双优化器维护成本、以及 meta 服务外迁带来的跨仓库协作开销。

整体而言，这是一个**架构成熟度高于多数开源数据仓库项目**的代码库——模块边界清晰、文档纪律好、兼容性严肃对待。主要风险集中在规模带来的编译效率和遗留迁移完成度上，而非结构性缺陷。

---

## 附录：关键架构文档参考

| 文档 | 位置 | 主题 |
|------|------|------|
| README.md | [repo](https://github.com/databendlabs/databend/blob/main/README.md) | 设计哲学、Agent-Ready 架构 |
| Databend Cloud Architecture | [docs.databend.com](https://docs.databend.com/guides/cloud/overview/architecture) | 三层解耦架构 |
| How Fuse Engine Works | [docs.databend.com](https://docs.databend.com/guides/how-databend-works/how-databend-fuse-engine-works) | 存储引擎设计（Snapshot/Segment/Block） |
| How Databend Optimizer Works | [docs.databend.com](https://docs.databend.com/guides/how-databend-works/how-databend-optimizer-works) | 优化器流水线（DP+Cascades） |
| New SQL Planner Framework RFC | [docs.databend.com](https://docs.databend.com/developer/community/rfcs/new-sql-planner-framework) | 规划器架构（Binder/Cascades/Memo） |
| Distributed Query and Shuffle RFC | [docs.databend.com](https://docs.databend.com/developer/community/rfcs/data-shuffle) | 分布式执行（StagePlan/Flight RPC） |
| Join Framework RFC | [docs.databend.com](https://docs.databend.com/developer/community/rfcs/join-framework-design) | Join 规划与算法 |
| Multiple Catalog RFC | [docs.databend.com](https://docs.databend.com/developer/community/rfcs/multiple-catalog) | Catalog 系统抽象 |
| metasrv-txn RFC | [docs.databend.com](https://docs.databend.com/developer/community/rfcs/metasrv-txn) | 元服务事务设计 |
| Proxy Engine Design | [repo](https://github.com/databendlabs/databend/blob/main/docs/designs/proxy-engine-design.md) | 多物理布局路由（含代码路径集成示例） |
| agents/repository-structure.md | [repo](https://github.com/databendlabs/databend/blob/main/agents/repository-structure.md) | 仓库模块边界指南 |
