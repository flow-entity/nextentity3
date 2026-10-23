# DESIGN — 架构正文

本文只讲**为什么这样切**与**边界在哪**。规则（必须/禁止）见 [AGENTS.md](../AGENTS.md)，用法见 [USAGE.md](USAGE.md)。

---

## 1. 定位

nextentity 2.x 用 25,741 行同时做了四件事：SQL 生成（JDBC + 方言）、JPA 桥接、自研元模型、Spring 集成。其中三件 Hibernate/Spring 已经提供，重复实现带来的是**双份语义**与**内部 API 耦合**。

本项目只保留一件：**把动态查询意图表达成不可变 AST，再交给单一执行引擎**。

非目标（避免再次膨胀）：实体生命周期与事件、懒加载代理、二级缓存、多方言 SQL 生成、注解处理器生成实体类、自研 Tuple5+。

---

## 2. 模块与依赖

```
api  (零三方依赖)
 ↑
core (Meta / Spec / Compiler / Executor / Repository 接口)
 ↑                    ↑
jpa (Criteria 实现)   (将来) jdbc (SQL 实现)
 ↑
spring (自动配置、事务桥接)

testing / tests-jpa / arch / benchmarks 仅被测试与 CI 使用
```

| 模块 | 放什么 | 不放什么 |
|---|---|---|
| `api` | 用户写查询会 import 的一切：`Path`、`PathRef`、`EntityPath`、`Predicate`、`Op`/`Operation`、步骤接口、`Slice`、`Tuple2`、`LockMode` | 任何 `jakarta.*`、`org.hibernate.*`、`org.springframework.*`、任何工具类 |
| `core` | `Meta`（属性解析）、`Spec`（AST）、`Compiler<S>`/`Executor<S>`（接缝）、`PersistExecutor`、`EntityOperationsFactory`/`EntityOperations` | JPA/Criteria 类型、Spring 类型、SQL 字符串 |
| `jpa` | `JpaMeta`、`JpaCompiler`、`JpaExecutor`、`JpaPersistExecutor`、`JpaEntityOperationsFactory` | Spring 类型（由 `spring` 模块桥接） |
| `spring` | `NextEntityAutoConfiguration`、属性绑定、`@Bean` 装配、`AbstractRepository`（构造器注入 `EntityOperationsFactory` + `query()` + `insert(T)`） | 查询逻辑 |

> `AbstractRepository` 放在 `spring` 而不是 `core`：它是 Spring 侧给消费方的仓储基类，且存量调用方（point-mall `AppVersionRepository`）直接 `import io.github.nextentity.spring.AbstractRepository`；放进 `core` 会迫使消费方改文件。
| `arch` | ArchUnit 规则：模块循环、core 包边界、`spec` 纯净与不可变、时间/随机注入、接口宽度 | 任何业务逻辑；只有测试代码 |

依赖方向与零依赖要求是硬约束，见 [AGENTS.md](../AGENTS.md)「依赖方向单向」；由各模块 `maven-enforcer-plugin` 的 `bannedDependencies`（编译期）与 `nextentity-arch` 的 ArchUnit 规则双重校验。

---

## 3. 分层与数据流

一次查询经过四段，每段只做一件事：

```
调用（链式 DSL）
   │  builder：把方法引用（`Path#refs()` → `SerializedLambda`）与 api 的 `Op` 值解析成 Spec 节点
   ▼
Spec（不可变 AST，纯值对象）
   │  Compiler<S>：Spec → 后端语句（JPA 为 Criteria 持有者）
   ▼
S  （后端语句）
   │  Executor<S>：执行 + 结果装配
   ▼
