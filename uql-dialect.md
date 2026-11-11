# Semora UQL 方言支持设计(目标:OneQuery SQL)

| 项 | 值 |
|---|---|
| 状态 | 设计稿(待评审,未实施) |
| 日期 | 2026-11-10 |
| 范围决策 | 目标后端不含 Druid;能力范围两阶段(第一阶段忠实渲染现有闭合 QueryAst,第二阶段扩展见附录 A);验收双层(框架内闭环 + OneQuery 引擎集成门) |
| 规格依据 | OneQuery SQL 语言参考(外部目录 `/Users/Admin/Desktop/uql`,快照哈希见 §15) |
| 关联文档 | `ontology/docs/DIALECT-CONTRACT.md`(方言共同契约)、`ontology/docs/QUERY.md`、`docs/MODEL-RELEASE.md`、`AGENTS.md` |
| 更新记录 | 当前 v7(2026-11-10);完整版本历史见 §16 |

本设计**不参考现有 uql 渲染器的任何实现决策**;全部映射、语义钉死与结构选择依据 OneQuery 规格文档与共享 `QueryAst`(`ontology/src/query.telora`)的权威定义从零推导。与既有实现相同的选择(如有)仅因规格与架构共同指向,不构成对其的继承。

---

## 1. 背景与目标

UQL 是统一查询引擎 **OneQuery** 的对外 SQL 方言:消费方宿主把 Semora 编译产物 `{sql, bindings}` 提交到 OneQuery 的查询接口,由引擎按数据源路由(Zenith / StarRocks / 多源)改写并下推执行,屏蔽底层存储差异。

本设计定义 Semora 项目内 UQL 方言渲染器的**完整能力契约**:

1. 把闭合 `QueryAst` 的全部词汇渲染为符合 OneQuery 规格的 SQL;
2. 与 SQLite / PostgreSQL 渲染器保持绑定级等价(`bindings` 深度相等,SQL 仅为序列化差异);
3. 在规格未定义语义处显式拒绝(fail-closed),满足"一个 Plan 无论方言只有一个含义";
4. 提供双层验收路径与发布集成。

## 2. 范围

### 2.1 目标后端

| 后端 | 说明 | 本设计立场 |
|---|---|---|
| Zenith(JDBC/GaussDB,分库分表) | 关系型,完整 SQL | ✅ 目标 |
| StarRocks(MPP) | 完整 SQL;TIMESTAMP/DATETIME 均映射 `datetime` | ✅ 目标 |
| 多源异构 | 统一路由 StarRocks | ✅ 目标(随 StarRocks) |
| Druid | 时序;存在 UNION 降级、无 INTERSECT/EXCEPT、窗口帧限制、会话时区自动偏移 | ❌ **明确排除**;若未来纳入,需另行专项设计:UNION 去重降级补偿、窗口帧限制、INTERSECT/EXCEPT 缺失、会话时区偏移四项 |

排除 Druid 的依据:规格后端能力矩阵中,Druid 是唯一对渲染器词汇全集存在缺口的后端;Zenith/StarRocks 对本设计用到的全部语法(集合操作、窗口、JOIN、子查询、NULLS FIRST/LAST)原生支持。

> 范围注记(m10):多源异构查询统一路由 StarRocks,但各数据源的函数改写仍按各自方言独立处理(规格"数据源与方言路由")——**若多源查询包含 Druid 表,其函数行为不受本设计保证,视为超出范围**,Tier2 不覆盖。

### 2.2 能力范围(两阶段)

- **第一阶段(本文主体)**:忠实渲染现有闭合 `QueryAst` —— 全部 8 种 `QueryPlan` 变体、全部 `ScalarFunction`/`AggregateFunction`/结构算子。不新增任何 AST 能力。这符合方言共同契约:"新的共享 AST 算子只有在每个受支持方言都能等价执行后才暴露给本体降级"。
- **第二阶段(附录 A)**:候选扩展清单,每项标注其"三方言等价义务"与引擎验证要求,逐项走准入流程,不在本设计内承诺。

### 2.3 非目标

- 不执行 SQL(执行属 OneQuery 引擎;Semora 交付止于 `{sql, bindings}`);
- 不改动 `query.telora` 的闭合词汇与校验语义(`Ordering`、`validated_output_limit` 等均按现状消费);
- 不覆盖规格中 AST 词汇之外的能力(JSON/数组函数、PIVOT、层次查询、EXPLAIN、CTE、量化比较等,见附录 A 归档);
- 不为 Druid 提供兼容(§2.1)。

## 3. 架构定位与契约遵从

### 3.1 管线位置

```
意图 JSON → intent.telora(解码/语义校验) → lower.telora(图降级)
         → query.telora(闭合 QueryAst + validate + Profile 门禁)
         → sqlite::build_query ┐
         → postgres::build_query ├─ ToQuery: Fn(qb::QueryAst) -> qb::Query
         → uql::build_query ┘          (本设计)
         → {sql, bindings} → 宿主 → OneQuery 查询接口(REST)
```

UQL 渲染器是同一 `QueryAst` 的第三个消费者;方言在工厂构造期选定(`query_request_method_factory(payload, uql::build_query)`),请求本身不选择后端。

### 3.2 模块接口

```telora
pub def build_query: Fn(qb::QueryAst) -> qb::Query
```

- 入口立即重校验:结构校验(`validate_structure`/`validate_distinct_plan`/集合操作数校验等,按计划变体分派)+ `validate_contextual_pagination`(与 SQLite 入口一致,sqlite.telora:928)+ `validated_output_limit`(拒绝伪造上限,`query.telora:551-564`);
- 渲染上下文 `RenderCtx`:携带构建期绑定数组、保留别名集、内部别名计数;所有渲染阶段不可变传递;
- 产物:`Query = {sql: String, bindings: Array(Val)}`,`Val` 跨界为原生 JSON 标量。

### 3.3 方言共同契约的逐条落实(DIALECT-CONTRACT.md)

| 契约条目 | 本设计的落实 |
|---|---|
| 一个查询形状无论方言只有一个含义;不得静默近似 AST 节点、重解释身份键、改变绑定顺序、施加会话相关时间语义 | §5.3 语义钉死;§8 不变量;规格未定义处 fail-closed(§5.4) |
| `BuildCtx` 分配稳定绑定序;`?n`/`$n` 对同一句柄;重复引用复用序号;`Query.bindings[n-1]` 即句柄 n 的标量 | §4.2:UQL 以纯位置 `?` 消费同一分配层——绑定数组为句柄数组的**出现序展开**(OQ-9;等价断言见 §8.1) |
| 标识符按标识符校验而非任意 SQL 接受 | §4.1:`is_sql_identifier` 白名单 + 反引号 |
| 新共享 AST 算子须所有受支持方言等价执行后才暴露 | §9.2:UQL 加入准入流程(渲染方案 + 双层验证为准入材料) |

## 4. 词法层设计

### 4.1 标识符

- 引用形式:**反引号** `` `ident` ``(规格"标识符规则":引用标识符使用反引号;`catalog.schema.table` 三段式支持)。
- 白名单:沿用 `qb::is_sql_identifier`(`^[A-Za-z_][A-Za-z0-9_]*$`,`query.telora:681-685`);不合规即 `fail!`,与 SQLite/PG 一致。物理表/列名来自模型声明的 `entity_source`/`@column`,当前词汇不含多段名;若未来引入,三段式按 `.` 连接后再整体校验每段。
- **陷阱(规格明文)**:双引号 `"text"` 在 OneQuery 解析为**字符串字面量**(MySQL 语义),不是标识符引用。因此 PG 风格 `"a"."b"` 在 UQL 中是语义错误,反引号是唯一正确选择。

### 4.2 参数与绑定

