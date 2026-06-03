# Apache Doris AI 算子与 Python 自定义算子验证设计方案

## 1. 结论先行

### 1.1 版本支持确认

基于 2026-06-03 对 Apache Doris 官方文档的核对：

- **AI Function / AI 算子**：Apache Doris 4.0 明确引入 AI 能力，包括 Vector Search、AI Functions、Hybrid Search 等。官方 4.0 发布说明将 AI Functions 列为 4.0 核心能力之一。
- **Python UDF / UDAF / UDWF / UDTF**：官方稳定文档在 **4.x** 版本下提供完整说明，覆盖 Python 标量 UDF、向量化 UDF、UDAF、窗口 UDAF、UDTF、环境配置、依赖管理、类型映射、性能建议和限制。
- **3.x / 2.1**：官方 3.x / 2.1 UDF 文档主要覆盖 Java UDF 与 Remote UDF，未看到与 4.x 同等的 Python UDF/UDAF/UDTF 官方稳定文档。因此本次验证不建议基于 3.x 做 Python UDF 能力结论。

**推荐验证版本**：

- 首选：Apache Doris **4.1.x 最新补丁版本**，用于验证最新 4.x 能力与稳定性。
- 可选：Apache Doris **4.0.x 最新补丁版本**，用于验证 AI Function 4.0 引入后的基础能力。
- 不建议：Apache Doris 3.x 或更低版本，用于本次“AI 算子 + Python 自定义算子”联合验证。

**上线选型建议**：

如果验证目标包含 AI Function 和 Python UDF 两类能力，统一选择 Doris 4.x。若企业内部版本仍在 3.x，应将本次验证定义为“升级可行性 POC”，而不是在 3.x 上验证生产可用性。

### 1.2 官方依据

- AI Function 通过 Doris `RESOURCE` 机制对接外部 AI Provider，并支持 `AI_CLASSIFY`、`AI_EXTRACT`、`AI_FILTER`、`AI_FIXGRAMMAR`、`AI_GENERATE`、`AI_MASK`、`AI_SENTIMENT`、`AI_SIMILARITY`、`AI_SUMMARIZE`、`AI_TRANSLATE`、`AI_AGG`。
- Python UDF 文档说明其支持标量 UDF、UDAF、UDWF、UDTF；BE 节点需要开启 `enable_python_udf_support`，并配置 Conda 或 venv 环境。
- Python UDF 强制依赖 `pandas` 与 `pyarrow`，且所有 BE 节点依赖版本必须一致。
- Python UDF 创建时必须显式指定完整 `runtime_version`，例如 `"3.10.12"`，不能只写 `"3.10"`。
- Python UDF 性能低于 Doris C++ 内置函数，性能敏感场景优先内置函数；复杂逻辑和中等数据量场景才适合 Python UDF。

参考链接：

- Apache Doris 4.0 发布说明：https://doris.apache.org/releases/v4.0/release-4.0.0/
- Apache Doris AI Functions 4.x 文档：https://doris.apache.org/docs/4.x/sql-manual/sql-functions/ai-functions/overview/
- Apache Doris Python UDF/UDAF/UDWF/UDTF 4.x 文档：https://doris.apache.org/docs/4.x/query-data/udf/python-user-defined-function/

## 2. 验证目标

### 2.1 总体目标

从架构可用性角度验证 Apache Doris 4.x 是否能够承载以下能力：

1. 在 SQL 内直接调用 AI 算子完成文本分析、抽取、摘要、翻译、分类、相似度和跨行聚合。
2. 通过 Python 自定义算子扩展 SQL 计算能力，覆盖标量转换、向量化批处理、聚合、窗口聚合和一行转多行。
3. 在分布式 Doris 集群下验证可部署性、性能、稳定性、可观测性、安全性、成本和运维复杂度。
4. 给出生产落地边界：哪些场景适合 Doris AI Function，哪些适合 Python UDF，哪些应放到外部服务或离线任务。

### 2.2 不做的范围

- 不验证模型本身的训练效果。
- 不将 Python UDF 作为大规模机器学习推理服务替代品。
- 不在 Python UDF 内访问外部网络、数据库或文件系统作为常规方案。
- 不将 AI Function 输出作为强一致决策依据，只做分析增强或辅助判断。

## 3. 验证架构

### 3.1 逻辑架构

```text
业务 SQL / 测试脚本
        |
        v
Doris FE
        |
        +--------------------+
        |                    |
        v                    v
Doris BE 集群           AI RESOURCE
        |                    |
        |                    v
        |              外部 LLM Provider
        |
        v
Python UDF Server / Python Runtime
        |
        v
Conda / venv + pandas + pyarrow + 业务依赖
```

### 3.2 验证分层

| 层级 | 目标 | 内容 |
|---|---|---|
| L0 环境验证 | 确认能力开关和依赖可用 | Doris 版本、BE 配置、Python 版本、依赖一致性、AI Resource |
| L1 功能验证 | 确认算子可以正常使用 | AI 函数、UDF、UDAF、UDWF、UDTF 单用例 |
| L2 业务能力验证 | 确认是否满足业务分析场景 | 文本分类、情感分析、抽取、脱敏、摘要、标签展开、聚合指标 |
| L3 工程验证 | 确认可生产化 | 性能、并发、失败恢复、日志、安全、成本、运维 |
| L4 架构边界验证 | 明确不适用场景 | 大规模推理、强一致判断、UDF 外部 IO、超大批量 Python 计算 |

## 4. 环境设计

### 4.1 集群拓扑

建议准备两套环境。

| 环境 | 拓扑 | 用途 |
|---|---|---|
| POC 环境 | 1 FE + 1 BE | 快速验证功能、脚本和数据 |
| 工程验证环境 | 1 FE + 3 BE | 验证分布式执行、UDAF merge、依赖一致性、并发和稳定性 |

### 4.2 推荐软件版本

| 组件 | 推荐版本 | 说明 |
|---|---|---|
| Apache Doris | 4.1.x 最新补丁版 | 推荐用于最终验证 |
| Apache Doris | 4.0.x 最新补丁版 | 可作为 AI Function 基线 |
| Python | 3.10.12 或 3.12.x | `runtime_version` 必须写完整版本 |
| pandas | 固定版本，例如 2.2.x 或 2.3.x | 所有 BE 一致 |
| pyarrow | 固定版本，例如 15.x 或 21.x | 所有 BE 一致 |
| numpy | 固定版本 | 用于向量化和数学验证 |
| mysql client | 8.x 或兼容客户端 | 执行 SQL |
| jq | 最新稳定版 | 解析脚本输出，可选 |

### 4.3 BE 配置

每个 BE 节点的 `be.conf` 建议配置：

```properties
enable_python_udf_support = true
python_env_mode = conda
python_conda_root_path = /opt/miniconda3
max_python_process_num = 0
```

如使用 venv：

```properties
enable_python_udf_support = true
python_env_mode = venv
python_venv_root_path = /doris/python_envs
python_venv_interpreter_paths = /opt/python3.10/bin/python3.10:/opt/python3.12/bin/python3.12
max_python_process_num = 0
```

配置变更后重启所有 BE。

### 4.4 Python 环境初始化脚本

Conda 模式：