结果（实体 / Tuple / DTO / Slice / 计数）
```

| 层 | 职责 | 边界判据 |
|---|---|---|
| `meta` | 属性名 → JPA 属性（`JpaMeta` 按实体类型缓存）；不含方法引用解析 | 只认 JPA 映射注解，不认业务语义 |
| `builder` | 方法引用 → 属性名（`PathRefs` 解析 `SerializedLambda`，每次调用解析、无缓存）；步骤接口 → `Spec` 节点；可选条件（`eqIfNotNull` / `containsIfNotEmpty`）在此短路 | 不产生 SQL/Criteria，不做类型转换以外的逻辑 |
| `spec` | 不可变 AST：条件 `Comparison`/`Between`/`In`/`NullCheck`/`Logical`/`Constant`；值表达式 `Operand`（`Literal`/`AttributePath`/`AggregateOperand`/`ArithmeticOperand` + `BinaryOperator`）；投影 `Projection`/`AggregateProjection`/`PathProjection`/`ComputedProjection`/`ValueSelection`/`EntitySelection`；枚举 `AggregateFunction`/`ComparisonOperator`/`LogicalOperator`；`Ordering`/`Window`/`QuerySpec`/`Selection` | 不含任何后端类型，可序列化、可断言、可复用 |
| `compile` | `Spec → S`，唯一渲染出口 | 一个 `switch` 分支处理一个节点，穷尽性由编译器保证 |
| `exec` | 执行、分页计数、结果装配（实体/Tuple/DTO）、锁模式 | 不解释条件语义 |
| `repo` | CRUD、批量、乐观锁、事务边界 | 不拼查询 |

**关键手段**：`Spec` 用 `sealed interface` + `record`，编译器用 `switch` 模式匹配。新增节点时 Java 25 的穷尽性检查会强制补齐所有分支——这是相对 2.x 反射式 AST 最实质的改进。

---

## 4. 兼容契约

`api` 包是对外契约，其余包为内部实现（`@Internal`）。冻结清单：

| 稳定（承诺兼容） | 内部（不承诺） |
|---|---|
| `Path` / `PathRef` / `EntityPath` | `core.meta.*` 实现类 |
| 步骤接口：`EntityQuery`、`BaseWhereStep`、`Whereable`、`SelectStep`、`GroupByStep`、`OrderByStep`、`SliceStep`、`LockStep`、`Collector` | `core.spec.*` 全部节点 |
| 谓词算子（`eq`/`in`/`gt`/`between`/`contains` 家族、`*IfNotNull`/`*IfNotEmpty`） | `core.compile.*`、`core.exec.*` |
| 列对列比较（`ColumnComparison`）、表达式算术（`NumberArithmetic`/`NumberValue`）、逻辑组合（`LogicalOps`：`not` + n 元 `and`/`or`）、`HavingStep` | `core.spec.Operand` 家族（`Literal`/`ArithmeticOperand`/`AggregateOperand`）、`jpa.compile.JpaOperands` |
| `Slice` / `Tuple2`（也是 `Tuple`）/ `Tuple` / `AggregateValue` / `LockMode` / `Op`+`Operation` | `jpa.*`、`spring.*` |

微调预算与破坏性变更的前置条件见 [AGENTS.md](../AGENTS.md)「公开 API 是契约」。

**已知的待收敛项**（实施时按此处理，均不破坏现有调用点）：

| 项 | 处理 |
|---|---|
| `BaseWhereStep<T,U>` 的第二类型参数恒等于第一（存量代码写 `<Order,Order>`） | **不实施**：`WhereStep<T>` 别名需要额外一层接口，而存量调用点全部容忍现状（0 处显式声明该类型为变量类型），收益为零 |
| 92 个 `*IfNotNull` / `*IfNotEmpty` | **已否决**：改名为 `*IfPresent` 要动 point-mall 154 处 `eqIfNotNull` 等调用点（破 D2/D3），且新名字并不更准确；保留 2.2.3 命名 |
| `Tuple5`~`Tuple10` | **已被 `model.Tuple` 取代**：3 列及以上统一返回 `Tuple`（`get(int)`/`size()`），不再提供 `get4()`…`get9()` 这类具名元组；2 列仍是 `Tuple2`（`Tuple2` 也实现 `Tuple`） |
| `lock(LockModeType)` 把 `jakarta.persistence` 带入 `api` | 已收敛：`api` 只提供自研 `LockMode` + `lock(LockMode)`，2.2.3 的 `lock(jakarta.persistence.LockModeType)` 重载已删除，`api` 回归零三方依赖（消费方 3 处调用点改用 `LockMode`） |
| `EntityPath.of(String)` 等类型不安全重载 | 已移除 |

---

## 5. 测试策略

四层，越往上越便宜，数量应呈金字塔：

| 层 | 测什么 | 依赖 | 位置 |
|---|---|---|---|
| 0. 架构门禁 | 模块循环、`core` 包边界、`spec` 纯净与不可变、时间/随机注入 | 无 | `nextentity-arch` |
| 1. Spec 单测 | builder 调用 → AST 结构（对象断言） | 无 | `core` 的 `src/test`（`api` 无测试源码：零依赖口径不引入 test-scope 依赖，值层断言放 `nextentity-testing`） |
| 2. 编译快照 | Spec → SQL（H2 + `StatementInspector` 抓取，逐字断言） | H2 | `nextentity-tests-jpa` |
| 3. 一致性 | 能力而非 SQL 文本：条件/分页/聚合/投影/写入/乐观锁 | 仅 `api`+`core` 接口 | `nextentity-testing` 抽象基类，由 jpa 子类继承 |
| 4. 集成 | 真实方言、`@Convert`、并发乐观锁 | Docker | `nextentity-tests-jpa` |

编写约定：

- 新增能力 = 1 条快照 + 1 条一致性用例，缺一不算完成（见 [AGENTS.md](../AGENTS.md)）。
- 一致性基类内不得出现 `EntityManager`、`Criteria`、SQL 字样，防止被某一后端绑架。
- 命名与运行方式见 [AGENTS.md](../AGENTS.md)「验证规范」。

---

## 6. 第二后端（JDBC）接缝

现在**不实现**，但接口必须为它留位。八条接缝：

1. `Spec` 不含后端类型（纯值对象）。
2. `Compiler<S>` 泛型化：JPA 产出 Criteria 持有者，JDBC 将来产出 `{sql, bindings}`。
3. `Executor<S>` 与 `Compiler<S>` 成对出现，终止符都在接口层。
4. `Meta` 接口化，JPA 实现内部走 `EntityManager.getMetamodel()`；JDBC 实现将来走反射/注解扫描。
5. `LockMode` 用自研枚举，不引 `jakarta.persistence`。
6. `PersistExecutor` 接口化（insert/update/delete/批量）。
7. 事务边界由调用方持有：point-mall 写入靠 `@Transactional`，id-generator 用自己的 `TransactionTemplate`。**不保留 `TransactionOperations` 接口**——它曾以「为第二后端留位」的理由存在，但零实现、零调用，属于「不写没有调用点的抽象」要清掉的那类（第二后端真的出现时随它一起引入，而不是提前摆一个空接缝）。`core`/`jpa` 仍不得依赖 `org.springframework.transaction`。
8. `nextentity-testing` 的一致性基类只依赖接口，新后端必须通过同一套。

准入条件（三条同时满足才允许新增 `nextentity-jdbc`）：出现 Hibernate 无法满足的明确需求；Criteria native query / `@Query` 原生 SQL 逃生口顶不住（`spec` 目前没有原生 SQL 片段节点，真要加就按 §8 的做法：新增 `sealed` 子类型，让编译器的 `switch` 穷尽性报错逼出全部分支）；能通过一致性测试。

---

## 7. 决策与否决

| 决策 | 理由 | 被否决的方案 |
|---|---|---|
| 单一引擎，JPA 优先 | 生产消费方就是 Hibernate；重复实现第二引擎是 2.x 主要体积来源 | 同时维护 JDBC + JPA 双后端 |
| 保留方法引用，增量提供静态元模型 | 存量代码零迁移；元模型提供编译期安全 | 一次性改为元模型（要改 87 个仓储） |
| 自研 `Spec` AST（约 12 节点），不直接拼 Criteria | 可单测、可快照、可复用，且是后端接缝的前提 | builder 里直接拼 Criteria（不可测、锁死后端） |
| 分页返回自有 `Slice` | 消费方 `Pageable` 已依赖该类型；且能表达「不计数」 | 直接返回 Spring `Page` |
| 一致性测试独立成模块并发布 | 让后端实现与消费方共用同一套契约测试 | 把基类塞进各模块 `src/test` |
| 不建 ADR 目录，决策写在本文 | 同一决策只有一个出处 | 每决策一篇 ADR（内容重叠、易漂移） |
| 不做实体事件与拦截器 | 用 Spring 事件 / JPA 生命周期回调即可 | 自研事件总线 + CGLIB 代理 |
| 引入 `core.spec.Operand` 值表达式层（`Comparison` 的右值从 `Object` 升级为 `Operand`） | 列对列比较（`a > b`）与表达式算术（`a mod 3 = 0`）要求比较两侧都可能是表达式；`Object` 只装得下字面量，值类型的分派只能推迟到运行期 | ① 在 `Comparison` 上加 `value` + `expression` 两个字段/两套重载：渲染端要写两条分支，`sealed` 穷尽性只覆盖节点、覆盖不到"值形态"；② 保留 `Object` 并在 `JpaPredicates` 里 `instanceof` 判定：类型错误延迟到运行期，且新增一种值形态时编译期不报错 |
| 推翻「`Aggregates` 不加 `MIN`/`AVG`」（原结论：两者在 point-mall 0 命中） | 原结论是**调用点驱动**的（当时 `.min(` / `.avg(` 全库 0 命中），本次把 `min`/`avg` 纳入第一梯队能力面：`core.spec.AggregateFunction` 增加 `MIN`/`AVG`，`api.Aggregates` 暴露 `min()`/`avg()`，`jpa.compile.JpaOperands` 用同一个 `switch` 渲染 | 原理由所在处：`core.spec.AggregateFunction` 的类注释里「不加 MIN/AVG：avg 0 命中」一段——**已被本次取代**（该注释已改写为"六种聚合都在能力面内"）。保留的约束只有一条：聚合结果 `AggregateValue` 不接聚合方法，`max().max()` 编译期即不可写 |

---

## 8. 新能力落位图

| 要加什么 | 落在哪 | 是否改 `api` |
|---|---|---|
| 新谓词（如 `regexp`） | `spec` 加节点 + `compile` 加分支 + `api` 加算子 | 是（增量） |
| 新值形态（表达式/聚合结果参与比较） | `core.spec.Operand` 加实现 + `jpa.compile.JpaOperands` 加 `switch` 分支（唯一渲染点） | 是（增量：`ColumnComparison` 等） |
| 新聚合函数 | `core.spec.AggregateFunction` 加常量 + `JpaOperands` 的 `switch` 分支 | 否（`api.Aggregates` / `NumberPath` 已是入口） |
| 分组后过滤（`having`） | `core.spec.QuerySpec.having` + `jpa.compile.JpaQueryBuilder` | 是（增量：`HavingStep`） |
| 新终止符（如 `keyset`） | `exec` 加方法 + `api` 加步骤接口 | 是（增量） |
| 新投影形态（record/DTO） | `exec` 的装配器 | 否 |
| 新后端（JDBC） | 新模块实现 `Compiler`/`Executor` | 否 |
| 新方言差异 | 后端模块内部 | 否 |