- 占位符:**纯位置 `?`**。**OQ-9 已确认**:OneQuery 查询接口以 `?` + 位置数组接受动态参数;规格"参数占位符"节列出的 `$1/$2`、`:name`、`@var` 属解析器能力菜单,并非提交路径的可用风格(引擎现实优先,建议规格标注主风格)。每个 `?` 按其在 SQL 文本中的**出现序**消耗绑定数组的一项;占位符可用于 WHERE / IN / BETWEEN / 比较 / **LIMIT / OFFSET** / JOIN ON / CTE 等位置(规格明文)。
- **出现序展开模型**:qb 的 `AllocatedBind` 携带句柄编号,同一句柄允许在计划中多次引用(DIALECT-CONTRACT:"repeated references reuse its index")。SQLite/PG 渲染编号占位符(`?n`/`$n`),绑定数组**按句柄分配序**每句柄一项;UQL 渲染 `?`,绑定数组 = **句柄数组按 SQL 出现序的展开**——同一句柄出现 k 次,其值在数组中出现 k 次;分配序 ≠ 出现序时,数组顺序遵循出现序(§7.8 嵌套即产生此类文本)。**未在最终 SQL 中出现的句柄不进入数组**——位置语义要求参数数恰等于 `?` 个数(引擎不接受多余参数),展开模型天然满足;这与编号占位符"容忍未引用参数"的行为不同。
- **绑定等价(第一层验收强制断言)**:`expand_by_occurrence(共享句柄数组, 出现序) == UQL bindings`——三方言仍共享同一分配层与同一句柄数组,等价从"字面逐位相等"重定义为"可计算的展开相等"(§8.1、§10.1)。
- 运行时值永不内联进 SQL 文本;`Val` 序列化为 JSON 原生标量(字符串/数值/布尔/NULL),由 OneQuery 按位接收(§11.2)。

### 4.3 内部别名

两级命名约定(与 SQLite/PG 渲染器对齐,便于跨方言审阅对照,m6):

- **语义名**:跨方言共享的结构性别名列(`__latest_stage`/`__latest_row`、`__q_left`/`__q_right`、`__q_base`、`__q_count`、`__q_capped`、`__q_qualification`、`__q_rn` 等)——同名同义,三方言渲染文本可直接并排审阅;
- **编号名**:临时派生表与分区隐藏列从 `__q_<n>` 序列分配(隐藏键沿用共享命名 `__q_pk_<i>`/`__q_ok_<j>`),分配前先收集计划内全部既有别名(含语义名)为保留集,保证碰撞无关。

### 4.4 类型与 CAST

| 用途 | 渲染 | 规格依据(data-types) |
|---|---|---|
| 时间值进入时间函数 | `CAST(v AS TIMESTAMP)` | TIMESTAMP 在册;StarRocks 侧映射 `datetime`(引擎负责) |
| 日期桶输出(DateUtc) | `CAST(... AS DATE)` | DATE 在册 |
| epoch 整数运算 | `CAST(... AS BIGINT)` / `CAST(... AS INTEGER)` | 均在册 |
| 对比排名的浮点化 | `CAST(... AS DOUBLE)` | DOUBLE 在册 |

不使用 `::` 后缀、`TRY_CAST`、`CONVERT`(闭合词汇不引入;`TRY_CAST` 的容错语义反而破坏失败模式等价)。

输入格式假定(m7):`CAST(v AS TIMESTAMP)` 的 `v`(绑定参数或列)为 DIALECT-CONTRACT 的规范 UTC 秒文本或引擎列原生类型;规格未记载 CAST 的文本解析格式,**Tier2 增加解析用例**(§10.3)。

### 4.5 字面量与注入面

SQL 文本中除引擎关键字与白名单标识符外,唯一动态内容是位置占位符 `?` 与内部别名编号;字符串/数值一律走绑定。注入面与三方言共同为零。

## 5. 标量表达式映射

### 5.1 `ScalarFunction` 对照表(`query.telora:61-89` 全集)

| ScalarFunction | UQL 渲染 | 规格依据 | 说明 |
|---|---|---|---|
| `Substr` | `substr(args[0], args[1][, args[2]])` | functions·字符串 | 参数序一致;pos≤0 仅出现于 EndsWith 谓词算术,该场景谓词结果对跨引擎 pos 约定差异免疫(m11);Tier2 保留防御性用例 `substr(s, 0, n)` |
| `Instr` | `instr(s, sub)` | functions·字符串 | 位置语义(一基、缺省 0)列入 Tier2 验证清单 |
| `BinaryText` | **fail-closed** | 规格仅有 `COLLATE 'zh_CN'`(locale),无二进制排序规则 | 见 §5.4 与建模规则 R-1 |
| `RegexMatch` | `(regexp_count(args[0], args[1]) > 0)` | functions·字符串 `regexp_count` | **参数序(m1)**:args[0]=被检值、args[1]=模式——与 SQLite 的 `regexp(pattern, value)` 注册序**相反**,实现时按此映射勿翻转;选函数式而非 `~` 操作符:函数改写是引擎核心职责路径,Tier1 可确定性 shim;`~` 备选 Tier2 评估 |
| `If` | `CASE WHEN args[0] THEN args[1] ELSE args[2] END` | sql-syntax·CASE WHEN | 参数序一致(cond, a, b);选 CASE 而非规格 `if()`:与另两方言结构对齐、避免函数签名重写差异 |
| `Add` / `Sub` | `(a + b)` / `(a - b)` | operators·算术 | |
| `Div` | `(args[0] / args[1])` | operators·算术:除法为真除(`10 / 3 = 3.333...`) | **前置条件(M1,按推荐方案钉死)**:UQL `/` 真除 vs SQLite/PG 整型截断除——AST 不保证操作数类型,当前亦无任何降级产生 `Div`(仅 profile 白名单出现);`Div` 仅在调用方保证非整数域(或已统一 CAST 双精度)时合法,未来产生方按 §9.2 准入须**三方言同步**保证双精度域(排名算子已在渲染层 CAST,§7.3);零值守卫由渲染器的排名算子映射负责(§7.3 的 `NULLIF`),`Div` 本身不注入守卫 |
| `Lower` | `lower(s)` | functions·字符串 | **OQ-5 已确认**:引擎 `lower()` 折叠域符合 AST 契约(仅 A–Z 折叠),直渲;Tier1b 在 SQLite 上执行时其原生 ASCII-only `lower` 恰与契约一致;Tier2 保留一次非 ASCII 抽查 |
| `Length` | `char_length(s)` | functions·字符串:字符长度 | 明确区分 `length`(字节)——AST `Length` 为字符语义 |
| `UtcDaysAgo` | `concat(timestamp_format(timestampadd('DAY', (0 - CAST(args[1] AS INTEGER)), CAST(args[0] AS TIMESTAMP)), '%Y-%m-%d'), 'T', timestamp_format(timestampadd('DAY', (0 - CAST(args[1] AS INTEGER)), CAST(args[0] AS TIMESTAMP)), '%H:%i:%s'), 'Z')` | functions·字符串 `concat` + 日期时间 | **参数序(m1)**:args[0]=时间戳、args[1]=天数(sqlite.telora:108 为既有锚点),符号为减(n 天前);**OQ-1 已确认**:格式串不支持 T/Z 字面量,T/Z 经 `concat` 字面量拼接;**OQ-8 已确认**:`concat` 任一参数 NULL → 整体 NULL,NULL 输入安全传播;`timestampadd` 子表达式按 SQL 惯例重复出现——同一 `AllocatedBind` 的每次出现各渲染一个 `?`,该值在 UQL 数组中按出现序重复出现(§4.2 展开模型;分配层仍只分配一个句柄) |
| `UtcTimeBucket(spec)` | 见 §5.2 专表 | functions·日期时间 | 保持输入逻辑类型与 NULL(AST 注释) |
| `Eq`/`Ne`/`Lt`/`Le`/`Gt`/`Ge` | `=`,`<>`,`<`,`<=`,`>`,`>=` | operators·比较 | `Ne` 统一渲染 `<>`(规格同义 `!=` 不用,减少一处等价面) |
| `And`/`Or`/`Not` | `(a AND b)`,`(a OR b)`,`NOT (a)` | operators·逻辑 | 全部加括号,不依赖优先级 |
| `IsNotNull` | `v IS NOT NULL` | operators·谓词 | |

### 5.2 `UtcTimeBucket` 映射矩阵(unit × encoding)

AST 契约(`query.telora:77`):"Calendar bucket start, **preserving the input logical type** and NULL"。支持日历范围 `0001-01-01T00:00:00Z` 至 `9999-12-31T23:59:59.999…Z`,epoch 毫秒表示即 `-62135596800000 ≤ v < 253402300800000`(与 SQLite/PG 渲染器共用的同一常量对)。