```bash
#!/usr/bin/env bash
set -euo pipefail

CONDA_HOME=/opt/miniconda3
ENV_NAME=doris-py310
PYTHON_VERSION=3.10.12

"${CONDA_HOME}/bin/conda" create -y -n "${ENV_NAME}" "python=${PYTHON_VERSION}" \
  pandas=2.2.3 \
  pyarrow=15.0.2 \
  numpy=1.26.4 \
  scikit-learn=1.5.2

"${CONDA_HOME}/envs/${ENV_NAME}/bin/python" --version
"${CONDA_HOME}/envs/${ENV_NAME}/bin/python" -c "import pandas, pyarrow, numpy; print(pandas.__version__, pyarrow.__version__, numpy.__version__)"
```

所有 BE 节点必须执行同一脚本。执行完成后检查：

```sql
SHOW PYTHON VERSIONS;
SHOW PYTHON PACKAGES IN '3.10.12';
```

### 4.5 AI Resource 配置

外部模型资源：

```sql
CREATE RESOURCE "ai_resource_llm"
PROPERTIES (
    'type' = 'ai',
    'ai.provider_type' = 'openai',
    'ai.endpoint' = 'https://your-compatible-endpoint/v1/chat/completions',
    'ai.model_name' = 'your-model-name',
    'ai.api_key' = 'replace-with-secret',
    'ai.temperature' = '0.1',
    'ai.max_tokens' = '1024',
    'ai.max_retries' = '3',
    'ai.retry_delay_second' = '1'
);

SET default_ai_resource = 'ai_resource_llm';
```

本地模型资源：

```sql
CREATE RESOURCE "ai_resource_local"
PROPERTIES (
    'type' = 'ai',
    'ai.provider_type' = 'local',
    'ai.endpoint' = 'http://your-local-llm-endpoint/v1/chat/completions',
    'ai.model_name' = 'local-model-name',
    'ai.temperature' = '0.1',
    'ai.max_tokens' = '1024',
    'ai.max_retries' = '1',
    'ai.retry_delay_second' = '0'
);
```

## 5. 数据构造

### 5.1 数据库和表结构

```sql
CREATE DATABASE IF NOT EXISTS doris_ai_udf_poc;
USE doris_ai_udf_poc;

DROP TABLE IF EXISTS customer_feedback;
CREATE TABLE customer_feedback (
    id BIGINT,
    user_id BIGINT,
    order_id VARCHAR(64),
    product_id VARCHAR(64),
    category VARCHAR(64),
    rating INT,
    amount DECIMAL(18, 2),
    tags VARCHAR(500),
    comment_cn TEXT,
    comment_en TEXT,
    raw_json TEXT,
    event_time DATETIME
)
DUPLICATE KEY(id)
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES (
    "replication_num" = "1"
);
```

### 5.2 种子样本

```sql
INSERT INTO customer_feedback VALUES
(1, 1001, 'O-001', 'P-100', '物流', 1, 129.90, 'delay,damaged,urgent',
 '快递晚了三天，包装破损，客服一直没有回复。',
 'The delivery was three days late and the package was damaged.',
 '{"phone":"13800000001","email":"alice@example.com","city":"Shanghai"}',
 '2026-06-01 10:00:00'),
(2, 1002, 'O-002', 'P-200', '质量', 5, 899.00, 'good,recommend',
 '产品质量很好，安装简单，下次还会购买。',
 'The product quality is excellent and installation is easy.',
 '{"phone":"13800000002","email":"bob@example.com","city":"Beijing"}',
 '2026-06-01 11:00:00'),
(3, 1003, 'O-003', 'P-300', '价格', 3, 59.90, 'price,neutral',
 '价格一般，功能还可以，没有特别惊喜。',
 'The price is average and the features are acceptable.',
 '{"phone":"13800000003","email":"carol@example.com","city":"Shenzhen"}',
 '2026-06-01 12:00:00'),
(4, 1004, 'O-004', 'P-400', '客服', 1, 399.00, 'service,complaint',
 '客服态度很差，问题处理了两周还没解决。',
 'Customer service was poor and the issue remained unresolved for two weeks.',
 '{"phone":"13800000004","email":"david@example.com","city":"Guangzhou"}',
 '2026-06-01 13:00:00'),
(5, 1005, 'O-005', 'P-500', '其他', NULL, NULL, NULL,
 NULL,
 '',
 '{"phone":null,"email":null,"city":null}',
 '2026-06-01 14:00:00');
```

### 5.3 批量数据生成脚本

文件：`scripts/generate_feedback_data.py`

```python
#!/usr/bin/env python3
import argparse
import csv
import random
from datetime import datetime, timedelta

COMMENTS = [
    ("物流", "快递晚了三天，包装破损，客服一直没有回复。", "The delivery was three days late and the package was damaged.", 1, "delay,damaged,urgent"),
    ("质量", "产品质量很好，安装简单，下次还会购买。", "The product quality is excellent and installation is easy.", 5, "good,recommend"),
    ("价格", "价格一般，功能还可以，没有特别惊喜。", "The price is average and the features are acceptable.", 3, "price,neutral"),
    ("客服", "客服态度很差，问题处理了两周还没解决。", "Customer service was poor and the issue remained unresolved for two weeks.", 1, "service,complaint"),
    ("其他", "页面说明不清楚，希望能增加使用教程。", "The page description is unclear. Please add a tutorial.", 2, "doc,ux"),
]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=10000)
    parser.add_argument("--output", default="customer_feedback.csv")
    args = parser.parse_args()

    start = datetime(2026, 6, 1, 0, 0, 0)
    with open(args.output, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            "id", "user_id", "order_id", "product_id", "category", "rating", "amount",
            "tags", "comment_cn", "comment_en", "raw_json", "event_time"
        ])
        for i in range(1, args.rows + 1):
            category, cn, en, rating, tags = random.choice(COMMENTS)
            user_id = 100000 + random.randint(1, 50000)
            amount = round(random.uniform(10, 5000), 2)
            phone = f"138{random.randint(0, 99999999):08d}"
            email = f"user{i}@example.com"
            city = random.choice(["Shanghai", "Beijing", "Shenzhen", "Guangzhou", "Hangzhou"])
            raw_json = f'{{"phone":"{phone}","email":"{email}","city":"{city}"}}'
            event_time = start + timedelta(minutes=i % 100000)
            writer.writerow([
                i, user_id, f"O-{i:08d}", f"P-{random.randint(1, 500):04d}", category,
                rating, amount, tags, cn, en, raw_json, event_time.strftime("%Y-%m-%d %H:%M:%S")
            ])

if __name__ == "__main__":
    main()
```

生成三档数据：

```bash
python3 scripts/generate_feedback_data.py --rows 100 --output data/customer_feedback_100.csv
python3 scripts/generate_feedback_data.py --rows 10000 --output data/customer_feedback_10k.csv
python3 scripts/generate_feedback_data.py --rows 100000 --output data/customer_feedback_100k.csv
```

### 5.4 数据规模

| 规模 | 行数 | 用途 |
|---|---:|---|
| S | 100 | 功能验证、人工抽检 |
| M | 10,000 | 批量稳定性、AI 成本评估 |
| L | 100,000 | Python UDF 性能、并发、分布式执行 |

## 6. AI 算子验证用例

### 6.1 功能用例矩阵

