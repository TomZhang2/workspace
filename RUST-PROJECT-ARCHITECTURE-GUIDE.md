# Rust 项目代码架构设计指南

---

## 一、项目组织模式：单 crate vs Workspace

### 1.1 单 crate 项目（小型）

适用于工具类、库类小项目。

```
my-project/
├── Cargo.toml
├── src/
│   ├── lib.rs          # 库入口
│   ├── main.rs         # 二进制入口（可选）
│   └── ...
├── tests/              # 集成测试
└── benches/            # 基准测试
```

### 1.2 Workspace 多 crate（中大型）

适用于多模块、多二进制的项目（如 databend、tikv）。

```
my-workspace/
├── Cargo.toml              # workspace 根配置（不包含 [package]）
├── Cargo.lock              # 统一锁文件（仅根目录有）
├── crates/
│   ├── core/               # 核心库
│   │   ├── Cargo.toml
│   │   └── src/
│   ├── api/                # API 层
│   │   ├── Cargo.toml
│   │   └── src/
│   ├── cli/                # CLI 二进制
│   │   ├── Cargo.toml
│   │   └── src/
│   └── utils/              # 工具库
│       ├── Cargo.toml
│       └── src/
├── tests/                  # 跨 crate 集成测试
└── benches/
```

**Workspace 根 `Cargo.toml` 规范：**

```toml
[workspace]
resolver = "2"
members = ["crates/*"]

# 统一版本与元信息
[workspace.package]
version = "0.1.0"
edition = "2021"
license = "MIT"
authors = ["Author <email>"]

# 集中管理依赖版本（避免各 crate 版本不一致）
[workspace.dependencies]
serde = { version = "1.0", features = ["derive"] }
tokio = { version = "1", features = ["full"] }
anyhow = "1.0"

# 子 crate 引用
# serde = { workspace = true }
```

**关键约束：**
- `Cargo.lock` **只在 workspace 根目录**存在，子 crate 不提交自己的 lock
- `[workspace.dependencies]` 集中声明依赖版本，子 crate 用 `{ workspace = true }` 引用
- `resolver = "2"` 是 edition 2021+ 的默认值，确保 feature 统一解析

---

## 二、目录结构标准约定

### 2.1 标准 crate 内部结构

```
crate-name/
├── Cargo.toml
├── build.rs                  # 构建脚本（可选）
├── src/
│   ├── lib.rs                # 库 crate 入口
│   ├── main.rs               # 二进制 crate 入口
│   ├── bin/                  # 额外二进制目标
│   │   └── tool.rs
│   ├── module_a/             # 模块目录（推荐用目录而非 mod.rs）
│   │   ├── mod.rs  或  module_a.rs  # 二选一（见下方说明）
│   │   ├── sub_module.rs
│   │   └── tests.rs          # 模块级单元测试
│   ├── module_b.rs
│   └── tests/                # 模块测试（替代内联）
├── tests/                    # 集成测试（仅 lib crate 有）
│   ├── integration_test.rs
│   └── common/
│       └── mod.rs
├── benches/                  # 基准测试
│   └── bench.rs
└── examples/                 # 示例代码
    └── example.rs
```

### 2.2 `mod.rs` vs `module.rs` 两种风格

Rust 支持两种模块组织方式：

```
# 风格 A（传统）：使用 mod.rs
src/
├── lib.rs
└── network/
    ├── mod.rs        # 声明 pub mod tcp; pub mod udp;
    ├── tcp.rs
    └── udp.rs

# 风格 B（现代，推荐）：用同名文件
src/
├── lib.rs
├── network.rs        # 声明 pub mod tcp; pub mod udp;
└── network/
    ├── tcp.rs
    └── udp.rs
```

**约定**：**保持整个项目一致**。现代趋势偏向风格 B（减少文件名 `mod.rs` 重复），但风格 A 在深层嵌套时更清晰。databend 等大型项目两种混用，关键是**同一项目内保持一致**。

### 2.3 `src/bin/` — 多二进制目标

```toml
# Cargo.toml
[[bin]]
name = "myapp"
path = "src/main.rs"

[[bin]]
name = "mytool"
path = "src/bin/tool.rs"
```

```
src/
├── main.rs          # 默认二进制（crate 同名）
└── bin/
    ├── tool.rs      # cargo run --bin tool
    └── helper.rs    # cargo run --bin helper
```

---

