# CHANGELOG

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

破坏性变更必须在对应条目里写明「为什么必须破」与迁移方式（见 [AGENTS.md](AGENTS.md)「公开 API 是契约」）。

## [Unreleased]

### Added

- 仓库骨架：`api` / `core` / `jpa` / `spring` / `testing` / `tests-jpa` / `bom` 七个模块与依赖方向约束。
- 设计正文 `docs/DESIGN.md`、使用正文 `docs/USAGE.md`。
- 本地门禁：Spotless(Palantir) + Checkstyle + PMD + SpotBugs + JaCoCo + Enforcer + ArchUnit + japicmp(API 兼容)。
- 新增 `nextentity-arch` 模块：ArchUnit 规则集中到一处，覆盖模块循环、`core` 包边界、`spec` 纯净与不可变、时间/随机注入、接口宽度。原 `core` 内的 `ArchitectureTest` 因 classpath 看不到 `jpa`/`spring` 而迁出。
- 静态检查补强：PMD 增加类级 NCSS、方法数、参数数、圈复杂度、耦合度、过宽 catch；Checkstyle 增加「catch 后返回默认值」拦截。

### Changed

- 无。

### Removed

- 相对 nextentity 2.x 不再提供：JDBC 第二引擎与方言、自研元模型、实体事件总线、CGLIB 仓储代理、`Tuple5`~`Tuple10`。

### Migration

从 nextentity 2.x 迁移时：

1. `io.github.nextentity.api.*` 包名与主要类型保持不变，存量查询代码无需改动。
2. 若曾直接依赖 `core.*` / `spring.*` 内部类（如 `DefaultMetamodel`、`SimpleExpressionImpl`），需改为通过 `api` 完成同一件事——这些类型已标记 `@Internal`。
3. 使用 JDBC 后端的工程不在本次支持范围内（见 `docs/DESIGN.md` 第 6 节）。