| 编号 | 算子 | 验证点 | 通过标准 |
|---|---|---|---|
| AI-001 | `CREATE RESOURCE` | AI 资源创建、参数合法性 | Resource 创建成功 |
| AI-002 | `default_ai_resource` | 默认资源选择 | 不显式传 resource 时可调用 |
| AI-003 | 显式 resource | 显式 resource 优先级 | 显式资源生效 |
| AI-004 | `AI_SENTIMENT` | 情感分析 | 输出为 positive/negative/neutral/mixed 或模型等价标签 |
| AI-005 | `AI_CLASSIFY` | 指定标签分类 | 输出属于标签集合 |
| AI-006 | `AI_EXTRACT` | 信息抽取 | 输出包含目标字段 |
| AI-007 | `AI_FILTER` | WHERE 过滤 | 返回 BOOLEAN，可用于过滤 |
| AI-008 | `AI_MASK` | 敏感信息脱敏 | 手机、邮箱被遮盖 |
| AI-009 | `AI_SIMILARITY` | 语义相似度 | 返回 0-10 浮点数 |
| AI-010 | `AI_SUMMARIZE` | 摘要 | 输出短于原文且保留核心问题 |
| AI-011 | `AI_TRANSLATE` | 翻译 | 中英互译语义正确 |
| AI-012 | `AI_AGG` | 跨行聚合 | 分组摘要可读且覆盖主要问题 |
| AI-013 | NULL 输入 | 空值处理 | 返回 NULL 或符合文档行为 |
| AI-014 | 超长文本 | token 限制 | 可定位错误或截断策略明确 |
| AI-015 | API 异常 | 失败重试 | 重试参数生效，错误可观测 |

### 6.2 AI SQL 脚本

文件：`sql/03_ai_function_cases.sql`

```sql
USE doris_ai_udf_poc;

SET default_ai_resource = 'ai_resource_llm';

-- AI-004 情感分析
SELECT id, comment_cn, AI_SENTIMENT(comment_cn) AS sentiment
FROM customer_feedback
WHERE comment_cn IS NOT NULL
ORDER BY id
LIMIT 20;

-- AI-005 分类
SELECT id,
       comment_cn,
       AI_CLASSIFY(comment_cn, ['物流', '质量', '价格', '客服', '其他']) AS ai_category
FROM customer_feedback
WHERE comment_cn IS NOT NULL
ORDER BY id
LIMIT 20;

-- AI-006 信息抽取
SELECT id,
       AI_EXTRACT(comment_cn, ['问题类型', '涉及对象', '用户诉求']) AS extracted_info
FROM customer_feedback
WHERE comment_cn IS NOT NULL
ORDER BY id
LIMIT 20;

-- AI-007 过滤投诉类评论
SELECT id, comment_cn
FROM customer_feedback
WHERE comment_cn IS NOT NULL
  AND AI_FILTER(CONCAT('判断这条评论是否为用户投诉，只返回 true 或 false：', comment_cn))
ORDER BY id
LIMIT 20;

-- AI-008 脱敏
SELECT id, raw_json, AI_MASK(raw_json) AS masked_json
FROM customer_feedback
ORDER BY id
LIMIT 20;

-- AI-009 语义相似度
SELECT id,
       comment_cn,
       AI_SIMILARITY('用户强烈投诉物流延迟、包装破损、客服不响应', comment_cn) AS similarity_score
FROM customer_feedback
WHERE comment_cn IS NOT NULL
ORDER BY similarity_score DESC
LIMIT 20;

-- AI-010 摘要
SELECT id, AI_SUMMARIZE(comment_cn) AS summary
FROM customer_feedback
WHERE comment_cn IS NOT NULL
ORDER BY id
LIMIT 20;

-- AI-011 翻译
SELECT id, AI_TRANSLATE(comment_cn, 'English') AS translated
FROM customer_feedback
WHERE comment_cn IS NOT NULL
ORDER BY id
LIMIT 20;

-- AI-012 分组聚合
SELECT category, AI_AGG(comment_cn) AS category_summary
FROM customer_feedback
WHERE comment_cn IS NOT NULL
GROUP BY category
ORDER BY category;

-- AI-013 NULL 输入
SELECT AI_SENTIMENT(NULL) AS null_sentiment,
       AI_CLASSIFY(NULL, ['物流', '质量']) AS null_classify,
       AI_TRANSLATE(NULL, 'English') AS null_translate;
```

### 6.3 AI 结果评估

AI 输出具有非确定性，不能只用精确字符串断言。建议使用以下指标：

| 指标 | 计算方式 | 通过标准 |
|---|---|---|
| 调用成功率 | 成功 SQL 数 / 总 SQL 数 | 100% |
| 行级失败率 | 失败行数 / 调用行数 | POC 小于 1% |
| 分类准确率 | 人工标注一致数 / 抽检数 | 大于等于 85%，按业务调整 |
| 多次一致率 | 同一批数据运行 3 次的一致比例 | 大于等于 80% |
| 平均延迟 | SQL 总耗时 / 行数 | 记录基线，不设绝对阈值 |
| 成本 | token 或 API 费用 / 千行 | 给出估算，不超业务预算 |

## 7. Python 自定义算子验证用例

### 7.1 用例矩阵

| 编号 | 类型 | 验证点 | 通过标准 |
|---|---|---|---|
| PY-001 | Scalar UDF inline | 字符串清洗 | 结果正确 |
| PY-002 | Scalar UDF inline | NULL 处理 | 不抛异常，返回预期 NULL |
| PY-003 | Scalar UDF inline | 数值计算 | 与 SQL 基准一致 |
| PY-004 | Vectorized UDF | Pandas 批处理 | 结果正确，性能优于 scalar |
| PY-005 | Module UDF | ZIP 模块加载 | 所有 BE 可加载 |
| PY-006 | Type Mapping | INT/DOUBLE/DECIMAL/STRING/DATETIME/BOOLEAN | 类型正确 |
| PY-007 | Complex Input | JSON 字符串解析 | 输出正确 |
| PY-008 | UDAF | 自定义聚合 | 与 SQL 基准一致 |
| PY-009 | UDAF Merge | 3BE 分布式合并 | 与单机/SQL 基准一致 |
| PY-010 | UDWF | 窗口聚合 reset | 窗口结果正确 |
| PY-011 | UDTF | 一行转多行 | 展开结果正确 |
| PY-012 | UDTF + Lateral View | 展开后过滤和聚合 | SQL 可组合 |
| PY-013 | Dependency | numpy/sklearn 加载 | 依赖可用 |
| PY-014 | Error Handling | 返回类型不匹配 | 错误可定位 |
| PY-015 | Env Mismatch | 依赖不一致 | 能通过检查命令识别 |

### 7.2 Python UDF SQL 脚本

文件：`sql/04_python_udf_cases.sql`