## 三、模块设计与可见性约束

### 3.1 模块层次原则

```
src/
├── lib.rs              # 仅 re-export，不写逻辑
├── error.rs            # 统一错误类型
├── config.rs           # 配置
├── types/              # 公共类型定义
│   ├── mod.rs
│   ├── id.rs
│   └── model.rs
├── domain/             # 领域逻辑（核心业务）
│   ├── mod.rs
│   ├── user.rs
│   └── order.rs
├── infrastructure/     # 基础设施（DB、外部 API）
│   ├── mod.rs
│   ├── db.rs
│   └── cache.rs
└── service/            # 服务编排层
    ├── mod.rs
    └── user_service.rs
```

### 3.2 可见性规则（pub 纪律）

| 场景 | 规则 |
|------|------|
| `lib.rs` | 仅 `pub mod` 声明 + `pub use` re-export，**不写业务逻辑** |
| `pub` | 仅对外 API 用 `pub`，内部实现用 `pub(crate)` |
| `pub(crate)` | 默认的内部可见性，优于 `pub` |
| `pub(super)` | 仅父模块可见 |
| `#[doc(hidden)]` | 隐藏内部实现细节不出现在文档 |

```rust
// lib.rs — 仅做门面（facade）
pub mod error;
pub mod config;
pub mod domain;

// 对外稳定 API（re-export 简化路径）
pub use domain::user::{User, UserId};
pub use error::{Error, Result};

// === 以下是反模式 ===
// pub mod internal_helper;  // 不要暴露内部模块
```

```rust
// domain/user.rs
pub struct User {          // 对外公开
    pub id: UserId,
    pub name: String,
    internal_cache: Cache,  // 私有字段
}

pub(crate) fn validate(u: &User) -> bool {  // 仅 crate 内可见
    // ...
}

fn helper() {}  // 模块私有
```

### 3.3 prelude 模式

对于库 crate，提供 `prelude` 模块简化用户导入：

```rust
// src/prelude.rs
pub use crate::error::{Error, Result};
pub use crate::domain::{User, Order, UserId};
pub use crate::config::Config;

// 用户侧：use my_crate::prelude::*;
```

---

## 四、错误处理约束

### 4.1 统一错误类型（强制）

```rust
// src/error.rs
use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    #[error("user not found: {0}")]
    NotFound(String),

    #[error("database error: {0}")]
    Database(#[from] sqlx::Error),

    #[error("invalid input: {0}")]
    InvalidInput(String),

    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

pub type Result<T> = std::result::Result<T, Error>;
```

**约束：**
- **库 crate** 用 `thiserror` 定义枚举式错误（精确、可匹配）
- **应用 crate** 可用 `anyhow::Result`（快速、聚合）
- **禁止**裸 `unwrap()` / `expect()` 在生产代码中（除 `const` 上下文或确信不变式处）
- **禁止**吞错误 `let _ = result;`——用 `let _ = result;` 时必须注释原因

### 4.2 `?` 运算符 + `From` 自动转换

```rust
fn get_user(id: &str) -> Result<User> {
    let row = db::query(id)?;       // sqlx::Error 自动转 Error::Database
    let name = parse_name(&row)?;   // 自定义错误需 #[from]
    Ok(User { id: id.into(), name })
}
```

---

## 五、命名规范（强制）