| encoding \ unit | Hour | Day | Month |
|---|---|---|---|
| **DatetimeUtc** | `time_floor(CAST(v AS TIMESTAMP), 'PT1H')` | `CAST(date_trunc('day', CAST(v AS TIMESTAMP)) AS TIMESTAMP)` | `CAST(date_trunc('month', CAST(v AS TIMESTAMP)) AS TIMESTAMP)` |
| **DateUtc** | *(qb 校验层禁止:DateUtc 无小时桶)* | `date_trunc('day', CAST(v AS TIMESTAMP))` | `date_trunc('month', CAST(v AS TIMESTAMP))` |
| **EpochMillis** | `CAST((v - mod(mod(v, 3600000) + 3600000, 3600000)) AS BIGINT)` | `CAST((v - mod(mod(v, 86400000) + 86400000, 86400000)) AS BIGINT)` | `CAST(timestampdiff('SECOND', TIMESTAMP '1970-01-01 00:00:00', CAST(date_trunc('month', timestampadd('DAY', CAST((v - mod(mod(v, 86400000) + 86400000, 86400000)) / 86400000 AS INTEGER), TIMESTAMP '1970-01-01 00:00:00')) AS TIMESTAMP)) AS BIGINT) * 1000` |

EpochMillis 全部形态外包一层日历范围守卫:

```sql
CASE WHEN v >= -62135596800000 AND v < 253402300800000 THEN <bucket> ELSE NULL END
```

守卫语义:出界输入返回 NULL(与逻辑类型契约一致),绝不向引擎递交会溢出/报错的时间运算;`mod(mod(v, W) + W, W)` 双取模对截断式(fmod,符号随被除数)与向下取整式(符号随除数)两种引擎约定**均不变**(已逐例验证,m2)。月桶走"整日 timestampadd,再截月,再 timestampdiff 秒"路径,避免 32 位 INT 溢出月份计数;其内层 `date_trunc('month', …)` 结果包 `CAST(… AS TIMESTAMP)` 后再进 `timestampdiff`,不依赖引擎对 DATE 参数的隐式转换;epoch 锚点使用规格**类型化字面量** `TIMESTAMP '1970-01-01 00:00:00'`(m12,不传裸字符串)。

**单位拼写按规格示例逐函数对齐(m8)**:`date_trunc` 小写(`'day'`/`'month'`,规格唯一示例为小写)、`timestampadd`/`timestampdiff` 大写(规格示例 `'HOUR'`/`'DAY'`)、`time_floor` ISO-8601 时长 `'PT1H'`;大小写敏感性规格未记载,Tier2 双拼写核验。

**OQ-2 确认后的映射依据(date_trunc 返回 DATE)**:

- **DatetimeUtc 小时桶弃用 `date_trunc`**——DATE 返回值在源头即丢失小时信息,任何外层 CAST 都无法恢复(会得到日粒度零点,错误结果);改用 `time_floor(v, 'PT1H')`。**OQ-7 已按规格闭环**:functions.md 与 functions.json 双份标注 `time_floor` 返回 **DATETIME**("时间向下对齐",保时间部分),与 `date_trunc` 返回 DATE 形成明确对照;Tier2 例行实测复核。备选方案保留文档记录:`timestamp_floor(ts, unit)`(规格标注返回 TIMESTAMP,但单位拼写未示例)或组合式 `timestampadd('HOUR', timestampdiff('HOUR', <固定锚点时间戳>, v), <固定锚点时间戳>)`(负计数行为需一并确认)。
- **DatetimeUtc 日/月桶**:`date_trunc` 返回 DATE(当日/当月一日),外层 `CAST(… AS TIMESTAMP)` 无损恢复为当日/当月零点的 DATETIME,与"保持输入逻辑类型"契约一致。
- **DateUtc 日/月桶**:`date_trunc` 的 DATE 返回值**恰好就是** DateUtc 的逻辑类型,直用,无需任何 CAST——该行较 v1 更简且不再有类型歧义。

### 5.3 语义钉死决策汇总(为什么"多此一举")

| 决策 | 钉死的语义 | 不钉死的后果 |
|---|---|---|
| Length 用 char_length | 字符数(非字节) | `length` 在部分后端是字节语义 |
| Ne 用 `<>` | 单一拼写 | `!=`/`<>` 在改写层的等价性未被规格逐后端声明 |
| epoch 守卫 CASE | 出界 → NULL | 引擎对越界时间运算报错,失败模式与 SQLite/PG 不等价 |
| 除法不加守卫 | `Div` 为纯真除 | 零除守卫属对齐排名职责(`NULLIF(b, 0)`),混入会改变 HAVING/过滤可组合性 |
| Div 前置条件(M1) | `Div` 仅准入非整数域 | 整型 Div 在 SQLite/PG 截断而 UQL 真除,三方言行集分叉;当前无产生方,未来产生方按准入流程三方言同步 CAST |

### 5.4 fail-closed 边界(第一阶段全集)

| 输入 | 行为 | 理由 |
|---|---|---|
| `ScalarFunction::BinaryText` | 渲染期 `fail!`,错误携带规格依据("UQL 规格未定义二进制排序规则") | 规格 COLLATE 仅 locale 语义;二进制排序跨后端不可等价 |
| 标识符/占位符不合法 | `fail!` | 与三方言共同纪律 |
| 计划含 `offset` 而无 `limit` 且无 `result_limit` | 渲染期 `fail!` | UQL 语法中 OFFSET 必须伴随 LIMIT(规格无 bare OFFSET / `LIMIT -1` 形态);该形态无公开意图产生者(仅低层 QueryBuilder 可构造);如需支持,按 §9.2 准入引入哨兵上限方案 |

> v3 变更:原列于此的"计划含 `Having` 而 `grouping` 为空 → fail!"已移除——引擎确认 OneQuery **接受无 GROUP BY 的 HAVING**(OQ-4 闭环),此类计划按 §7.1 原生渲染 `HAVING <聚合谓词>`;SQLite/PostgreSQL 渲染器本就支持该形态,三方言等价不受影响。

**建模规则 R-1**(发布期强制,非运行期):模型 `PlanProfile.allowed_scalars` 含 `BinaryText` 时,`release.config.json` 的 `dialects` 不得包含 `uql`;`model-release` 构建期校验并拒绝。这把能力缺口从"运行期渲染失败"提前到"发布期不可选"。

## 6. 聚合与度量

### 6.1 基础聚合

`Count/Sum/Avg/Min/Max` 直渲(`distinct` 前缀照常);参数与过滤谓词全部走闭合 `Expr`。**形态注记(m5)**:AST 的 `AggregateCall` 恒携带参数(`query.telora:128-135`),**不存在 `count(*)` 形态**——"计数"语义的参数是一个常量绑定(低层构造者绑定值 1),渲染为 `count(?)`;`avg` 返回 DOUBLE(规格在册)。

### 6.2 聚合局部过滤(`AggregateCall.filter`)

规格**无聚合级 `FILTER (WHERE ...)`**(其 FILTER 条目属窗口函数)。统一改写规则(单一规则覆盖全部闭集聚合,m5 修订):

> **`agg(<x>) FILTER (WHERE <c>)` ⟶ `agg(CASE WHEN <c> THEN <x> END)`**(`distinct` 照常前缀:`count(DISTINCT CASE WHEN <c> THEN <x> END)`)

依据:CASE 无 ELSE 分支在不满足谓词时产出 NULL,而闭集 {Count,Sum,Avg,Min,Max} 恰好忽略 NULL 输入——与 `FILTER` 语义严格等价(sql-syntax·CASE WHEN"条件聚合"即此形态);DISTINCT 组合、全 NULL 组、空组逐一验证等价;`Latest` 与 filter 的组合被 AST 结构性禁止(`query.telora:1105`),无需覆盖。`<x>` 为该聚合自身的参数(计数场景即常量绑定),**不新增句柄**(UQL 数组按出现序展开,§4.2)。规格的 `count_if(expr)` 仅覆盖 count 族且无 DISTINCT 组合,不采用(附录 A 归档为优化候选)。