```sql
USE doris_ai_udf_poc;

DROP FUNCTION IF EXISTS py_clean_text(STRING);
CREATE FUNCTION py_clean_text(STRING)
RETURNS STRING
PROPERTIES (
    "type" = "PYTHON_UDF",
    "symbol" = "evaluate",
    "runtime_version" = "3.10.12",
    "always_nullable" = "true"
)
AS $$
def evaluate(text):
    if text is None:
        return None
    return text.strip().lower()
$$;

SELECT id, py_clean_text(comment_en) AS cleaned
FROM customer_feedback
ORDER BY id
LIMIT 20;

DROP FUNCTION IF EXISTS py_safe_divide(DOUBLE, DOUBLE);
CREATE FUNCTION py_safe_divide(DOUBLE, DOUBLE)
RETURNS DOUBLE
PROPERTIES (
    "type" = "PYTHON_UDF",
    "symbol" = "evaluate",
    "runtime_version" = "3.10.12",
    "always_nullable" = "true"
)
AS $$
def evaluate(a, b):
    if a is None or b is None or b == 0:
        return None
    return a / b
$$;

SELECT py_safe_divide(10.0, 2.0) AS normal_case,
       py_safe_divide(10.0, 0.0) AS zero_case,
       py_safe_divide(10.0, NULL) AS null_case;

DROP FUNCTION IF EXISTS py_vec_amount_bucket(DOUBLE);
CREATE FUNCTION py_vec_amount_bucket(DOUBLE)
RETURNS STRING
PROPERTIES (
    "type" = "PYTHON_UDF",
    "symbol" = "bucket",
    "runtime_version" = "3.10.12",
    "always_nullable" = "true"
)
AS $$
import pandas as pd

def bucket(x: pd.Series) -> pd.Series:
    return pd.cut(
        x,
        bins=[-1, 100, 1000, 100000000],
        labels=['low', 'mid', 'high']
    ).astype(str)
$$;

SELECT id, amount, py_vec_amount_bucket(CAST(amount AS DOUBLE)) AS amount_bucket
FROM customer_feedback
ORDER BY id
LIMIT 20;

DROP FUNCTION IF EXISTS py_json_city(STRING);
CREATE FUNCTION py_json_city(STRING)
RETURNS STRING
PROPERTIES (
    "type" = "PYTHON_UDF",
    "symbol" = "extract_city",
    "runtime_version" = "3.10.12",
    "always_nullable" = "true"
)
AS $$
import json

def extract_city(raw):
    if raw is None:
        return None
    try:
        return json.loads(raw).get("city")
    except Exception:
        return None
$$;

SELECT id, py_json_city(raw_json) AS city
FROM customer_feedback
ORDER BY id
LIMIT 20;
```

### 7.3 Python UDAF 脚本

```sql
USE doris_ai_udf_poc;

DROP FUNCTION IF EXISTS py_weighted_avg(DOUBLE, DOUBLE);
CREATE AGGREGATE FUNCTION py_weighted_avg(DOUBLE, DOUBLE)
RETURNS DOUBLE
PROPERTIES (
    "type" = "PYTHON_UDF",
    "symbol" = "WeightedAvg",
    "runtime_version" = "3.10.12",
    "always_nullable" = "true"
)
AS $$
class WeightedAvg:
    def __init__(self):
        self.weighted_sum = 0.0
        self.weight_sum = 0.0

    def update(self, value, weight):
        if value is None or weight is None:
            return
        self.weighted_sum += float(value) * float(weight)
        self.weight_sum += float(weight)

    def merge(self, other):
        self.weighted_sum += other.weighted_sum
        self.weight_sum += other.weight_sum

    def finalize(self):
        if self.weight_sum == 0:
            return None
        return self.weighted_sum / self.weight_sum

    def reset(self):
        self.weighted_sum = 0.0
        self.weight_sum = 0.0
$$;

SELECT category,
       py_weighted_avg(CAST(amount AS DOUBLE), CAST(rating AS DOUBLE)) AS py_result,
       SUM(CAST(amount AS DOUBLE) * CAST(rating AS DOUBLE)) / SUM(CAST(rating AS DOUBLE)) AS sql_result
FROM customer_feedback
WHERE amount IS NOT NULL AND rating IS NOT NULL AND rating > 0
GROUP BY category
ORDER BY category;
```

### 7.4 Python UDTF 脚本

```sql
USE doris_ai_udf_poc;

DROP FUNCTION IF EXISTS py_split_tags(STRING);
CREATE TABLES FUNCTION py_split_tags(STRING)
RETURNS ARRAY<STRING>
PROPERTIES (
    "type" = "PYTHON_UDF",
    "symbol" = "split_tags",
    "runtime_version" = "3.10.12",
    "always_nullable" = "true"
)
AS $$
def split_tags(tags):
    if tags is None or tags == "":
        return
    for tag in tags.split(","):
        clean_tag = tag.strip()
        if clean_tag:
            yield clean_tag
$$;

SELECT id, tag
FROM customer_feedback
LATERAL VIEW py_split_tags(tags) t AS tag
ORDER BY id, tag
LIMIT 100;

SELECT tag, COUNT(*) AS cnt
FROM customer_feedback
LATERAL VIEW py_split_tags(tags) t AS tag
GROUP BY tag
ORDER BY cnt DESC, tag
LIMIT 50;
```

### 7.5 Module UDF 验证

文件：`udf_modules/text_ops.py`

```python
import re

def normalize_comment(text):
    if text is None:
        return None
    text = text.strip().lower()
    text = re.sub(r"\s+", " ", text)
    return text

def mask_phone(text):
    if text is None:
        return None
    return re.sub(r"1[3-9]\d{9}", "1**********", text)
```

打包：

```bash
cd udf_modules
zip text_ops.zip text_ops.py
```

创建函数：

```sql
DROP FUNCTION IF EXISTS py_module_normalize(STRING);
CREATE FUNCTION py_module_normalize(STRING)
RETURNS STRING
PROPERTIES (
    "type" = "PYTHON_UDF",
    "symbol" = "text_ops.normalize_comment",
    "file" = "file:///absolute/path/to/udf_modules/text_ops.zip",
    "runtime_version" = "3.10.12",
    "always_nullable" = "true"
);

SELECT id, py_module_normalize(comment_en)
FROM customer_feedback
ORDER BY id
LIMIT 20;
```

## 8. StarRocks Python UDF 验证用例

本章用于补充验证 StarRocks Python UDF。与 Doris 4.x Python UDF/UDAF/UDTF 不同，StarRocks Python UDF 应按 StarRocks 自身约束单独验证，不将 Doris 的 UDAF、UDTF、`runtime_version`、Conda/venv 配置直接套用到 StarRocks。

### 8.1 验证目标和版本约束

| 项 | 设计 |
|---|---|
| 推荐版本 | StarRocks 3.5.x，例如 3.5.17 或同系列最新补丁版 |
| 验证能力 | Python 标量 UDF，覆盖 `scalar` 输入和 `arrow` 输入 |
| 不验证能力 | Python UDAF、UDTF、窗口 UDF；这些不作为 StarRocks Python UDF 通过标准 |
| FE 配置 | `fe.conf` 中设置 `enable_udf = true`，修改后重启 FE |
| BE 配置 | `be.conf` 或 BE 启动环境中配置 `python_envs`，指向 Python 解释器安装目录 |
| Python 版本 | Python 3.8 或更高版本 |
| 依赖要求 | BE Python runtime 中可加载 `pyarrow`；如按官方编译 Python runtime，需要同时安装 `pyarrow` 和 `grpcio` |
| 分发方式 | UDF 代码打包为符合 `zipimport` 的 `.py.zip` 文件，通过 HTTP 地址分发 |
| 符号要求 | `symbol` 必须指向可调用对象，例如 `customer_risk_udf.evaluate_risk`，不能只写模块名 |