遵循 [Rust API Guidelines (C-CASE)](https://rust-lang.github.io/api-guidelines/naming.html)：

| 类型 | 规范 | 示例 |
|------|------|------|
| Crate 名 | `snake_case` 或 `kebab-case` | `databend_common_ast` |
| 模块名 | `snake_case` | `mod user_service;` |
| 类型（struct/enum/trait） | `UpperCamelCase` | `HttpClient`, `ErrorKind` |
| 函数/方法 | `snake_case` | `fn get_user()` |
| 常量/静态 | `SCREAMING_SNAKE_CASE` | `const MAX_RETRY: u32` |
| 类型参数 | 单大写或 `UpperCamelCase` | `T`, `Key`, `Value` |
| 生命周期 | 短小写 | `'a`, `'ctx` |
| Feature 名 | `kebab-case` | `tokio-support` |
| 字段 | `snake_case` | `user_id: String` |

**特例：**
- `Sha256`, `Md5` 等缩写保留原大小写
- 构造器约定 `fn new() -> Self`；多个用 `fn with_xxx()`
- 布尔返回 `fn is_xxx() -> bool` / `fn has_xxx() -> bool` / `fn should_xxx() -> bool`
- 转换 `fn as_xxx(&self)` (借用) / `fn to_xxx(&self)` (拥有) / `fn into_xxx(self)` (消费)

---

## 六、Trait 设计约束

### 6.1 Trait 定义与实现分离

```rust
// src/domain/repository.rs — 抽象
pub trait UserRepository: Send + Sync {
    async fn find(&self, id: &UserId) -> Result<Option<User>>;
    async fn save(&self, user: &User) -> Result<()>;
}

// src/infra/db.rs — 实现
pub struct PgUserRepository { pool: PgPool }

impl UserRepository for PgUserRepository {
    async fn find(&self, id: &UserId) -> Result<Option<User>> { /* ... */ }
    async fn save(&self, user: &User) -> Result<()> { /* ... */ }
}
```

**约束：**
- Trait 默认 `?Sized`，需 `Send + Sync` 时显式声明（多线程必需）
- 优先用 trait object（`dyn Trait`）做依赖注入，泛型做性能关键路径
- Trait 方法**不要默认实现**太多——会让实现者遗漏语义
- 用 `#[async_trait]` 或原生 async trait（edition 2024+）

### 6.2 避免上帝 Trait

```rust
// 反模式
trait GodService {
    fn user_op(&self);
    fn order_op(&self);
    fn billing_op(&self);
}

// 正确：单一职责
trait UserService { fn user_op(&self); }
trait OrderService { fn order_op(&self); }
```

---

## 七、测试组织约束

### 7.1 三层测试结构

```
src/
└── user.rs
    └── #[cfg(test)] mod tests { ... }  # 单元测试（与代码同文件）

tests/
├── integration.rs       # 集成测试（仅 lib crate 能有）
└── common/
    └── mod.rs           # 共享测试工具

benches/
└── user_bench.rs        # 基准测试（criterion）
```

### 7.2 测试约定

```rust
// 单元测试：内联在模块底部
pub fn parse(s: &str) -> Result<Foo> { ... }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_valid() { assert!(parse("x").is_ok()); }

    #[test]
    fn parse_invalid() { assert!(parse("").is_err()); }
}
```

**约束：**
- 单元测试 `#[cfg(test)] mod tests` 放文件底部，测试**私有函数**（`use super::*`）
- 集成测试放 `tests/`，仅测 `pub` API
- 测试工具放 `tests/common/mod.rs` 或独立的 `test-support` crate
- 测试名 `fn method_condition_expected()`：`parse_empty_returns_error`
- 用 `pretty_assertions::assert_eq` 比对复杂结构

### 7.3 测试 crate 独立（大型项目）

databend 模式：测试支持代码独立成 crate

```
crates/
├── mylib/
│   └── src/
└── mylib-test-support/    # 测试工具库
    └── src/
```

```toml
# mylib-test-support/Cargo.toml
[dependencies]
mylib = { path = "../mylib" }
```

---

## 八、依赖管理约束

### 8.1 版本与 features

```toml
# 精确指定（库 crate 最低版本）
serde = "1.0"

# 按需启用 feature
tokio = { version = "1", features = ["rt", "macros"] }

# 不用 default feature 时显式关闭
serde_json = { version = "1", default-features = false, features = ["std"] }
```

### 8.2 约束清单

| 规则 | 说明 |
|------|------|
| 库 crate 用 `^x.y`（默认），不用 `=x.y` | 允许补丁升级 |
| 应用 crate 可用 `=` 锁定 | 确保可复现 |
| 不引入未使用的依赖 | `cargo udeps` / `cargo machete` 检查 |
| feature 默认最小化 | `default = []`，用户按需开 |
| 用 `[workspace.dependencies]` 统一版本 | 避免多版本冲突 |
| 避免 git 依赖（除非必须） | 优先 crates.io，git 依赖影响可复现性 |
| `Cargo.lock` 提交规则 | **二进制项目提交**，**库项目不提交** |

### 8.3 Feature 设计

```toml
[features]
default = ["std"]
std = []
tokio-support = ["tokio"]
json = ["serde", "serde_json"]

[dependencies]
serde = { version = "1", optional = true }
serde_json = { version = "1", optional = true }
tokio = { version = "1", optional = true }
```

**约束：**
- feature 名 `kebab-case`
- `default` 尽量空或最小
- feature 之间可组合：`full = ["a", "b", "c"]`
- `#[cfg(feature = "xxx")]` 守卫代码
- **不要**用 feature 改变 API 语义，只增减功能

---

## 九、构建与 Profile

```toml
[profile.release]
opt-level = 3
lto = "thin"          # 或 "fat" 追求极致性能
codegen-units = 1     # 更好优化，编译更慢
strip = true          # 移除调试符号
panic = "abort"       # 减小二进制（库慎用）

[profile.dev]
opt-level = 0
debug = true
incremental = true

[profile.dev.package."*"]
opt-level = 2         # 依赖库在 dev 下也优化（加速运行）
```

**约束：**
- dev profile 保持快速编译（`incremental = true`）
- release profile 优化优先（`lto`, `codegen-units = 1`）
- 性能敏感依赖在 dev 下也 `opt-level = 2`（如 regex、加密库）

---

## 十、文档与注释规范

### 10.1 文档注释（强制对外 API）

```rust
/// Parses a user ID from a string.
///
/// # Arguments
/// * `s` - A string in the format "user-{digits}"
///
/// # Returns
/// A validated [`UserId`] on success.
///
/// # Errors
/// Returns [`Error::InvalidInput`] if the format is wrong.
///
/// # Example
/// ```
/// let id = mylib::parse_id("user-42")?;
/// assert_eq!(id.as_u64(), 42);
/// ```
pub fn parse_id(s: &str) -> Result<UserId> { ... }
```

**约束：**
- 所有 `pub` 项必须有 `///` 文档注释
- 包含 `# Errors` / `# Panics` / `# Safety` 章节（如适用）
- `# Example` 用可执行 doctest（`cargo test --doc` 运行）
- crate 级文档在 `lib.rs` 顶部用 `//!`

```rust
//! # MyCrate
//!
//! A brief description of what this crate does.
//!
//! ## Quick Start
//! ```
//! use mycrate::process;
//! ```
```

### 10.2 内部注释

```rust
// 单行：说明 WHY，不是 WHAT
let cache = Cache::new(100);  // 100 条上限：经 benchmark 确认最佳

/* 多行注释用于解释复杂逻辑 */
```

---

## 十一、Clippy 与 lint 约束

### 11.1 workspace 级 lint

```toml
# Cargo.toml
[workspace.lints.rust]
unsafe_code = "forbid"        # 禁止 unsafe（除非必要）
missing_docs = "warn"         # 公开 API 必须有文档

[workspace.lints.clippy]
all = "warn"
pedantic = "warn"             # 严格风格
nursery = "warn"
unwrap_used = "warn"          # 禁止 unwrap
expect_used = "warn"
todo = "warn"
dbg_macro = "warn"
print_stdout = "warn"         # 禁用 println!
```

### 11.2 子 crate 继承

```toml
# crates/mylib/Cargo.toml
[lints]
workspace = true
```

**约束：**
- `cargo clippy --workspace -- -D warnings` 在 CI 中强制零警告
- `#![allow(...)]` 仅在文件顶部局部放宽，注明理由
- `cargo fmt --check` 强制统一格式

---

## 十二、常用目录布局模板

### 12.1 应用服务（Web/后端）

```
my-app/
├── Cargo.toml
├── src/
│   ├── main.rs              # 仅启动逻辑
│   ├── lib.rs               # re-export 供集成测试用
│   ├── config.rs            # 配置加载
│   ├── error.rs             # 统一错误
│   ├── app.rs               # 应用组装（依赖注入）
│   ├── domain/              # 领域模型与业务规则
│   │   ├── mod.rs
│   │   ├── user.rs
│   │   └── order.rs
│   ├── service/             # 服务层（编排 domain）
│   ├── infra/               # 基础设施（DB、cache、外部 client）
│   │   ├── mod.rs
│   │   ├── db/
│   │   └── cache.rs
│   ├── api/                 # HTTP/RPC handler
│   │   ├── mod.rs
│   │   ├── route.rs
│   │   └── handler/
│   └── utils.rs
├── tests/
│   └── api_test.rs
├── migrations/              # 数据库迁移
└── config/                  # 配置文件
```

### 12.2 CLI 工具

```
my-cli/
├── Cargo.toml
├── src/
│   ├── main.rs              # clap 参数解析 + 分发
│   ├── cli.rs               # 参数定义
│   ├── commands/            # 每个子命令一个文件
│   │   ├── mod.rs
│   │   ├── add.rs
│   │   └── list.rs
│   ├── core/                # 核心逻辑（可独立测试）
│   └── output.rs            # 输出格式化
└── tests/
```

### 12.3 库 crate

```
my-lib/
├── Cargo.toml
├── src/
│   ├── lib.rs               # facade：pub mod + pub use
│   ├── prelude.rs           # 常用 re-export
│   ├── error.rs
│   ├── types.rs             # 公共类型
│   ├── internal/            # 内部实现（#[doc(hidden)]）
│   │   ├── mod.rs
│   │   └── parser.rs
│   └── ext.rs               # Trait 扩展
├── tests/
│   ├── integration.rs
│   └── common/
└── benches/
```

### 12.4 嵌入式/绑定（FFI/PyO3）

```
my-binding/
├── Cargo.toml
├── src/
│   ├── lib.rs               # #[pymodule] 入口
│   ├── wrapper/             # 绑定层
│   │   ├── mod.rs
│   │   ├── context.rs
│   │   └── dataframe.rs
│   └── core/                # 纯 Rust 核心（不依赖绑定）
└── pyproject.toml           # Python 构建（maturin）
```

---

## 十三、关键约束速查表

| 类别 | 约束 | 严重度 |
|------|------|--------|
| 模块 | `lib.rs` 仅 facade，不写逻辑 | 强制 |
| 可见性 | 默认 `pub(crate)`，对外才 `pub` | 强制 |
| 错误 | 统一 `Error` 枚举 + `Result` 别名 | 强制 |
| 错误 | 禁止生产代码 `unwrap()`/`expect()` | 强制 |
| 命名 | 严格遵循 C-CASE 规范 | 强制 |
| 文档 | 所有 `pub` 项必须有 `///` 文档 | 强制 |
| 测试 | 单元测试 `#[cfg(test)] mod tests` 内联 | 推荐 |
| 测试 | 集成测试放 `tests/` | 推荐 |
| 依赖 | workspace 统一版本 | 强制 |
| Feature | `default` 最小化，可组合 | 推荐 |
| Clippy | CI 零警告 | 强制 |
| 格式 | `cargo fmt --check` | 强制 |
| unsafe | 默认 `forbid`，必要时 `unsafe` block + `// SAFETY:` 注释 | 强制 |
| 异步 | async fn 不跨 `.await` 持有非 Send 类型 | 强制 |
| 锁 | 不在 async 中持 `std::sync::Mutex`，用 `tokio::sync::Mutex` | 强制 |
| Clone | 避免 `Arc<Mutex<Vec<T>>>`，考虑 `tokio::sync::watch`/channel | 推荐 |

---

## 十四、与 databend 的对照参考

以 databend 为标杆案例对照上述规范：

| 规范 | databend 实践 | 评价 |
|------|--------------|------|
| Workspace 多 crate | 35+ crate，`[workspace.dependencies]` 集中管理 | ✅ 标准 |
| crate 命名 | `databend-common-{name}` 前缀 | ✅ 一致 |
| trait 解耦 | `Catalog`/`Table`/`Database` trait + `storages/*` 实现 | ✅ 优秀 |
| 错误统一 | `common-exception` crate + `ErrorCode` 枚举 | ✅ |
| Feature 分层 | `script-udf`/`task-support` 可选集成 | ✅ |
| EE/OSS 分离 | 双 main + `EnterpriseServices` vs `OssLicenseManager` | ✅ 创新 |
| 兼容性 | `META_CHANGE_LOG` + `frozen_api` proc-macro | ✅ 严苛 |
| 文档 | `agents/` + 各 crate README | ✅ 但仓库内架构文档已外迁 |
| lint | `[workspace.lints.clippy]` 配置 | ✅ |
| 遗留 | `datavalues` 与 `expression` 并存 | ⚠️ 迁移未完成 |
| crate 粒度 | 21 个 common crate，部分极小 | ⚠️ 可能过细 |

---

**总结**：Rust 项目架构的核心原则是 **"分层 + trait 解耦 + 严格可见性 + 统一错误"**。目录设计遵循 Cargo 约定（`src/lib.rs` + `src/bin/` + `tests/` + `benches/`），约束层面靠 clippy + fmt + CI 强制执行。大型项目用 workspace 多 crate 隔离关注点，但需注意编译效率和粒度平衡。