### 6.3 Latest 度量暂存

**B1 修订:与两个既有渲染器同构的"全行暂存 + 分组归约"形态**(窗口无帧;暂存阶段**不裁行**——普通聚合必须看到组内全部合格行,Latest 的单行归约在外层 CASE 中完成):

```sql
-- 阶段一:全行暂存(无 WHERE 裁剪)
SELECT <按共享 latest_stage 命名物化:分组键/聚合参数/HAVING 调用/时间列/平局列…,每个表达式 AS `__latest_input_N`>,
       row_number() OVER (
         PARTITION BY <分组键…>
         ORDER BY <时间列> DESC NULLS LAST, <平局列…> ASC NULLS FIRST
       ) AS `__latest_row`
FROM (<业务 FROM/JOIN/WHERE 源>) AS t

-- 阶段二:外层分组查询(对暂存全行聚合)
SELECT <分组键…>, <普通聚合:参数取自暂存全行> …,
       max(CASE WHEN `__latest_stage`.`__latest_row` = 1 AND `__latest_stage`.`<时间列>` IS NOT NULL
                THEN `__latest_stage`.`<Latest 参数列>` END) AS <latest 别名>
FROM (<阶段一结果>) AS `__latest_stage`
GROUP BY <分组键…>
```

要点(与 LATEST-DESIGN.md 契约逐条对应):

- **全行暂存**:Latest 与普通度量并存是 AST 明确允许的形态(`validate_latest_plan`),普通 `count/sum/avg/min/max` 的参数取自暂存**全行**——**绝不可**在暂存阶段 `WHERE __latest_row = 1` 裁行(LATEST-DESIGN.md:148-159 明文禁止;check-result-limit.mjs:67-68 同时断言 `s_latest` 选值与 `s_mean` 均值,后者必须看到组内两个样本);
- **NULL 时间不是候选**:归约条件含 `<时间列> IS NOT NULL`——全 NULL 时间的组 Latest 返回 **NULL** 而非任意平局行的值(LATEST-DESIGN.md:66-68);该守卫只作用于 CASE 归约,不裁剪普通聚合输入;
- **类型保持**:归约为裸 `max(CASE … THEN <arg> END)`,**不加 CAST**——整型 Latest 保持整型(LATEST-DESIGN.md:46),物理数值类型与 NULL 原样保留;
- **输入物化**:阶段一按共享 `latest_stage`(`query.telora:1155-1217`)把全部所需表达式物化为命名列 `__latest_input_N`(分组键/聚合参数/HAVING 调用/时间/平局)——表达式型聚合参数必须先物化才能被窗口与外层引用,不可 `SELECT t.*`;
- **确定性**:时间 `DESC NULLS LAST`、平局 `ASC NULLS FIRST`(§7.1 同一 NULL 规则);时间+平局仍并列时,`max` 归约取并列行中的最大参数值——与两既有渲染器逐字节同形,由 Tier1a 文本断言锁定三方言等价。

## 7. 语句结构映射(全部 8 种 QueryPlan)

### 7.1 通用行查询(Rows / DistinctRows / GroupCount)

```sql
SELECT [DISTINCT] <投影: Expr/NamedExpr/Aggregate/Computed>
FROM <source> [JOIN …]*            -- INNER JOIN / LEFT JOIN … ON <闭合条件树>
[WHERE <过滤 Expr> [AND EXISTS (…)]*]
[GROUP BY <分组表达式…>]
[HAVING <聚合谓词:HavingOp over Ref/Call>]
[ORDER BY <key> ASC NULLS FIRST | DESC NULLS LAST, …]
[LIMIT ? [OFFSET ?]]
```

- JOIN:`JoinKind` 闭合词汇仅 `Inner`/`Left`(`query.telora:273-276`),规格两形态全后端原生支持;ON 条件为闭合树(`Eq`/`Predicate`/`Exists`/`And`/`Or`);
- **排序采用原生 `NULLS FIRST` / `NULLS LAST`**:AST `Ordering` 契约("升序 NULL 在前、降序 NULL 在后;渲染器必须显式发射",`query.telora:455-461`),而规格 ORDER BY 与窗口 ORDER BY 均定义了 `NULLS FIRST|LAST` —— 原生拼写即为契约的直接表达,不再需要任何模拟写法;
- GROUP BY 支持表达式(规格在册);HAVING 引用投影聚合别名或直接聚合调用,**分组可有可无**(无分组 HAVING 为全表聚合过滤,引擎确认接受,OQ-4 已闭环)。
- **重展开纪律(M6)**:HAVING 的 `HavingMeasure::Ref`、ORDER BY 的 `AggregateRef`、`Computed` 聚合项一律**重展开**为完整聚合/算术表达式——HAVING 引用投影别名不是规格记载形态(规格仅记载 ORDER BY 的别名引用),且两既有渲染器均为重展开(sqlite.telora:513-520);`Computed` 操作数内联展开,`internal_measures` **不**物化为列(sqlite.telora:184-209 同纪律);
- **投影限制(NIT)**:`DistinctRows` 不得投影 Aggregate/Computed(`query.telora:2920-2928`),Rows/GroupCount 无此限制;
- **WHERE 项组合序(文本确定性)**:过滤 → EXISTS → 标量比较 → 排名比较,顺序固定,与两既有渲染器一致。

### 7.2 集合(SetRows / SetCount)

- `SetOpKind::Union → UNION`(去重)、`Intersect → INTERSECT`、`Except → EXCEPT`(三者在 Zenith/StarRocks 原生支持;单计划恒为同种算子,无跨算子优先级问题;多操作数同种算子按规格**左结合**(`(A−B)−C`);操作数形状由 `validate_set_operands` 保证:非空投影、无局部排序/**上限/偏移**/分区,m9);
- `SetCount`:`SELECT count(1) FROM (<集合体>) AS \`__q_count\``;
- `GroupCount` 形态(m9):`SELECT count(1) FROM (<分组查询>) AS \`__q_count\``,内层必须为已分组计划(`query.telora:3179-3186`)。

### 7.3 对齐对比(JoinedGroups / JoinedGroupCount)

```sql
SELECT <输出列: 侧前缀别名>, [排名表达式], count(1)
FROM (<左分组查询>) AS `__q_left`
JOIN (<右分组查询>) AS `__q_right`
  ON `__q_left`.`<k>` <=> `__q_right`.`<k>` [AND …]      -- NULL 安全身份键对齐
WHERE <非空守卫(排名依赖时)>                             -- e.g. `__q_left`.`m` IS NOT NULL AND `__q_right`.`m` IS NOT NULL
ORDER BY <排名表达式> <rank.direction 方向:Asc→NULLS FIRST / Desc→NULLS LAST>, <身份键…> ASC NULLS FIRST
LIMIT ?
```

- 身份键对齐用规格 **`<=>` NULL 安全相等**(operators·比较):语义即"两 NULL 视为相等",是身份对齐的精确表达;
- 排名三算子(m3 补齐 CAST,与两既有渲染器一致):`Subtract → (CAST(a AS DOUBLE) - CAST(b AS DOUBLE))`;`Ratio → (CAST(a AS DOUBLE) / NULLIF(CAST(b AS DOUBLE), 0))` 附 `AND b <> 0` 守卫;`GrowthRate → ((CAST(a AS DOUBLE) - CAST(b AS DOUBLE)) / NULLIF(CAST(b AS DOUBLE), 0))` 附 `AND b > 0` 守卫(规格 `NULLIF` 在册;CAST AS DOUBLE 统一数值域;**NULL-b 行已被 IS NOT NULL 守卫先行排除**后才轮到 `b <> 0`);
- 对齐 JOIN 用内连接 + 非空守卫表达"两侧均有值才参与排名"的语义,`JoinedGroupCount` 为其 `count(1)` 包裹。

### 7.4 组合表(ComposedTable)

**M3 修订:补全映射**——基表 + 列分支逐层左对齐,含 zero 填充、枚举排序、group_count 与无键分支:

```sql
SELECT <基表键列与列…>, <分支列:按需 COALESCE(…, 0)> …
FROM (<基表查询>) AS `__q_base`
LEFT JOIN (<分支1查询>) AS `__q_b1` ON <键等值:见下>
LEFT JOIN (<分支2查询>) AS `__q_b2` ON <键等值:见下>
…
WHERE <分支谓词:作用于派生表列的等值条件>
[ORDER BY <输出别名 | 枚举 CASE(见下)> <方向+NULLS>]
[LIMIT …]
```

- **键连接**:有键分支逐键 `` `__q_base`.`<k>` <=> `__q_bN`.`<k>` ``;**无键分支**(恒值列)渲染 `ON 1 = 1`(规格 JOIN ON 接受任意布尔表达式);
- **zero 填充**:`TableOutput.zero` 为真时该列渲染 `COALESCE(<分支列>, 0)`(规格 `coalesce` 在册)——仅 Count/Computed-Count 输出可置 zero(`table_zero_safe`,`query.telora:3137-3148`),缺失值由 NULL 变 0;
- **枚举排序**:`TableOrder.values` 存在时,ORDER BY 项渲染为 `CASE <输出别名> WHEN <v₁> THEN 0 WHEN <v₂> THEN 1 … ELSE NULL END <方向>`(枚举值按其 AST 表达式渲染,为绑定时即渲染为 `?`,不内联);带 `known_column` 守卫时该项外包 `CASE WHEN <守卫列> = 'Known' THEN <上述 CASE> ELSE NULL END`,仅 Known 行参与该排序维度;
- **group_count 分支**:`TableBranch.group_count` 为真时,分支列渲染为 `SELECT count(1) AS <别名> FROM (<分支查询>) AS \`__q_qualification\`` 的标量子查询形态,参与对齐方式不变;
- **分支谓词**:Table 级谓词以**派生表列等值条件**进入外层 WHERE(而非 HAVING 输出别名引用),与两既有渲染器一致;
- 分支各自可携带独立时间窗,缺失分支值自然为 NULL(LEFT JOIN 语义);分支内使用 `Independent` 作用域的绑定,绑定编号仍按全局分配序。

**分支唯一性来源(M3,显式记录契约边界)**:

| 保证 | 来源 |
|---|---|
| 分支无局部 ORDER BY/LIMIT/**OFFSET**/partition;聚合分支分组键 ≡ 连接键(每键一行) | **AST 校验强制**(`query.telora:3118, 3124-3132`) |
| DISTINCT 分支每键值唯一 | **降级层契约**(非 AST 结构性保证)——若低层调用方构造违反者,LEFT JOIN 将扇出基表行;Tier1b 用例覆盖该守护 |

### 7.5 相关 EXISTS 与比较子查询

各形态的精确 SQL 形状(M5 修订;模板与 AST 注释 `query.telora:321-337/393-405` 一致,仅做 UQL 词法替换):

| AST 结构 | UQL 形态 |
|---|---|
| `Exists`(普通,无分组体) | `[NOT] EXISTS (SELECT 1 FROM <source> [JOIN…] WHERE <相关谓词> [AND <filters>])`(规格在册;引擎侧负责其 JOIN 改写) |
| `Exists`(聚合体 grouped) | `[NOT] EXISTS (SELECT 1 FROM <source> [JOIN…] [WHERE …] GROUP BY <分组列> HAVING <聚合> <op> ?)` |
| `Exists`(alternatives) | `EXISTS (… WHERE (<候选1 相关谓词>) OR (<候选2 相关谓词>) …)`(多候选 OR 合并) |
| `Exists`(peer) | `EXISTS (SELECT 1 FROM <origin> AS o, <peer> AS p WHERE (<a 的 origin 条件> AND <a 的 peer 条件>) OR (<b 的 origin 条件> AND <b 的 peer 条件>) [AND o.<key> <> p.<key> …] [AND <filters>])`——两分支 OR 由 `PeerCorr.exchange` 为真时启用;`distinct_keys` 非空时追加逐键 `o.<k> <> p.<k>` 不等守卫 |
| `Exists`(link) | `EXISTS (SELECT 1 FROM <origin> INNER JOIN <link> ON <link 连接条件> [INNER JOIN <tail> ON …] WHERE <相关谓词> [AND <filters>])`(两跳链 + 可选尾表) |
| `ScalarAggregateComparison` | `<outer列> <op> (SELECT <聚合> FROM <source> [JOIN…] [WHERE …])`(规格·标量子查询) |
| `RankedKeyComparison` | `<outer列> <op> (SELECT <键> FROM <source> [JOIN…] [WHERE …] GROUP BY <键> ORDER BY <聚合> <方向+NULLS> LIMIT 1)` |

- 排名比较的 ORDER BY 显式 NULLS(升 NULLS FIRST / 降 NULLS LAST,§7.1 同规则,m4);
- **并列不确定性(m4,显式记录的既有共享歧义)**:排名聚合并列时 LIMIT 1 的选键在各引擎不保证一致(SQLite/PG 亦然)——Tier1a 以三方言文本同形断言约束实现层不引入**新**分歧;降级层构造该形态时应以完整键序决胜;
- "标量子查询内 GROUP BY"列入 Tier2 验证清单(规格支持子查询含 GROUP BY/ORDER BY/LIMIT,但需引擎实测确认改写路径)。

### 7.6 分区 Top-N(PartitionedTopN)

**M4 补全**:`Plan.partition` 存在时的专属形态(窗口无帧,与 §6.3 同一窗口纪律):

```sql
-- 内层:窗口编号 + 隐藏键物化
SELECT <投影…>,
       <未投影的分组键物化为 `__q_pk_<i>`>,
       <未投影的排序列物化为 `__q_ok_<j>`>,
       row_number() OVER (PARTITION BY <分区表达式…> ORDER BY <排序列> <方向+NULLS> …) AS `__q_rn`
FROM <source> [JOIN…] [WHERE …] [GROUP BY …]      -- 作用于聚合分组结果时,窗口在分组之后

-- 外层:裁剪 + 确定性排序
SELECT <投影…>
FROM (<内层>) AS `__q_partitioned`
WHERE `__q_rn` <= <take 绑定>
ORDER BY <隐藏键…>, <排序列…> <方向+NULLS>       -- 隐藏列保证确定的输出序
```

- 隐藏列 `__q_pk_<i>`(分组键)/`__q_ok_<j>`(排序键)沿用共享命名(§4.3),让未投影键参与外层排序,裁剪后行序确定;
- `take` 为正整数绑定;外层 ORDER BY 含完整隐藏键序,同一输入行序唯一;
- 与输出上限组合时按 §7.8 嵌套包裹(分区语义在内层完成后,cap 只裁交付行)。

### 7.7 派生源(DerivedSource,union_source 实体)

```sql
FROM (SELECT … UNION ALL SELECT … UNION ALL …) AS `alias`
```

UNION ALL 为规格原生(全后端);此为实体多源声明的物化形态,与集合计划的去重 UNION 语义不同,不可混用。

### 7.8 排序、分页与输出上限(关键新设计)

**业务分页**:`LIMIT ? [OFFSET ?]`(规格在册;占位符可用于 LIMIT/OFFSET 为规格明文)。

**输出上限(`result_limit`)——嵌套包裹设计**(M2 修订:钉住**有序前缀**语义)。当 `validated_output_limit` 返回上限时,整体外包裹:

```sql
SELECT * FROM (
  <完整业务查询,含其自身的 ORDER BY / LIMIT / OFFSET / DISTINCT / 窗口>
) AS `__q_capped`
LIMIT ?
```

- **验收属性是"有序前缀",不是行数**(M2):交付行必须是业务查询有序结果的**前缀序列**——check-result-limit.mjs:62 以 `deepEqual(rows, full.slice(0, limit))` 定义该语义,行数 = min(业务上限, cap) 只是推论;Tier2 增加**整行序列**比对用例,并确认引擎"外层 LIMIT 保留派生表顺序"(并入 OQ-3;StarRocks 为 MPP,不能默认顺序穿透);
- **只使用规格记载的 `LIMIT ?` 形态**,结构性回避"LIMIT 位置出现 CASE 表达式"(历史实现的开放问题就此消除);
- **OFFSET 组合**(M2):业务含 LIMIT+OFFSET 时嵌套(内层 `LIMIT ? OFFSET ?`、外层 `LIMIT ?` → 前缀 [m+1, m+min(b,cap)]);业务**仅 OFFSET 无 LIMIT** 且有 cap 时,不嵌套,直接单层 `… LIMIT ? OFFSET ?`(前缀 [m+1, m+cap]);**无 LIMIT 亦无 cap 的纯 OFFSET** 不可表达,§5.4 fail-closed;
- **重复输出列名**(M2):`SelectItem::Expr` 可产出重名列(合法 AST),`SELECT *` 对此合法——Tier1b/Tier2 增加重名列穿透用例;
- 上限绑定必须经 `validated_output_limit`(正整数绑定句柄),渲染前校验,拒绝伪造;
- 上限缺席时不包裹,业务 `LIMIT` 原位渲染。