重点验证目标：

1. FE UDF 开关和 BE Python runtime 配置正确。
2. `.py.zip` 文件可被所有 BE 下载并导入。
3. `symbol` 指向函数对象，避免出现 `'module' object is not callable`。
4. `scalar` 输入下逐行调用结果正确。
5. `arrow` 输入下批处理结果正确，返回 Arrow 兼容结果。
6. NULL、空字符串、非法 JSON、边界金额、异常评分等输入不会导致查询失败。
7. 多 BE 分布式执行结果一致，失败时能从 BE 日志定位到 Python 异常。

### 8.2 StarRocks 数据构造

建议单独创建 StarRocks POC 数据库，避免与 Doris POC 对象混用。

文件：`sql/starrocks/01_starrocks_python_udf_data.sql`

```sql
CREATE DATABASE IF NOT EXISTS sr_python_udf_poc;
USE sr_python_udf_poc;

DROP TABLE IF EXISTS sr_customer_events;
CREATE TABLE sr_customer_events (
    id BIGINT NOT NULL,
    user_id BIGINT,
    order_id VARCHAR(64),
    category VARCHAR(64),
    rating INT,
    amount DOUBLE,
    tags VARCHAR(500),
    comment_cn STRING,
    comment_en STRING,
    raw_json STRING,
    event_time DATETIME
)
DUPLICATE KEY(id)
DISTRIBUTED BY HASH(id) BUCKETS 8
PROPERTIES (
    "replication_num" = "1"
);

INSERT INTO sr_customer_events VALUES
(1, 1001, 'O-001', '物流', 1, 129.90, 'delay,damaged,urgent',
 '快递晚了三天，包装破损，客服一直没有回复。',
 'The delivery was three days late and the package was damaged.',
 '{"phone":"13800000001","email":"alice@example.com","city":"Shanghai","vip":false}',
 '2026-06-01 10:00:00'),
(2, 1002, 'O-002', '质量', 5, 899.00, 'good,recommend',
 '产品质量很好，安装简单，下次还会购买。',
 'The product quality is excellent and installation is easy.',
 '{"phone":"13800000002","email":"bob@example.com","city":"Beijing","vip":true}',
 '2026-06-01 11:00:00'),
(3, 1003, 'O-003', '价格', 3, 59.90, 'price,neutral',
 '价格一般，功能还可以，没有特别惊喜。',
 'The price is average and the features are acceptable.',
 '{"phone":"13800000003","email":"carol@example.com","city":"Shenzhen","vip":false}',
 '2026-06-01 12:00:00'),
(4, 1004, 'O-004', '客服', 1, 399.00, 'service,complaint',
 '客服态度很差，问题处理了两周还没解决。',
 'Customer service was poor and the issue remained unresolved for two weeks.',
 '{"phone":"13800000004","email":"david@example.com","city":"Guangzhou","vip":false}',
 '2026-06-01 13:00:00'),
(5, 1005, 'O-005', '其他', NULL, NULL, NULL,
 NULL,
 '',
 '{"phone":null,"email":null,"city":null,"vip":false}',
 '2026-06-01 14:00:00'),
(6, 1006, 'O-006', '风控', 1, 12000.00, 'refund,complaint,high_value',
 '高金额订单申请退款，用户连续投诉物流和客服。',
 'High-value order requested refund with repeated delivery and service complaints.',
 '{"phone":"13900000006","email":"risk@example.com","city":"Hangzhou","vip":true}',
 '2026-06-01 15:00:00'),
(7, 1007, 'O-007', '异常', -1, -20.00, 'bad_data',
 '异常测试数据。',
 'Bad test data.',
 'not a json document',
 '2026-06-01 16:00:00');
```

批量数据可复用第 5.3 节脚本，导入到 StarRocks 时建议生成三档：

| 数据集 | 行数 | 用途 |
|---|---:|---|
| SR-S | 100 | 功能验证、人工抽检 |
| SR-M | 10,000 | Arrow 批处理、稳定性 |
| SR-L | 100,000 | 分布式执行、并发、性能基线 |

### 8.3 稍复杂 Python UDF 脚本

该脚本实现两个函数：

- `evaluate_risk`：`scalar` 输入，逐行解析 JSON、评分、金额、标签和评论，输出风险等级。
- `evaluate_risk_arrow`：`arrow` 输入，按批处理生成同样的风险等级，验证 StarRocks Arrow 路径。

文件：`udf_modules/starrocks/customer_risk_udf.py`

```python
import json
import re

import pyarrow as pa


NEGATIVE_WORDS = (
    "late", "damaged", "poor", "unresolved", "refund", "complaint",
    "晚", "破损", "差", "投诉", "退款", "未解决"
)


def _to_float(value):
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _to_int(value):
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _parse_json(raw_json):
    if not raw_json:
        return {}
    try:
        obj = json.loads(raw_json)
        return obj if isinstance(obj, dict) else {}
    except Exception:
        return {}


def _keyword_count(*texts):
    merged = " ".join(str(text).lower() for text in texts if text)
    return sum(1 for word in NEGATIVE_WORDS if word in merged)


def _normalize_tags(tags):
    if not tags:
        return set()
    return {tag.strip().lower() for tag in str(tags).split(",") if tag.strip()}


def _risk_level(rating, amount, tags, comment_cn, comment_en, raw_json):
    score = 0
    rating_value = _to_int(rating)
    amount_value = _to_float(amount)
    tag_set = _normalize_tags(tags)
    profile = _parse_json(raw_json)

    if rating_value is None:
        score += 1
    elif rating_value <= 1:
        score += 3
    elif rating_value <= 2:
        score += 2
    elif rating_value >= 5:
        score -= 1

    if amount_value is None:
        score += 1
    elif amount_value >= 10000:
        score += 4
    elif amount_value >= 1000:
        score += 2
    elif amount_value < 0:
        score += 3

    if {"complaint", "refund", "urgent", "damaged"} & tag_set:
        score += 2
    if "high_value" in tag_set:
        score += 2

    score += min(_keyword_count(comment_cn, comment_en), 3)

    phone = profile.get("phone")
    email = profile.get("email")
    if phone and not re.fullmatch(r"1[3-9]\d{9}", str(phone)):
        score += 1
    if email and "@" not in str(email):
        score += 1
    if profile.get("vip") is True:
        score -= 1

    if score >= 7:
        return "high"
    if score >= 4:
        return "medium"
    if score >= 1:
        return "low"
    return "normal"


def evaluate_risk(rating, amount, tags, comment_cn, comment_en, raw_json):
    return _risk_level(rating, amount, tags, comment_cn, comment_en, raw_json)


def evaluate_risk_arrow(rating, amount, tags, comment_cn, comment_en, raw_json):
    result = []
    for values in zip(
        rating.to_pylist(),
        amount.to_pylist(),
        tags.to_pylist(),
        comment_cn.to_pylist(),
        comment_en.to_pylist(),
        raw_json.to_pylist(),
    ):
        result.append(_risk_level(*values))
    return pa.array(result, type=pa.string())
```

本地导入检查：