绑定编号一律按 `BuildCtx` 分配序;嵌套导致 SQL 文本出现顺序与分配顺序不同——编号占位符在共同契约下明文容忍该差异,而 UQL 侧的体现是:**出现序展开使数组顺序遵循文本序而非分配序**(§4.2),该差异由 Tier1a 的展开断言消化。

## 8. 语义不变量(跨方言等价的验收基准)

1. **绑定等价(展开形式,OQ-9 后重定义)**:三方言共享同一分配层与同一按句柄数组——SQLite/PG 产物 `bindings` 与句柄数组逐位相等(编号占位符);UQL 产物 `bindings` = 句柄数组按 SQL **出现序**的展开(同一句柄多次出现则值重复多次,未出现句柄不入数组,§4.2)。Tier1a 断言 `expand_by_occurrence(句柄数组, 出现序) == UQL bindings`——等价从"字面逐位"升级为"可计算的展开相等";标识符引用(无/双引号/反引号)是仅有的词面差异之一;
2. **一义性**:任何计划形状的行集语义、身份键解释、NULL 处理、排序结果在三方言一致;不可表达处 fail-closed 而非近似;
3. **UTC 假设**:全部时间函数无时区参数,按 UTC 解释;不使用 `from_tz` / `AT TIME ZONE`(规格在册但引入会话依赖,违反契约)。**存储约定(建模规则 R-2)**:模型时间列必须以 UTC-naive `TIMESTAMP`/`DATETIME` 或 epoch 毫秒存储;Zenith 侧禁用带时区列型(`timestamptz`)。排除 Druid 后引擎无会话时区自动偏移,该约定即完整时区策略;
4. **确定性**:排序键含身份平局决胜列;NULL 定位显式(`NULLS FIRST/LAST`);同一输入永远产出同一 SQL 文本与绑定(渲染为纯函数);
5. **失败模式等价**:出界时间值 → NULL(非报错);不合法标识符/占位符 → 构建期 `fail!`;
6. **有序前缀交付(M2)**:输出上限裁剪交付的是业务查询有序结果的**前缀序列**——验收比对整行序列而非行数(check-result-limit.mjs:62 的 `deepEqual(rows, full.slice(0, limit))` 即该语义);
7. **Div 前置条件(M1)**:`Div` 仅在调用方保证非整数域时合法(§5.1);当前无降级产生方,未来产生方按 §9.2 准入时须三方言同步保证双精度域。

## 9. Profile 与能力门禁

### 9.1 渲染器对 PlanProfile 的义务

`PlanProfile{allowed_operators, allowed_join_kinds, allowed_aggregates, allowed_scalars, allow_distinct}` 是模型级门禁,由降级层与 `qb::validate` 执行;渲染器在入口重校验结构,不重新解释能力。UQL 渲染器对闭合词汇的覆盖 = 全集减去 §5.4 的 fail-closed 清单(m9 更正:`BinaryText` 与"无限偏移"两项;无分组 HAVING 已于 v3 闭环移出)。

### 9.2 新算子准入流程(UQL 加入后)

任何新 `ScalarFunction`/`AggregateFunction`/结构算子的准入材料必须包含:三方言渲染方案 + Tier1 双层证据 + Tier2 引擎验证记录。附录 A 的候选一律走此流程。

### 9.3 建模规则(发布期强制)

- **R-1**:`allowed_scalars` 含 `BinaryText` 的模型,发布 `dialects` 不得含 `uql`(已确认 IC 域模型无 BinaryText 声明,本规则为零成本保险);
- **R-2**:时间列 UTC-naive / epoch 毫秒存储(§8 不变量第 3 项"UTC 假设");
- 两条规则由 `model-release` 构建期校验,违反即拒绝发布。

## 10. 验收设计(双层)

### 10.1 第一层 a:精确文本断言(Telora 模块测试)

每个结构家族锁定断言:标识符引用形态、`?` 位置占位、`NULLS FIRST/LAST`、`<=>` 对齐、`UNION`/`UNION ALL` 区分、嵌套 cap 形态、Latest 暂存、epoch 守卫 CASE、CASE 包装聚合过滤、`regexp_count` 形态、`char_length`、`timestamp_format` 格式串。**强制包含绑定展开断言**(§8.1:`expand_by_occurrence(句柄数组, 出现序) == UQL bindings`;同一用例下 SQLite/PG 数组仍与句柄数组逐位相等)。

### 10.2 第一层 b:参考语义 shim 执行(全量 QueryPlan 矩阵)

在 SQLite 上以**确定性参考实现**执行渲染产物,shim 覆盖本设计用到的全部 UQL 专有函数(而非仅三两个):

| shim 函数 | 参考实现要点 |
|---|---|
| `mod(a, b)` | 截断式取模(fmod 语义,符号随被除数)——双取模 `mod(mod(v,W)+W,W)` 对截断式/向下取整式两种约定均不变(m2) |
| `concat(a, b, …)` | **NULL 传播**(任一参数 NULL → NULL;引擎已确认,显式覆盖 SQLite 原生 concat 的跳过 NULL 行为——否则 NULL 输入的 UtcDaysAgo 参考结果错误,M7) |
| `char_length(s)` | 字符数(len) |
| `regexp_count(s, p)` | `len(re.findall(p, s))`,NULL 传播 |
| `date_trunc(unit, v)` | 按输入逻辑类型返回(datetime/date) |
| `timestampadd(unit, n, ts)` | 日历加法 |
| `timestampdiff(unit, a, b)` | 整值差 |
| `timestamp_format(ts, fmt)` | `%Y-%m-%d` / `%H:%i:%s` 记号解析(T/Z 不进格式串,由 concat 字面量提供) |
| `time_floor(v, 'PT1H')` | 小时向下对齐,返回 DATETIME(保持时间部分;OQ-7 新依赖的参考实现) |

执行矩阵覆盖全部 8 种 `QueryPlan` × 关键特性(过滤/聚合过滤/时间窗/桶/Latest/Top-N/对齐/集合/cap/NULL 传播),每格带期望行集断言。执行方式:SQLite **qmark 风格**直接以位置数组执行(`db.execute(sql, bindings)`),无需命名参数转换层(OQ-9 后占位符与引擎同形)。**诚实标注**:本层验证的是"渲染产物在参考语义下的正确性",不是 OneQuery 引擎行为;参考实现与规格的偏差即 Tier2 的必测项。

### 10.3 第二层:OneQuery 引擎集成门(消费方/引擎方协作)

- 通过 OneQuery 查询接口(REST)提交 `{sql, bindings}`,在 **Zenith 与 StarRocks 双后端**实测,并与 SQLite 参考结果比对行集等价;
- 专项清单:UQL 专有函数逐项(`time_floor('PT1H')`(OQ-7 规格已确认,实测复核)、`date_trunc` 的 DATE 返回形态(DatetimeUtc 日/月 + DateUtc)、`concat + timestamp_format` 组合(UtcDaysAgo,OQ-1 渲染复核;**含 NULL 输入用例**,OQ-8)、`timestampadd/timestampdiff` 月桶路径(含负值)、`regexp_count`、`mod` 负值、`lower` 非 ASCII 折叠域抽查、`substr(s, 0, n)` 防御性用例(m11)、`CAST(规范 UTC 文本 AS TIMESTAMP)` 解析(m7)、时间函数单位拼写双形态核验(m8))、原生 `NULLS FIRST/LAST`、`<=>` 对齐、去重 `UNION`、**嵌套 cap 的整行序列比对 + 派生表顺序保留确认**(M2,OQ-3 扩展)、**重复输出列名穿透**(M2)、标量子查询内 GROUP BY、**无分组 HAVING**(OQ-4)、多源(StarRocks 路由)形态;
- 范围注记:多源查询包含 Druid 表时不属本层覆盖(§2.1,m10);
- §12 开放问题全部在此层闭环后方可进入发布准入;
- 工装形态:消费方验收脚本(`model-release` 的 `acceptanceScript` 挂点)+ 引擎方联调记录,归档为发布材料。