```bash
python3 - <<'PY'
from udf_modules.starrocks.customer_risk_udf import evaluate_risk

print(evaluate_risk(
    1,
    12000.0,
    "refund,complaint,high_value",
    "高金额订单申请退款，用户连续投诉物流和客服。",
    "High-value order requested refund with repeated complaints.",
    '{"phone":"13900000006","email":"risk@example.com","city":"Hangzhou","vip":true}'
))
PY
```

期望输出：

```text
high
```

### 8.4 打包和分发

StarRocks Python UDF 包建议使用 `.py.zip` 后缀，zip 顶层直接放模块文件，保证 `zipimport` 能通过模块名导入。

文件：`scripts/package_starrocks_udf.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

mkdir -p dist/starrocks
cd udf_modules/starrocks
zip -r ../../dist/starrocks/customer_risk_udf.py.zip customer_risk_udf.py
cd -

python3 - <<'PY'
import sys
sys.path.insert(0, "dist/starrocks/customer_risk_udf.py.zip")

import customer_risk_udf

print(customer_risk_udf)
print(customer_risk_udf.evaluate_risk)
print(callable(customer_risk_udf.evaluate_risk))
PY
```

期望 `callable(customer_risk_udf.evaluate_risk)` 输出 `True`。

HTTP 分发示例：

```bash
cd dist/starrocks
python3 -m http.server 18080
```

生产或工程验证环境应使用稳定 HTTP 服务或对象存储地址，不使用开发机临时服务作为长期依赖。

### 8.5 StarRocks 函数创建 SQL

文件：`sql/starrocks/02_starrocks_python_udf_create.sql`

```sql
USE sr_python_udf_poc;

DROP FUNCTION IF EXISTS sr_risk_level(INT, DOUBLE, VARCHAR, STRING, STRING, STRING);
CREATE FUNCTION sr_risk_level(INT, DOUBLE, VARCHAR, STRING, STRING, STRING)
RETURNS STRING
PROPERTIES (
    "type" = "Python",
    "symbol" = "customer_risk_udf.evaluate_risk",
    "file" = "http://HTTP_HOST:18080/customer_risk_udf.py.zip",
    "input" = "scalar"
);

DROP FUNCTION IF EXISTS sr_risk_level_arrow(INT, DOUBLE, VARCHAR, STRING, STRING, STRING);
CREATE FUNCTION sr_risk_level_arrow(INT, DOUBLE, VARCHAR, STRING, STRING, STRING)
RETURNS STRING
PROPERTIES (
    "type" = "Python",
    "symbol" = "customer_risk_udf.evaluate_risk_arrow",
    "file" = "http://HTTP_HOST:18080/customer_risk_udf.py.zip",
    "input" = "arrow"
);
```

注意事项：

- `symbol` 必须写到函数名：`customer_risk_udf.evaluate_risk`，不能只写 `customer_risk_udf`。
- `file` 建议使用 `.py.zip`，不要使用普通 `.zip` 名称规避约定。
- zip 顶层必须包含 `customer_risk_udf.py`，不能多包一层目录。
- 如果使用 `input = "arrow"`，函数入参应按 Arrow Array 处理，返回 Arrow 兼容结果。

### 8.6 功能验证用例

文件：`sql/starrocks/03_starrocks_python_udf_cases.sql`

```sql
USE sr_python_udf_poc;

-- SR-PY-001 scalar 基础结果
SELECT id,
       rating,
       amount,
       tags,
       sr_risk_level(rating, amount, tags, comment_cn, comment_en, raw_json) AS risk_level
FROM sr_customer_events
ORDER BY id;

-- SR-PY-002 arrow 基础结果
SELECT id,
       sr_risk_level_arrow(rating, amount, tags, comment_cn, comment_en, raw_json) AS risk_level
FROM sr_customer_events
ORDER BY id;

-- SR-PY-003 scalar 与 arrow 结果一致性
SELECT id,
       sr_risk_level(rating, amount, tags, comment_cn, comment_en, raw_json) AS scalar_result,
       sr_risk_level_arrow(rating, amount, tags, comment_cn, comment_en, raw_json) AS arrow_result
FROM sr_customer_events
WHERE sr_risk_level(rating, amount, tags, comment_cn, comment_en, raw_json)
   <> sr_risk_level_arrow(rating, amount, tags, comment_cn, comment_en, raw_json)
ORDER BY id;

-- SR-PY-004 NULL、空字符串、非法 JSON 不应导致查询失败
SELECT id,
       sr_risk_level(rating, amount, tags, comment_cn, comment_en, raw_json) AS risk_level
FROM sr_customer_events
WHERE id IN (5, 7)
ORDER BY id;

-- SR-PY-005 聚合组合能力
SELECT sr_risk_level_arrow(rating, amount, tags, comment_cn, comment_en, raw_json) AS risk_level,
       COUNT(*) AS cnt,
       AVG(amount) AS avg_amount
FROM sr_customer_events
GROUP BY sr_risk_level_arrow(rating, amount, tags, comment_cn, comment_en, raw_json)
ORDER BY risk_level;

-- SR-PY-006 与业务规则预期对齐
SELECT id,
       sr_risk_level(rating, amount, tags, comment_cn, comment_en, raw_json) AS risk_level
FROM sr_customer_events
WHERE id IN (2, 6, 7)
ORDER BY id;
```

人工预期：

| id | 预期等级 | 原因 |
|---:|---|---|
| 2 | normal 或 low | 高评分、VIP、正向评论，风险应最低 |
| 5 | low | rating/amount/comment 为空，允许保守低风险，但不应失败 |
| 6 | high | 高金额、退款、投诉、高价值标签、多负向关键词 |
| 7 | medium 或 high | 负金额、异常评分、非法 JSON，风险应高于普通订单 |

### 8.7 验证矩阵

| 编号 | 类型 | 验证点 | SQL/动作 | 通过标准 |
|---|---|---|---|---|
| SR-PY-001 | scalar | 逐行调用 | 查询 `sr_risk_level` | 结果非空且符合人工预期 |
| SR-PY-002 | arrow | 批处理调用 | 查询 `sr_risk_level_arrow` | 结果非空且无 Arrow 异常 |
| SR-PY-003 | 一致性 | scalar 与 arrow 等价 | 差异查询返回 0 行 | 两种输入路径结果一致 |
| SR-PY-004 | NULL/异常输入 | NULL、空字符串、非法 JSON | 查询 id 5、7 | 不抛异常，返回可解释等级 |
| SR-PY-005 | SQL 组合 | UDF 用于 `GROUP BY` | 聚合风险等级 | 聚合成功，行数与总量一致 |
| SR-PY-006 | 分布式 | 3BE 上重复执行 | SR-M/SR-L 数据集 | 多次结果一致，无 BE 局部失败 |
| SR-PY-007 | 包加载 | `.py.zip` 分发 | 重建函数并查询 | 所有 BE 可下载和导入 |
| SR-PY-008 | 错误定位 | `symbol` 写模块名 | `symbol = "customer_risk_udf"` | 复现 `'module' object is not callable`，日志可定位 |
| SR-PY-009 | 文件命名 | 使用非 `.py.zip` 名称 | 将包改为 `.zip` | 若失败，错误可定位；若成功，也记录为不推荐配置 |
| SR-PY-010 | 返回类型 | 返回非 STRING | 故意返回 dict/list | 查询失败且错误明确 |

### 8.8 批量和并发验证

文件：`sql/starrocks/04_starrocks_python_udf_benchmark.sql`

```sql
USE sr_python_udf_poc;

SELECT COUNT(*) AS total_rows
FROM sr_customer_events;

SELECT sr_risk_level_arrow(rating, amount, tags, comment_cn, comment_en, raw_json) AS risk_level,
       COUNT(*) AS cnt
FROM sr_customer_events
GROUP BY sr_risk_level_arrow(rating, amount, tags, comment_cn, comment_en, raw_json)
ORDER BY risk_level;

SELECT category,
       sr_risk_level_arrow(rating, amount, tags, comment_cn, comment_en, raw_json) AS risk_level,
       COUNT(*) AS cnt,
       AVG(amount) AS avg_amount
FROM sr_customer_events
GROUP BY category, sr_risk_level_arrow(rating, amount, tags, comment_cn, comment_en, raw_json)
ORDER BY category, risk_level;
```

压测脚本可复用第 9.2 节脚本，环境变量改为 StarRocks 连接信息：

```bash
HOST="${STARROCKS_HOST:-127.0.0.1}"
PORT="${STARROCKS_QUERY_PORT:-9030}"
USER="${STARROCKS_USER:-root}"
PASSWORD="${STARROCKS_PASSWORD:-}"
DB="${STARROCKS_DB:-sr_python_udf_poc}"
```

建议执行口径：

| 数据集 | 并发 | 重复次数 | 关注指标 |
|---|---:|---:|---|
| SR-S | 1 | 3 | 功能正确性 |
| SR-M | 3 | 3 | Arrow 批处理稳定性、BE 日志 |
| SR-L | 5 | 3 | 查询耗时、失败率、Python worker 异常 |

### 8.9 异常排查重点

| 错误现象 | 优先检查 | 修复方向 |
|---|---|---|
| `'module' object is not callable` | `symbol` 是否只写了模块名 | 改为 `模块名.函数名` |
| `ModuleNotFoundError` | zip 顶层结构和 `file` URL | 确保 zip 顶层是 `.py` 文件，HTTP 可访问 |
| Arrow 类型错误 | `input = "arrow"` 函数入参/返回值 | 入参按 Arrow Array 处理，返回 `pa.array` 或 Arrow 兼容对象 |
| 返回类型不匹配 | SQL `RETURNS` 与 Python 返回值 | 保持返回 STRING/INT/DOUBLE 等类型一致 |
| 某些 BE 失败 | BE 是否都能访问 HTTP 包和依赖 | 检查 BE 网络、日志、pyarrow 版本 |
| `.zip` 包偶发失败 | 文件名不符合 `.py.zip` 约定 | 统一改为 `xxx.py.zip` |

验收标准：

- `sr_risk_level` 和 `sr_risk_level_arrow` 均可创建并执行。
- scalar 与 arrow 在同一批数据上结果一致。
- SR-S/SR-M/SR-L 三档数据均无不可解释失败。
- 错误用例可以复现并定位到具体配置、包结构或 Python 返回类型问题。
- 形成 StarRocks 与 Doris Python UDF 能力边界对比，避免把 Doris UDAF/UDTF 能力误判为 StarRocks 能力。

## 9. 性能与稳定性测试

### 9.1 性能对比场景

| 编号 | 场景 | 对比对象 | 指标 |
|---|---|---|---|
| PERF-001 | 内置 SQL 字符串函数 | `lower(trim())` vs scalar UDF | 耗时、CPU |
| PERF-002 | scalar UDF | row-by-row | QPS、耗时 |
| PERF-003 | vectorized UDF | Pandas Series | 与 scalar 性能比 |
| PERF-004 | UDAF | Python UDAF vs SQL 聚合 | 正确性、耗时 |
| PERF-005 | UDTF | tag 展开 | 输出行数、耗时 |
| PERF-006 | AI Function | 100/1k/10k 行 | 延迟、失败率、成本 |

### 9.2 压测脚本

文件：`scripts/run_sql_benchmark.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

HOST="${DORIS_HOST:-127.0.0.1}"
PORT="${DORIS_PORT:-9030}"
USER="${DORIS_USER:-root}"
PASSWORD="${DORIS_PASSWORD:-}"
DB="${DORIS_DB:-doris_ai_udf_poc}"
SQL_FILE="${1:?usage: run_sql_benchmark.sh sql_file}"
REPEAT="${REPEAT:-3}"

for i in $(seq 1 "${REPEAT}"); do
  echo "run=${i}, sql=${SQL_FILE}, start=$(date '+%Y-%m-%d %H:%M:%S')"
  start=$(date +%s)
  mysql -h"${HOST}" -P"${PORT}" -u"${USER}" -p"${PASSWORD}" "${DB}" < "${SQL_FILE}"
  end=$(date +%s)
  echo "run=${i}, elapsed_seconds=$((end - start)), end=$(date '+%Y-%m-%d %H:%M:%S')"
done
```

### 9.3 并发测试脚本

文件：`scripts/run_concurrent_sql.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

SQL_FILE="${1:?usage: run_concurrent_sql.sh sql_file}"
CONCURRENCY="${CONCURRENCY:-5}"
REPEAT="${REPEAT:-3}"

for worker in $(seq 1 "${CONCURRENCY}"); do
  (
    for i in $(seq 1 "${REPEAT}"); do
      echo "worker=${worker}, run=${i}"
      scripts/run_sql_benchmark.sh "${SQL_FILE}"
    done
  ) &
done

wait
```

### 9.4 性能验收口径

| 项 | 通过标准 |
|---|---|
| Python scalar UDF | 结果正确，性能劣化可解释 |
| Python vectorized UDF | 同等逻辑下明显优于 scalar UDF |
| Python UDAF | 3BE 分布式结果与 SQL 基准一致 |
| Python UDTF | 展开行数正确，聚合结果稳定 |
| AI Function | 小批量稳定，成本可估算，失败可定位 |

## 10. 异常、边界与安全测试

### 10.1 异常测试矩阵

| 编号 | 场景 | 操作 | 预期 |
|---|---|---|---|
| ERR-001 | Python UDF 未开启 | 关闭 `enable_python_udf_support` 后调用 | 明确报错 |
| ERR-002 | Python 版本不存在 | `runtime_version='3.9.99'` | 报 Python 环境不存在 |
| ERR-003 | 缺少 pandas | 删除依赖后调用 | 报依赖错误 |
| ERR-004 | BE 依赖不一致 | 某 BE 安装不同 pyarrow | `SHOW PYTHON PACKAGES` 可识别 |
| ERR-005 | UDF 返回类型错误 | 返回字符串到 DOUBLE | SQL 报错，可定位 |
| ERR-006 | UDAF merge 错误 | 故意实现错误 merge | 分布式结果与基准不一致 |
| ERR-007 | UDTF yield 类型错误 | yield tuple 到单列 string | SQL 报错 |
| ERR-008 | AI API Key 错误 | 错误 key | 调用失败，可定位 |
| ERR-009 | AI Provider 限流 | 并发触发限流 | 重试生效或失败可观测 |
| ERR-010 | 超长 prompt | 发送长文本 | 失败或截断策略明确 |