### 10.4 验收矩阵(摘要)

| AST 家族 | Tier1a 文本 | Tier1b shim 执行 | Tier2 引擎 |
|---|---|---|---|
| 表达式全集(§5.1) | ✅ | ✅(含 NULL/出界) | ✅ 逐函数 |
| 聚合 + 局部过滤 + Latest | ✅ | ✅ | ✅ |
| Rows/Distinct/GroupCount | ✅ | ✅ | ✅ |
| Set(Union/Intersect/Except)+ SetCount | ✅ | ✅ | ✅(含去重语义) |
| JoinedGroups(三算子) | ✅ | ✅ | ✅ |
| ComposedTable(左对齐) | ✅ | ✅ | ✅ |
| EXISTS(普通/peer/link)+ 比较子查询 | ✅ | ✅ | ✅(含子查询内 GROUP BY) |
| 分区 Top-N | ✅ | ✅ | ✅ |
| 排序/分页/嵌套 cap | ✅ | ✅ | ✅(有序前缀整行序列比对,OQ-3) |
| 绑定等价(展开断言,§8.1) | ✅ 每用例 | — | — |

### 10.5 发布验收挂接

- `model-release`:`dialects` 含 `uql` 时,`acceptance.intent` 探针走 UQL 渲染;smoke 的 transform 重置须保留方言;manifest 记录规格快照版本(§15);
- 发布准入前置条件:OQ-1/OQ-2/OQ-4/OQ-5/OQ-7/OQ-8 已按引擎与规格确认(`concat` 组合含 NULL 传播 / 桶矩阵重排 / 原生无分组 HAVING / time_floor / `lower` 直渲),且上述调整项的 Tier2 实测复核全部通过。语义类开放问题至此全部关闭;仅余 OQ-3(Tier2 例行)与 OQ-6(规格版本号,管理项)。

## 11. 发布与消费集成

### 11.1 方言选择与 UQL-only 发布

`release.config.json` 的 `dialects` 为 `sqlite/postgres/uql` 非空子集;IC 类消费方(UQL-only 发布契约)仅选 `uql`。R-1/R-2 由构建期校验。

### 11.2 REST 交付契约

宿主把 `Query.queries[i] = {sql, bindings}` 提交 OneQuery 查询接口:

- SQL 内占位符为**纯位置 `?`**:第 i 个 `?`(按文本出现序,1 基)对应 `bindings[i-1]`;同一句柄多次出现时,该值在数组中重复多次(OQ-9/§4.2 展开模型);
- `bindings` 为 JSON 原生标量数组(字符串/数值/布尔/NULL)。**注意它与提交 SQLite/PG 的数组不是同一形态**(后者按句柄分配序、每句柄一项)——各后端提交其渲染器配套的 `{sql, bindings}`,宿主**不得跨后端混用数组**;
- 参数数量必须恰等于 `?` 个数(位置语义,引擎不接受多余参数;展开模型天然保证);
- 输出上限(若存在)是出现序数组中的既定位,消费方无需特殊处理;
- 语句类型恒为单条 `SELECT`(规格仅接受 SELECT/EXPLAIN)。

### 11.3 替换上线与兼容

- 接口不变:`ToQuery` 同签名原位替换,降级层/工厂/发布工具零改动;
- **SQL 文本变化不构成破坏性变更**(文本从来不是稳定契约;绑定等价才是),但下游若有文本快照依赖需同步再生成;
- 语义变化点:无(NULLS 原生化、`<=>`、嵌套 cap 均为等价改写);文本层面与历史实现的差异清单在实施 PR 中随附,供消费方回归对照;
- 消费方升级路径:pin 新 commit → 领域验收(含 Tier2 引擎门)→ 提交 gitlink。

## 12. 开放问题追踪(状态与闭环方式)

| 编号 | 问题 | 本设计立场 | 闭环方式 |
|---|---|---|---|
| **OQ-1** ✅ 已确认 | `timestamp_format` 格式串:引擎不支持 T/Z 字面量;**`%` 记号可用**(已确认) | `concat` 组合方案定稿(§5.1) | 已闭环;Tier2 对组合形态例行复核 |
| **OQ-2** ✅ 已确认 | `date_trunc` 返回类型:**返回 DATE**(规格标注正确) | §5.2 重排:DateUtc 直用;DatetimeUtc 日/月外层 `CAST(… AS TIMESTAMP)`;DatetimeUtc **小时桶弃用 date_trunc**——原"外层 CAST"预案对小时桶不足(DATE 返回已在源头毁掉小时信息),改用 `time_floor(v, 'PT1H')` | 已闭环;衍生 OQ-7 |
| **OQ-7** ✅ 规格确认 | `time_floor(v, 'PT1H')`:functions.md 与 functions.json 双份标注**返回 DATETIME**("时间向下对齐"),与 date_trunc 返回 DATE 对照明确 | DatetimeUtc 小时桶定稿采用;备选(`timestamp_floor` / timestampadd 组合式)保留文档记录 | Tier2 例行实测(返回类型与小数秒边界) |
| **OQ-3** | LIMIT 形态:嵌套 `SELECT * FROM (…) LIMIT ?` 与单层 `LIMIT ? OFFSET ?` 为标准形态 | 本设计只使用该两种形态(M2 修订) | Tier2 确认:①外层 LIMIT **保留派生表顺序**(有序前缀语义所系);②OFFSET 组合形态 |
| **OQ-4** ✅ 已确认 | 无分组 HAVING:引擎**接受** | §5.4 移除该项 fail-closed,§7.1 原生渲染;SQLite/PG 本就支持,三方言等价无变化 | Tier2 例行复核 |
| **OQ-5** ✅ 已确认 | `lower()` 折叠域:引擎确认符合 AST 契约(仅 A–Z 折叠) | 弃用 replace 链,直接渲染 `lower(x)`(§5.1);SQLite 原生 `lower` 亦为 ASCII-only,Tier1b 天然对齐 | Tier2 保留一次非 ASCII 折叠抽查;建议规格补充明文折叠域 |
| **OQ-8** ✅ 已确认 | `concat` NULL 传播:引擎确认**任一参数 NULL → 返回 NULL**(M7 评审引入,即时闭环) | UtcDaysAgo 的 concat 组合对 NULL 输入安全(NULL 进 → NULL 出);Tier1b shim 以 NULL 传播语义显式覆盖 SQLite 原生 concat 的跳过行为 | Tier2 例行复核 + NULL 输入 UtcDaysAgo 专项用例 |
| **OQ-9** ✅ 已确认 | 查询接口动态参数风格:引擎确认**仅接受纯位置 `?` + 位置数组**(规格菜单中的 `$1/$2`/`:name`/`@var` 为解析器能力,非提交路径可用) | §4.2 重写为 `?` + **出现序展开**模型;§8.1 绑定等价重定义为展开相等;§10.1/§11.2 同步(REST 契约改为位置数组,跨后端数组不可混用) | Tier2 例行复核(接口参数计数严格性);建议规格标注主风格 |
| **OQ-6** | 规格版本跟踪:当前以目录快照哈希钉住 | 见 §15 | 建议规格方发布版本号,消费方按版本 pin |

## 13. 附录 A:第二阶段扩展候选

> 每项须走"三方言等价 + Tier1 + Tier2"准入;**均不在本设计承诺范围内**。

| 候选 | UQL 依据 | 三方言等价义务(SQLite/PG) | 价值与备注 |
|---|---|---|---|
| `count_if(expr)` 聚合 | functions·聚合 | SQLite/PG: CASE 包装已有等价形态 | 仅 count 族的渲染简化,优先级低 |
| LAG/LEAD 窗口 | functions·窗口 | SQLite ≥3.25 支持;PG 原生 | **高价值**:GraphPair 的前后窗口对比可单遍窗口化,替代双分组子查询对齐;改变降级层,属大项 |
| 窗口帧(移动统计) | sql-syntax·窗口函数 | 双方原生(帧语义需逐后端验证) | 新 `PartitionedTopN` 之外的窗口算子;准入成本高 |
| `ILIKE` 过滤 | operators·谓词 | SQLite: 需 `lower` 组合(OQ-5 已确认仅 A–Z 折叠,组合可行);PG: ILIKE 原生 | 新 `FilterOp`;SQLite 侧等价性仍需逐用例验证 |
| `time_floor` / `ago` / 更丰富时间函数 | functions·日期时间 | 需各自等价方案(多为 strftime/extract 组合) | 建模表达力扩展 |
| `TRY_CAST` | data-types | SQLite/PG 无直接等价(PG 需 CASE + 校验函数) | 失败模式等价难达成,低优先 |
| CTE(WITH)渲染策略 | sql-syntax·CTE(引擎最终内联) | 双方原生 | 引擎会内联 CTE,嵌套派生表现状更稳;仅可读性收益 |
| `approx_count_distinct` 等近似聚合 | functions·聚合 | **无等价**(SQLite/PG 缺近似聚合) | 需方言级 Profile(架构改造:当前 Profile 是模型级)——列为架构讨论项,非渲染器范畴 |
| 字符串/数值函数族(`abs/round/concat/‖`…) | functions/`||` | 双方基本原生 | 新 `ScalarFunction` 词汇扩充,常规准入 |
| 四类意图产生 `Intersect/Except` 的语法入口 | 规格·集合操作 | 双方原生 | AST 已有 `SetOpKind`,仅缺意图层入口;语义讨论项 |

## 14. 附录 B:与 SQLite / PostgreSQL 渲染器差异速查

| 关注点 | SQLite | PostgreSQL | UQL(本设计) |
|---|---|---|---|
| 标识符 | 裸(白名单后) | `"a"."b"` | `` `a`.`b` ``(双引号是字符串!) |
| 占位符 | `?n`(编号) | `$n`(编号) | `?`(纯位置,出现序展开) |
| NULL 排序 | 方言默认(恰合契约) | 显式 `NULLS FIRST/LAST` | 显式 `NULLS FIRST/LAST` |
| 身份键 NULL 安全对齐 | `(l = r OR (l IS NULL AND r IS NULL))` | 同左 | `<=>` |
| 聚合局部过滤 | `FILTER (WHERE …)` | `FILTER (WHERE …)` | `CASE WHEN` 包装 |
| 文本折叠(A-Z) | `lower` | `translate`/等价 | `lower`(引擎确认折叠域符合契约) |
| 字符长度 | `length`(字符) | `char_length` | `char_length` |
| 时间桶 | `strftime`/`date()` | `date_trunc`/`AT TIME ZONE`/`extract` | `time_floor`(小时,返回 DATETIME)+ `date_trunc`+`CAST`(日/月,引擎返回 DATE)+ `timestampadd/timestampdiff`(epoch)+ 守卫 CASE |
| 输出上限 | `LIMIT` CASE 守卫 | 同左 | 嵌套派生表 + 外层 `LIMIT ?` |
| 除法 | `/`(整型截断) | `/`(整型截断) | `/`(规格明文真除;**前置条件**:非整数域,§5.1 M1) |
| 二进制排序 | `COLLATE BINARY` | `COLLATE "C"` | fail-closed |
| `instr` 定位 | `instr` | `strpos` | `instr` |
| 正则 | `REGEXP`(注册函数) | `~` | `(regexp_count(s, p) > 0)` |

## 15. 参考资料(规格快照)

OneQuery SQL 语言参考,目录 `/Users/Admin/Desktop/uql`(版本字段 `1.0`,见 `functions.json`)。快照 SHA-256:

```
83db1cecdcb729164af7a566db2226a02584e8f5241f78f835d9fd4cb1a33f48  data-types.md
ad809210a9e1fc5922d7b05cc9ff525fa4997db34eaa48a8a9894cf17f128898  examples.md
961c6c8bafe467e44084b45f396036b2aa281eb59601bed85440436ff63b3512  functions.md
76e75bea0095142111b2c3552c701b3cbf5ff41df6d4a4b5675fa754568be53b  operators.md
40842822261428fa792f83bfb41ee04686631e859fe42f3a197d6d508edaba77  README.md
4a05279efb613cf061090bcffaf1c95fd6fbed8c6cb6c42a1d3fb1ff24018e97  sql-syntax.md
ffbfad94a3347ef571c53013f955e563339d18530f691061fa7f50f1a72405e3  data-types.json
080d1550e176e62fdf9b4d4886dfd02409a73fdaa6ed96e4114985cfd8939a6f  functions.json
d06e0e23ddb9344b8c1715e52c175db2cced81a1b4ea2fcd9299f50fc33cca65  operators.json
03f76cb245dcb6e8427579d8957c637d2b6df35859a1827f592890cad7700467  sql-syntax.json
```

仓内关联:`ontology/src/query.telora`(闭合 QueryAst 权威定义)、`ontology/docs/DIALECT-CONTRACT.md`、`ontology/docs/QUERY.md`、`docs/MODEL-RELEASE.md`。

## 16. 变更记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1 | 2026-11-10 | 初稿:四项范围决策(无 Druid / 两阶段 / 写入仓库 / 双层验收),基于 OneQuery 规格 v1.0 快照与闭合 QueryAst 从零推导 |
| v2 | 2026-11-10 | 引擎侧确认 OQ-1(格式串不支持 T/Z)与 OQ-2(date_trunc 返回 DATE):表达式映射改 concat 组合、桶矩阵重排;新增 OQ-7(time_floor) |
| v3 | 2026-11-10 | 确认 `%` 记号可用(OQ-1 全闭环)、无分组 HAVING 引擎可接受(OQ-4 闭环,自 fail-closed 清单移除)、`time_floor` 规格返回 DATETIME(OQ-7 闭环) |
| v4 | 2026-11-10 | 确认 `lower()` 折叠域符合契约(OQ-5 闭环):弃用 replace 链,直渲 `lower(x)`;R-1 确认对 IC 域模型无影响(无 BinaryText 声明) |
| v5 | 2026-11-10 | Oracle SQL 专家评审(1 BLOCKER / 7 MAJOR / 12 MINOR / 3 NIT)全量修订——B1:§6.3 Latest 重写为"全行暂存 + max(CASE) 归约";M3:§7.4 组合表映射补全;M4:新增 §7.6 分区 Top-N;M5:§7.5 EXISTS 形态细化;M2:§7.8 嵌套 cap 钉住有序前缀与 OFFSET 组合;M6:§7.1 HAVING/Computed 重展开纪律;M1:Div 前置条件钉死(按推荐方案);M7:concat NULL 传播确认(OQ-8 闭环);minor/nit 全部修复 |
| v6 | 2026-11-10 | 结构复审修订:§5.x 重编号(原 5.2.1 桶矩阵→§5.2,原 5.2 决策汇总→§5.3,原 5.3 fail-closed→§5.4)并同步全部交叉引用;Druid 排除的四项专项设计内联(修复对 §12 的悬空引用);§12 标题改为"开放问题追踪"(8 项中 6 项已闭环,余名不副实);变更历史自文档头移至本节;§10.5 前置条件补 OQ-8;`If` 行参数序钉死;R-2 的 §8 引用改为明文指向 |
| v7 | 2026-11-10 | OQ-9 确认(查询接口**仅接受纯位置 `?`**,非规格菜单中的 `$1/$2`):§4.2 重写为**出现序展开**模型(同一句柄多次出现则值重复;未出现句柄不入数组);§8.1 绑定等价从"字面逐位"重定义为"可计算的展开相等";§11.2 REST 契约改为位置数组并明示跨后端数组不可混用;§10.1 断言改写、§10.2 shim 改 qmark 直连;全文 `$n` 拼写替换(§5.1/§6.1/§7.1/§7.3/§7.4/§7.5/§7.8/§14) |