### 10.2 安全检查

| 项 | 检查方式 | 标准 |
|---|---|---|
| API Key | 检查 SQL 输出、FE/BE 日志 | 不应明文暴露 |
| UDF 文件 | 检查 ZIP 来源和权限 | 可追溯、只读、不可随意覆盖 |
| Python 依赖 | 固定 requirements 或 environment.yml | 可复现 |
| 外部 IO | 代码扫描 UDF 是否访问网络/文件 | 默认禁止 |
| 权限 | 检查 CREATE FUNCTION / RESOURCE 权限 | 仅管理员或受控角色 |

### 10.3 UDF 代码扫描脚本

文件：`scripts/scan_udf_code.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="${1:-udf_modules}"

grep -RInE "requests|urllib|httpx|socket|open\\(|subprocess|os\\.system|popen|eval\\(|exec\\(" "${TARGET_DIR}" || true
```

命中并不一定失败，但必须人工确认是否合理。

## 11. 可观测性

### 11.1 必查日志

| 日志 | 用途 |
|---|---|
| FE query log | SQL 执行、失败定位 |
| BE log | 执行异常、资源问题 |
| `output/be/log/python_udf_output.log` | Python UDF 运行时错误 |
| AI Provider 日志 | 请求量、token、限流、错误 |

### 11.2 运行中检查 SQL

```sql
SHOW FRONTENDS;
SHOW BACKENDS;
SHOW RESOURCES;
SHOW PYTHON VERSIONS;
SHOW PYTHON PACKAGES IN '3.10.12';
```

## 12. 执行计划

### 12.1 阶段划分

| 阶段 | 时间 | 输出 |
|---|---|---|
| D1 环境准备 | 0.5-1 天 | Doris 4.x 集群、Python 环境、AI Resource |
| D2 数据准备 | 0.5 天 | S/M/L 三档数据 |
| D3 功能验证 | 1 天 | AI 与 Python UDF 功能结果 |
| D4 工程验证 | 1-2 天 | 性能、并发、异常、日志 |
| D5 结论整理 | 0.5 天 | POC 报告和上线建议 |

### 12.2 执行顺序

1. 部署 Doris 4.1.x。
2. 配置 Python UDF 环境并重启 BE。
3. 执行 `SHOW PYTHON VERSIONS` 和 `SHOW PYTHON PACKAGES`。
4. 创建 AI Resource，验证 `AI_SENTIMENT` 单条调用。
5. 建表并导入 S 档数据。
6. 执行 AI 功能 SQL。
7. 执行 Python UDF/UDAF/UDTF SQL。
8. 导入 M/L 档数据。
9. 执行性能和并发测试。
10. 执行异常和安全测试。
11. 整理报告。

## 13. 验收标准

### 13.1 必须通过

- Doris 4.x 集群部署完成。
- Python UDF 环境在所有 BE 节点可见。
- `pandas` 和 `pyarrow` 在所有 BE 节点版本一致。
- Scalar UDF、Vectorized UDF、UDAF、UDTF 均能成功执行。
- UDAF 在 3BE 分布式场景下结果与 SQL 基准一致。
- AI Function 至少完成情感分析、分类、抽取、摘要、翻译、聚合 6 类验证。
- 异常可通过 SQL 错误、FE/BE 日志或 Python UDF 日志定位。

### 13.2 建议通过

- Vectorized UDF 性能明显优于 scalar UDF。
- AI Function 多次运行一致率满足业务要求。
- AI 成本可估算，并可通过 LIMIT、抽样、分批策略控制。
- UDF 模块化 ZIP 包可在所有 BE 节点稳定加载。

### 13.3 生产准入

| 维度 | 准入要求 |
|---|---|
| 功能 | 核心用例 100% 通过 |
| 正确性 | Python 结果与基准脚本一致 |
| 性能 | 满足业务 SLA，或有降级策略 |
| 稳定性 | 并发和批量测试无不可解释失败 |
| 安全 | API Key、UDF 文件、权限受控 |
| 运维 | 环境、依赖、日志、回滚路径明确 |

## 14. POC 报告模板

```markdown
# Apache Doris AI Function 与 Python UDF POC 报告

## 1. 环境

- Doris 版本：
- FE/BE 拓扑：
- Python 版本：
- pandas 版本：
- pyarrow 版本：
- AI Provider：
- 模型：

## 2. 版本支持结论

- AI Function：
- Python UDF/UDAF/UDTF：
- 推荐版本：

## 3. 数据规模

| 数据集 | 行数 | 说明 |
|---|---:|---|
| S | | |
| M | | |
| L | | |

## 4. 功能验证结果

| 用例 | 结果 | 备注 |
|---|---|---|
| AI-001 | PASS/FAIL | |
| PY-001 | PASS/FAIL | |

## 5. 性能结果

| 场景 | 数据量 | 并发 | 耗时 | QPS | 备注 |
|---|---:|---:|---:|---:|---|

## 6. 异常测试结果

| 场景 | 结果 | 是否可定位 |
|---|---|---|

## 7. 风险

- 

## 8. 生产建议

- 
```

## 15. 架构判断

### 15.1 适合使用 AI Function 的场景

- 中小批量文本分类、摘要、抽取、翻译、情感分析。
- 分析类 SQL 中需要直接使用 AI 输出作为辅助维度。
- 数据不方便导出到外部应用，但允许调用外部模型 API。
- 需要减少 Glue Code，把分析链路留在 Doris SQL 内。

### 15.2 不适合使用 AI Function 的场景

- 高并发、低延迟、强 SLA 的在线推理。
- 强一致、可审计、不可随机变化的核心决策。
- 大规模全量文本处理且模型调用成本敏感。
- 输入包含不能出域的敏感数据，且外部 Provider 无法满足合规。

### 15.3 适合使用 Python UDF 的场景

- Doris 内置函数不能覆盖的复杂清洗、转换、解析。
- 中等规模数据上的业务规则计算。
- 需要复用 Python 生态库，但逻辑仍然是纯函数。
- UDTF 用于一行转多行，例如标签展开、字符串拆分、简单 JSON 展开。
- UDAF 用于自定义聚合指标，并且可接受 Python 执行开销。

### 15.4 不适合使用 Python UDF 的场景

- 大规模高频计算且可用 Doris 内置函数替代。
- UDF 内访问外部网络、数据库、文件系统。
- 复杂模型推理、GPU 推理、长耗时任务。
- 依赖包难以在所有 BE 节点保持一致的场景。

## 16. 最终建议

本次验证应以 **Doris 4.1.x + 3BE 分布式集群** 作为主验证环境，覆盖 AI Function 和 Python UDF/UDAF/UDTF 的完整能力。若当前企业生产版本低于 4.x，建议将结论定义为“Doris 4.x 升级后可获得的 AI 与 Python UDF 能力”，并单独评估升级成本。

生产落地时建议采用以下策略：

1. AI Function 用于分析增强，不用于强一致核心决策。
2. Python UDF 用于 Doris 内置函数无法表达的中等复杂度逻辑。
3. 大规模推理、复杂模型和外部 IO 保持在外部服务或离线任务中。
4. 所有 Python 依赖通过标准化环境文件统一分发到 BE 节点。
5. 对 AI 成本、失败率、输出一致性建立常态化监控。
