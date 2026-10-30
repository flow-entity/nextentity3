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
 ↑                        ↑
jpa (Criteria 实现)       jdbc (纯 JDBC 实现：H2 / MySQL / PostgreSQL / SQL Server)
 ↑
spring (自动配置、事务桥接)

testing / tests-jpa / tests-jdbc / tests-legacy / arch 仅被测试与 CI 使用（bom 只做版本对齐）
```

`jpa` 与 `jdbc` 是 `core` 接缝的两个平行实现，**互不依赖**：`jpa` 走 Hibernate/Criteria，`jdbc` 走 `java.sql` + 手写 SQL。`spring` 目前只桥接 `jpa`；`jdbc` 由使用方直接 `new JdbcEntityOperationsFactory(dataSource)` 装配，不经过 Spring。`jdbc` 的方言是**可替换接缝**而不是硬编码分支：内置 H2 / MySQL / PostgreSQL / SQL Server 四个实现，三方（或自研）数据库只需实现 `JdbcDialect` 并 `new JdbcEntityOperationsFactory(dataSource, customDialect)`，窗口/锁/生成键/`avg`/`exists`/字符串函数等渲染差异全部由接口的 9 个实例钩子表达（见 §6）。

| 模块 | 放什么 | 不放什么 |
|---|---|---|
| `api` | 用户写查询会 import 的一切：`Path`、`PathRef`、`EntityPath`、`Predicate`、`Op`/`Operation`、步骤接口、`Slice`、`Tuple2`~`Tuple6`、`ProjectionMapper`/`ProjectionMappers`、`LockMode` | 任何 `jakarta.*`、`org.hibernate.*`、`org.springframework.*`、任何工具类 |
| `core` | `Meta`（属性解析）、`Spec`（AST）、`Compiler<S>`/`Executor<S>`（接缝）、`PersistExecutor`、`EntityOperationsFactory`/`EntityOperations` | JPA/Criteria 类型、Spring 类型、SQL 字符串 |
| `jpa` | `JpaMeta`、`JpaCompiler`、`JpaExecutor`、`JpaPersistExecutor`、`JpaEntityOperationsFactory` | Spring 类型（由 `spring` 模块桥接） |
| `jdbc` | `JdbcMeta`、`JdbcCompiler`（`QuerySpec → {sql, bindings}`）、`JdbcDialect`（内置 `H2Dialect`/`MySqlDialect`/`PostgresDialect`/`SqlServerDialect` + `of(DataSource)` 探测）、`JdbcExecutor`、`JdbcPersistExecutor`、`JdbcEntityOperationsFactory` | Spring 类型、JPA/Hibernate 类型（只反射 `jakarta.persistence` 注解）；`jpa` / `testing`（ArchUnit `JDBC_IS_A_BACKEND_ONLY` 执法） |
| `spring` | `NextEntityAutoConfiguration`、属性绑定、`@Bean` 装配、`AbstractRepository`（构造器注入 `EntityOperationsFactory` + `query()` + `insert(T)`） | 查询逻辑 |
| `arch` | ArchUnit 规则：模块循环、core 包边界、`spec` 纯净与不可变、时间/随机注入、接口宽度、后端反向依赖（`jpa`/`jdbc` 不得反向依赖别的实现） | 任何业务逻辑；只有测试代码 |

> `AbstractRepository` 放在 `spring` 而不是 `core`：它是 Spring 侧给消费方的仓储基类，且存量调用方（point-mall `AppVersionRepository`）直接 `import io.github.nextentity.spring.AbstractRepository`；放进 `core` 会迫使消费方改文件。

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
   │  Executor<S>：执行 + 后端行成形（多列 `Object[]` / 单列标量）
   ▼
结果（实体 / Tuple / DTO / Slice / 计数）
```

执行器交出后端原始行、不做任何行成形；行成形与装配由 `builder` 的 `RowAssembler` 负责——把原始行装成 `Tuple` 再交给 `ValueSelection#rowMapper()`。`select(...)` 的 1/2/3 列与 `project(...)` 走**同一条**装配路径，区别只在选中哪个映射器（`ValueMappers` 的内置映射器 vs 调用方映射器）；行的 cell 数与投影列数不等时当场抛 `IllegalStateException`，见下表 `builder` 行。

| 层 | 职责 | 边界判据 |
|---|---|---|
| `meta` | 属性名 → JPA 属性（`JpaMeta` 走 `EntityManager.getMetamodel()`；`JdbcMeta` 走 `jakarta.persistence` 注解反射 + 字段类型），各自按实体类型缓存；不含方法引用解析 | 只认 JPA 映射注解，不认业务语义 |
| `builder` | 方法引用 → 属性名（`PathRefs` 解析 `SerializedLambda`，每次调用解析、无缓存）；步骤接口 → `Spec` 节点（表达式 → 投影项的唯一转换点是 `ValueProjections`）；可选条件（`eqIfNotNull` / `containsIfNotEmpty`）在此短路；后端原始行 → 结果对象（`RowAssembler`：包 `Tuple` 后一律经 `ValueSelection#rowMapper()` 装配，`select(...)` 的内置映射器在 `ValueMappers`） | 不产生 SQL/Criteria，不做类型转换以外的逻辑 |
| `spec` | 不可变 AST：条件 `Comparison`/`Between`/`In`/`NullCheck`/`Logical`/`Constant`；值表达式 `Operand`（`Literal`/`AttributePath`/`AggregateOperand`/`ArithmeticOperand` + `BinaryOperator`）；投影 `Projection`/`AggregateProjection`/`PathProjection`/`ComputedProjection`/`ValueSelection`/`EntitySelection`；枚举 `AggregateFunction`/`ComparisonOperator`/`LogicalOperator`；`Ordering`/`Window`/`QuerySpec`/`Selection` | 不含任何后端类型，可序列化、可断言、可复用 |
| `compile` | `Spec → S`，唯一渲染出口（`jpa` 产出 Criteria，`jdbc` 产出 `{sql, bindings}`） | 一个 `switch` 分支处理一个节点，穷尽性由编译器保证 |
| `exec` | 执行、分页计数、锁模式；**只交后端原始行**（单列可为裸值或长度 1 的 `Object[]`，多列是**长度等于**投影列数的 `Object[]`；长度不符——多给或少给——装配层抛 `IllegalStateException`） | 不解释条件语义，**不做行成形/装配**（装配规则含「投影了几列」，只有 `builder` 知道） |
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
| `Slice` / `Tuple` 与其 record 实现 `Tuple2`~`Tuple6` / `AggregateValue` / `ProjectionMapper`+`ProjectionMappers` / `LockMode` / `Op`+`Operation` | `jpa.*`、`jdbc.*`、`spring.*` |

微调预算与破坏性变更的前置条件见 [AGENTS.md](../AGENTS.md)「公开 API 是契约」。

**这条红线现在有执法者**（2026-10 起）：`nextentity-api/pom.xml` 的 `api-compat` profile 用 `nextentity-api:${japicmp.oldVersion}` 作基线（默认值 = 上一发布版；3.0.0 起 api 独立发布，当前基线 3.0.0；对照 2.2.3 的历史报告要同时传 `-Djapicmp.oldArtifactId=nextentity-core`，那时 `io.github.nextentity.api.*` 的类在 core 的 jar 里），并开了 `breakBuildBasedOnSemanticVersioning`。该开关的语义是「主版本号上跳就允许破坏」：`3.0.0-SNAPSHOT ← 2.2.3` 这种跃迁放行（历史实测 624 处不兼容仍 `BUILD SUCCESS`），而 `3.1.0 ← 3.0.0` 这种次版本跃迁下任何破坏都会让 `verify` 判红——所以「破坏性变更每版 ≤ 5 处」从 3.0.0 之后的下一个版本起真正生效，要破契约必须发 `x.0.0`（例外口径见上文「主版本跃迁」）。纯加性恢复按语义化版本属 MINOR，故本版是 **3.1.0** 而不是补丁版。本地实测：在 3.0.0 契约面的开发版本上 + 默认基线 3.0.0 → `BUILD SUCCESS`（报告 383 条 `UNCHANGED`、无破坏条目）；普通 `verify` 走 `<skip>` 不跑这个 goal，只有 `-Papi-compat` 才跑。

门禁的两个前提写在这里免得下一个人踩：（1）只在 pom 版本 > 基线时有意义——在刚发版的 HEAD（版本 = 基线）上，japicmp 会拿本模块 `target/` 里的 jar 与自身比较，报 `Versions of archives indicate no API changes but found API changes`，先 bump 版本再跑；（2）旧 jar 里若没有 `io.github.nextentity.api.*`（基线 artifact 或版本配错），看到的全是「新增」，天然无不兼容，同样静默变成空检查——基线的 artifact 与版本必须都对。

**已知的待收敛项**（实施时按此处理，均不破坏现有调用点）：

| 项 | 处理 |
|---|---|
| `BaseWhereStep<T,U>` 的第二类型参数恒等于第一（存量代码写 `<Order,Order>`） | **不实施**（§7 CUT-26）：`WhereStep<T>` 别名需要额外一层接口，而存量调用点全部容忍现状（0 处显式声明该类型为变量类型），收益为零 |
| 92 个 `*IfNotNull` / `*IfNotEmpty` | **已否决改名**（§7 CUT-25）：改名为 `*IfPresent` 要动 point-mall 154 处 `eqIfNotNull` 等调用点（破 D2/D3），且新名字并不更准确；保留 2.2.3 命名 |
| `Tuple3`~`Tuple10` | **只有 `Tuple2`~`Tuple6`，7 列以上不提供**（§7 CUT-24）：三列由 `select(...)` 固定重载给 `Tuple3`，四~六列由 `ProjectionMappers.select(...)` 给 `Tuple4`~`Tuple6`。封顶六列是 `ExcessiveParameterListSix`（参数 ≤6）的直接后果；七列以上用自定义 `ProjectionMapper`。具名元组与 `Tuple` 统一是 record，实现不再放在 core |
| `lock(LockModeType)` 把 `jakarta.persistence` 带入 `api` | **已收敛**：`api` 只提供自研 `LockMode` + `lock(LockMode)`，2.2.3 的 `lock(jakarta.persistence.LockModeType)` 重载已删除，`api` 回归零三方依赖（消费方 3 处调用点改用 `LockMode`） |
| `EntityPath.of(String)` 等类型不安全重载 | **已移除**（§7 CUT-28） |
| `selectDistinct(Class)` | **不单独提供**（§7 CUT-27）：`SelectStep` 加 `select(Class)` 后正好 6 个方法，撞 `API_INTERFACES_STAY_NARROW`（接口方法 ≤6）。用 `distinct().select(Class)` 表达；3.1.0 的 examples 移植按「只改调用形态、不弱化断言」处理 |
| `Collector` 上 `lock(...)` 之后的分页 | 3.1.0 起以 `Collector` 的 `default` 方法提供（§7 CUT-10），`lock(...)` 返回类型不动——补能力是加性补充，不是重划契约 |

---

## 5. 测试策略

五层防线，越往下越便宜、数量越多（金字塔底在 L0）。便宜层能挡住的问题，不允许流到贵的层。下文用 L0–L4 指代。

| 层 | 测什么 | 判定标准 | 依赖 | 位置 |
|---|---|---|---|---|
| 0. 架构门禁 | 模块循环、`core` 包边界、`spec` 纯净与不可变、时间/随机注入 | 编译期或测试期变红；同类问题不允许留到运行期 | 无 | `nextentity-arch` |
| 1. Spec 单测 | builder 调用 → AST 结构（对象断言） | 结构等价：断言节点类型与字段值，不看 SQL | 无 | `api` 与 `core` 的 `src/test`（api 钉线格式、core 钉 Spec 翻译，分工见下「api 值层断言的落点」） |
| 2. 编译快照 | Spec → SQL（JPA 用 H2 + `StatementInspector` 抓取；JDBC 直接断言 `JdbcCompiler` 产出的 `{sql, bindings}`） | 语句文本的确定性片段必须出现；期望值不得写进方言的引号、别名、大小写 | H2 | `nextentity-tests-jpa`（JPA）、`nextentity-jdbc/src/test`（JDBC） |
| 3. 一致性 | 能力而非 SQL 文本：条件/分页/聚合/投影/写入/乐观锁 | 真库执行结果 vs 独立算出的期望；用例不引用任何后端类型 | 仅 `api`+`core` 接口 | `nextentity-testing` 抽象基类，由 jpa / jdbc 子类各自继承 |
| 4. 集成 | 真实方言、`@Convert`、并发乐观锁 | 同一用例在各方言下结果集完全相同（载体：`nextentity-tests-jpa` 与 `nextentity-tests-jdbc` 的 `*IntegrationTest`，跑真 MySQL 容器） | Docker | `nextentity-tests-jpa`、`nextentity-tests-jdbc` |

L2 只证明「语句下发正确」，不证明「参数绑定正确」：`SqlRecorder` 只记语句文本，绑定顺序与取值抓不到（`StatementInspector` 的接口上就没有绑定值）。这一半证据由 L3（真库执行结果）兜底；文本相同而结果唯一会变的那一处——`lock(...).single()` 里主键与 `fetch first ? rows only` 两个 `Long` 占位符——已由 `ItemQueryEntrySnapshotTest#pessimisticLockSingleReturnsTheFilteredRow` 用「取回的行就是 where 里那个 id」补上。剩余的"绑定顺序写反但结果仍相同"在语义上不可观测，不计为缺口。

L4 的载体在 `nextentity-tests-jpa`：H2 用例走 surefire，容器用例（`*IntegrationTest`）走 failsafe，两条通道按类名显式分开（见下「通道拆分」）。容器侧用 `org.testcontainers:mysql` 起一个 `mysql:8.0.18`，一个 JVM 一个容器、所有容器用例共享，接的是**同一批一致性基类**——所以「跨方言结果集相同」是逐行比出来的，不是另写一套 MySQL 专用断言。真 MySQL 的另一处出现是 `nextentity-tests-legacy`，需手动开 `-Ddbc=h2,mysql`，与 L4 无关。

**api 值层断言的落点**：`nextentity-api` 有自己的 `src/test`，但**只放它自己的线格式用例**。该模块的 enforcer 规则 `enforce-api-is-dependency-free` 只禁发布作用域（`compile` / `runtime` / `provided` / `system`），`test` 作用域放行，于是 `junit-jupiter` / `assertj-core` 能进测试类路径而不进 jar。api 的用例断言 `Op` 的四件套线格式（`operation()` 等于哪个常量、`arguments()` 的精确内容、`subject()` 为 `null` 还是哪个表达式、`refs()` 逐级 `apply(实体)` 取到的值），不依赖任何后端；「值 → `core.spec` 节点」的翻译证据仍在 `core` 的 `ApiOperatorAssemblyTest`，用同包可见的 `OpInterpreter` 把 api 值读成 Spec 节点、断言结构整体相等。两层各管一段：api 管「拼出来的线格式对不对」，core 管「翻译成 Spec 对不对」。

这条门禁以前是「禁一切依赖（含 `test` 作用域）」，后果是 api 长期没有测试源码——而 PIT 有条硬门槛：模块没有 `src/test` 就打印 `Project is either no tests or no production code.` 并直接跳过，连 `failWhenNoMutations=true` 都不触发。收窄作用域是为了让 api 能自己跑测试与变异；红线的实质（**发布产物零三方依赖**）没有松动，`compile` 作用域仍被拦（实测加 `org.slf4j:slf4j-api` 依然 `BUILD FAILURE`）。

编写约定：

- 新增能力 = 1 条快照 + 1 条一致性用例，缺一不算完成（见 [AGENTS.md](../AGENTS.md)）。
- 一致性基类内不得出现 `EntityManager`、`Criteria`、SQL 字样，防止被某一后端绑架。
- 命名与运行方式见 [AGENTS.md](../AGENTS.md)「验证规范」。

**oracle 分层**（决定「期望值从哪来」，是这一节最容易被做错的地方）：

| oracle 形态 | 能证明什么 | 用在哪 |
|---|---|---|
| 手算常量（固定夹具 + `containsExactlyInAnyOrder(APPLE_ID, CHERRY_ID)`） | 只钉**已知语义**：`between` 是闭区间、`notBetween` 与 `between` 互补且两侧都不含 NULL 行、`a = b` 与 `a <> b` 都不命中 NULL 行 | 语义细节、边界、容易被「优化」掉的坑 |
| 差分（真库执行 vs 内存独立计算） | 组合正确性：同一份 Spec 在两条独立实现路径上算出的行集必须一致 | 条件/排序/分页/聚合的组合面积 |
| 跨方言对拍 | 方言无关性：同一用例在 H2 与真实 MySQL 上结果集逐行相同 | L4（`*IntegrationTest`，同一批一致性基类接两套装置） |
| 变形关系（metamorphic / TLP） | **关系**成立性：`Q(p)` 必须等于 `Q(p∧c)`、`Q(p∧¬c)`、`Q(p∧c IS NULL)` 三桶之并，不需要任何人知道期望值 | 条件组合语义（`AND`/`OR`/`NOT` 的分组与 `NULL` 相互作用）、分页重组 |

为什么手算不能当主力：4 行固定夹具下手算可行，组合面积一扩大就不可维护——每个新能力只能挑一个最顺的例子手算一条，边界组合没人算，最终结果是测试只覆盖 happy path，而 oracle 本身成了又一份需要维护的「实现」。所以手算常量只用来钉已知语义，正确性证据必须来自差分或跨方言对拍。

**差分 oracle 台账**（哪些家族已有差分、哪些还只有手算常量——这张表决定「补证据该往哪补」，也决定新能力落地时必须同时加哪一格）：

| 家族 | 差分基类（`nextentity-testing/src/main`） | 格数 | 内存侧怎么算 |
|---|---|---|---|
| 谓词：比较/范围/集合/空值/枚举/文本 | `PredicateDifferentialTest` | 40 + 1 条变长≡集合 | 独立 Kleene 三值求值；变长重载另与集合形态逐元素对拍 |
| 谓词组合（属性测试） | `PredicateFuzzDifferentialTest` | 60 seed + 3 条配额守卫 | 概念树 → 两套互不相邻的渲染器（DSL / 内存求值） |
| 聚合：count/sum/avg/min/max | `AggregateDifferentialTest` | 33 + 2 条语义 | 从夹具逐组累加 |
| 算术：链式 `+ - * / %` | `ArithmeticDifferentialTest` | 10 | 按列类型逐行算，除零按方言拆两格 |
| `having` | `HavingDifferentialTest` | 16 | 分组后按聚合值过滤 |
| 空结果集上的全部终止符 | `EmptyResultDifferentialTest` | 34 | 空集语义表 |
| 排序（单/多键、升降、NULL 位置） | `SortDifferentialTest` | 16 | 独立比较器 |
| 分页与切片 | `PaginationDifferentialTest` | 55 | 内存切片 + 行数 |
| 投影/元组/映射器/distinct | `ProjectionDifferentialTest` | 19 | `LinkedHashSet` 去重 + 列序表；六列按 `Tuple6` 实参位次装配 |
| 一次查询只有一份投影（USAGE §6.1 契约 3） | `ProjectionReplacementDifferentialTest` | 3 | 后一次调用整体覆盖前一次，期望按最后一次的列集算 |
| 关联嵌套路径 | `AssociationDifferentialTest` | 42 | 独立实现 LEFT JOIN + 三值逻辑 |

差分覆盖不到的，只有下面三类。**这是登记在案的豁免清单**：按 AGENTS.md「每个能力至少 1 条快照 + 1 条一致性用例」核对时，落在清单里的按「证据偏弱」记，不按红线违反记——判据是它们都提供了**替代 oracle**，而不是没证据：

- **写侧往返**（批量写、`@Convert`、`@Version`）：期望来自「写进去什么就该读回什么」的**声明式**期望，不是差分——因为这条路径的 oracle 是数据库本身，再写一份内存实现在这里只会是又一份存储层。
- **悲观锁**：结果依赖并发与锁等待，单进程里没有可复现的差分 oracle；证据是「5 种 `LockMode` 逐条钉住放行/拒绝」+ SQL 骨架，属 §5 允许的「手算常量只钉已知语义」。
- **spring 装配**：oracle 是容器本身（真 `EntityManagerFactory` + H2），不涉及行集计算。

手算常量的用例并没有被删——它们钉的是「闭区间」「NULL 行归属」「算子互补」这类**已知语义**，与差分互补，两者都要留。判据很简单：**这条用例红了，我能说出是期望值错还是生产错吗？** 说不出就说明 oracle 没分层。

**变形关系（metamorphic / TLP）**：差分 oracle 有一个它自己答不出的问题——**内存 oracle 写错时，真库与内存会一起错**，套件照样全绿。变形关系绕开这个闭环：它不比对「期望值 vs 实际值」，只断言两条查询之间**全称成立的关系**，因此不依赖任何人算对答案。

- 落点：`nextentity-testing/src/main` 的 `MetamorphicTestBase`（夹具参数与断言工具）→ `MetamorphicPagingTest`（分页重组 2 格 + 投影对齐 1 格 + 倒序互逆 1 格）→ `MetamorphicQueryTest`（TLP 等式 3 格 + 分区自我检查 1 格 + 幂等/中性元 3 格 + 夹具质量门 2 格），共 **13 格**。后端子类是 `ItemMetamorphicQueryTest`（H2）与 `MySqlItemMetamorphicQueryIntegrationTest`（真 MySQL），只换接线，断言只有一套。
- 夹具自检是必需的，不是装饰：`offByOnePartitionIsNotIgnored()` 故意把分区写成「`>` 代替 `>=`」，断言等式**必须破**、且漏掉的必须**恰好**是边界行（边界值取自夹具实际出现的中位数，所以它必然存在，且用例没有假设「边界只有一行」）。没有这一条，关系在夹具退化时恒成立，三条 TLP 用例就成了摆设。
- 夹具质量门有两道，缺第二道会留下一个看不见的洞：`fixtureSupportsEveryPartitionBucket()` 只校验**逐列**边缘分布（每列都有大于/小于/等于边界与 `null` 的行）；`degenerateCellsAreExactlyTheKnownOnes()` 才校验**逐格**——遍历 6 个基谓词 × 3 个分区的 18 格，把「至少一桶为空」的格钉成常量清单。18 格里当前有 4 格退化，成因要分开说，笼统写成「逻辑必然」是错的：这 4 格的**共同**成因是结构性的——四个基谓词都不接受 `NULL`，所以 `p ∧ (列 IS NULL)` 桶恒空，换任何夹具都消不掉；其中前两格**另外**还因为**当前阈值**恰好让 `p` 蕴含 `c`（`score >= 50` 对 `c = score >= 48`）而连 `neg` 桶也空，等式退化成 `whole == pos`、杀伤力归零，把分区阈值调大就不再退化。真正的判据是「`neg` 与 `null` 同时为空」，那才是零杀伤力格。而「结构必然的空桶」和「夹具悄悄退化的空桶」在运行结果里长得一模一样，所以退化集合一旦变化就判红，逼人重新论证每一格。只有第一道门时，空桶会被静默放过——`and()` 原样返回左操作数也能过。
- 倒序互逆那格（`descendingOrderIsTheReverseOfAscending`）专门补 TLP 的盲区：TLP 两侧都用同一条 `ORDER BY`，渲染错会**同时**出现在两侧，等式照样成立。实测把 `JpaPaths.descending` 改成 `builder.asc(...)`，这条当场红。
- 页大小取 7 与夹具默认行数 64 互质，末页必然不是整页——整页时「多取一行/少取一行」在拼接结果里看不出来。
- **它看得见什么、看不见什么**（写清楚比宣称更强有用）：看得见 `AND`/`OR`/`NOT` 的优先级与分组错、`NOT` 与 `NULL` 的交互错、条件被静默丢弃、分页切片错位、排序方向写反、排序键被整段丢弃（两侧返回同一顺序，互逆关系当场破——这一条是倒序互逆那格抓的，不是它的盲区）、投影列漏列与列序错位、某列被换成常量、**装配层的行形态错**——`RowAssembler#tuple` 对「行 cell 数 ≠ 投影列数」是**拒绝**（`IllegalStateException`，消息带两个数字，`RowAssembler.java:74-77`），不是静默截断也不是静默补 `null`，因此后端交回少列/多列的行时在装配处直接红（这一条由 `ProjectionMappersTest` 的短行/多列族用例钉住，不依赖 TLP 或差分）。**看不见**两类——① 谓词整体平移（`GE`→`GT` 三桶一起移，等式仍成立，靠差分抓）；② 排序键被换成「在夹具上与 id 同序的另一列」（两侧照样互逆，看不出，只能靠差分的期望行序）。还有一类**不在「看得见/看不见」之争里**：**SQL 里多渲染了一列**（投影项没变、语句多 `SELECT` 一列）——装配只看行 cell 数与投影列数，两者一致，多余那列在渲染侧就多余，变形关系与差分都看不出来，要抓得靠 SQL 快照。
- 投影那一格（`projectedColumnsSurvivePagingAndStayAligned`）值得单说：分页重组若只比 id，`select` 漏列、多列、列序错位全都看不见——id 照样拼得回去。它把三列投影的结果与「从整条实体上直读同样三个字段」算出的元组逐行对齐比较，期望值不来自任何手算常量，而是同一夹具的另一条读路径；列序写反、某列被换成常量、`SELECT` 里少一列，三种都会红。
- **它不替代差分**，实测分工是互补的：把 `JpaPredicates` 的 `AND`/`OR` 互换，TLP 当场红 4 条（分区等式与两两不相交全破）；把 `GE` 改成 `GT`，TLP **全绿**（三桶照样覆盖全集，因为 `>` 与 `NOT (> )` 仍是合法分区），红的是差分。所以判据是「哪一族关系能看见这个错」，不是「哪一层更高级」。

**写侧维度覆盖（嵌入 / 列名覆盖 / 时间 / 自增主键）**：这四类只能走「声明式期望 + SQL 快照 + 变异证据」的组合 oracle（理由见上一条「写侧往返」），落点是 `nextentity-tests-jpa` 的**三份契约基类**（四个维度里 `@Embedded` 写侧与 `@AttributeOverride` 共用一份——后者串不串列只有前者那套镜像断言才看得出来）。每份基类只有**一套断言**，H2 子类与真 MySQL 子类只换接线——两份断言会漂移，漂移出来的绿是假绿：

| 维度 | 契约基类（`nextentity-tests-jpa/src/test`） | 钉住什么 |
|---|---|---|
| 嵌入字段写侧（两层嵌套 + 置 null） | `EmbeddedOverrideWriteContract` | 整块 `@Embedded` 参与 `UPDATE ... SET` 时，Hibernate 必须把它摊平成叶子列（`set home_city=?` 而不是 `set home=?`，后者在真库上直接语法错）；嵌入置 `null` 也只写叶子列；`insert` 与 `update` 两条写路径都覆盖，并用原生查询核对落库列 |
| `@AttributeOverride`（含嵌套路径 `geo.zone`） | 同上 | 两个同类型嵌入各自覆盖成不同列名后，**查询 / 排序 / 写入三面必须都走覆盖后的列名**且互不串列。证据是镜像断言：同一份数据按 `home.city` 查得 `{1}`、按 `office.city` 查得 `{2}`；渲染快照里有 `home_city=?`、没有 `office_city=?` |
| 时间类型（`Instant` / `LocalDateTime` / `LocalDate`） | `TemporalWriteContract` | 写入往返不丢精度（含微秒级小数秒）、不被 JVM 时区挪动；比较算子对时间列成立；时间列上的 `between` 是闭区间，且 `le`/`lt` 的边界值**刻意取夹具里的日期**——上界若不是夹具值，`<=` 被改成 `<` 返回集合同样不变，断言就没有区分力。正确性靠差分：内存侧另算一遍期望行集，于是「夹具含 `null` 行时比较条件不命中 `null`」是被差分钉住的，而不是靠手写常量断言 |
| 自增主键（`@GeneratedValue(IDENTITY)`） | `GeneratedIdInsertContract` | `insert` 返回 void 是契约，主键由 Hibernate 回填进入参对象；回填的 id 必须能直接驱动 `update` 命中唯一行；连续 insert 不撞键。三条一起钉，才防得住「插入成功但 id 没回填」这类只有写侧才看得见的缺陷 |

**嵌入与时间两份基类用变异注入验过「不是摆设」**（手法见下「变异测试」：改一行生产代码 → 只跑相关契约 → 看红的是不是该红的那一条 → `git checkout` 还原）：

- 把 `JpaPersistExecutor#setValues` 改成「只写 `String`/`Number`/`Boolean`/枚举/时间类型，其余一律 `continue`」（等价于把 `@Embedded` 误当关联跳过）→ 嵌入契约红 **2 条**（`updateFlattensBothEmbeddedsIntoLeafColumns`、`updateWithNullEmbeddedWritesNullLeaves`），时间契约当轮 9 条全绿（该注入在 `690e863` 之后、`a829898` 之前实测，那时 `TemporalWriteTest` 还是 9 条；HEAD 上是 10 条 1 skip，结论不变）——两类缺陷互不冒充，没有连带误报；
- 把 `JpaPredicates` 的 `GE` 从 `greaterThanOrEqualTo` 改成 `greaterThan`（闭区间变开区间）→ 时间契约红 **1 条**（`queryByLocalDateTimeAndLocalDateComparison`），且是**差分 oracle** 打红的（端点行被内存筛选算进来了、真库却漏掉）；
- 把 `JpaPredicates` 的 `LE` 改成 `lessThan`、再把 `LT` 改成 `lessThanOrEqualTo`（两个方向各一次）→ **两次都只红 1 条**，还是 `queryByLocalDateTimeAndLocalDateComparison`。这一对是第三轮审查逼出来的：上界原来取 `2024-03-31`（夹具里没有这个日期），`<=` 变 `<` 返回集合同样是 `[1]`，断言没有区分力；改成上界一律取夹具值（`le(DUE_1)` 的 `2024-03-10`、`lt(DUE_2)` 的 `2024-04-10`）之后，两个方向都能红。

**注入必须带 `-am`（或先 `install`）**：`-pl nextentity-tests-jpa` 单模块跑会从 `~/.m2` 取**未变异**的 `nextentity-jpa` 产物，改的那一行根本不生效，表现是「变异后依然全绿」——第一轮复核就是这么被骗过一次，改成 `-pl nextentity-tests-jpa -am` 后同一变异立刻红 2 条。

**自增主键那份没有变异证据，理由要写清**：号段由数据库决定，改框架代码造不出「号段错」这类缺陷（框架只做 `persist` + 回填），所以它的证据形态是 SQL 快照（列清单、占位符个数、自增列以 `default` 发号）+ 回填断言，不是变异注入。

**这四类都不需要新增生产代码**：`@GeneratedValue` / 时间类型 / `@AttributeOverride` 全程只经过 Hibernate 的属性绑定，`nextentity` 侧一行没动——`jpa/meta/JpaEntityAttribute` 走的是字段反射（属性名 = Java 字段名，列名一律由 Hibernate 管），所以 `@AttributeOverride` 只改列名、不改框架行为。它们的价值不是「新代码要测」，而是**这四条语义此前一条用例都没有**：非 legacy 的**持久化实体**里 `@GeneratedValue` / `Instant` / `LocalDate` / `@AttributeOverride` 零命中（唯一的 `Instant` 字段在 `nextentity-core` 的 builder 单测里，是个非持久化字段，不落库），`insert` 后的 id 回填、时间列的往返精度、嵌入写侧的摊平行为全靠「读代码推断」，而推断对错没有证据。

**度量体系**：

| 指标 | 角色 | 目标 |
|---|---|---|
| 变异分数（PIT） | 主指标 | `api` / `core` / `jpa` / `spring` / `jdbc` 均 ≥ 95%；当前实测 98.50% / 99.07% / 95.84% / 100% / 96.7%（见下「变异测试」，阈值写在各模块 pom） |
| 语义等价律条数 | 辅助 | 每条律至少 1 条差分用例，条数只增不减 |
| 方言矩阵通过率 | 辅助 | 默认通道 100% 通过；skip 数单独报表，只许降不许升。矩阵列为 H2\MODE=MySQL\ + MySQL 8.0.18（默认、强制）+ H2\MODE=PostgreSQL\（`-Pdialect-pg`，报告落独立目录成独立通道 `surefire-pg:`，`pr`/`ci`/`night`/`release` 四档都跑；9 条已知方言差异按 `tools/expected-failures/h2-postgresql.txt` **逐条**判定，多一条或少一条都红；`Instant` 绑定偏移不再计入（写侧断言按方言分派，既不红也不跳过，见下「方言矩阵通道」））；skip 报表**已建成**：每次门禁都从 surefire/failsafe XML 里抽 `<skipped>` 并连理由一起落进 `tmp/gate/<commit>-<ts>/receipt.json`，见下「执行凭证」 |
| 缺陷逃逸率 | 辅助 | 发版后发现的语义缺陷数 / 版本，趋势必须向下 |
| 行/分支覆盖率（聚合口径） | **不作门禁**，仅诊断报表 | 不设百分比门槛；判据是「新增的未覆盖行必须落在下文『已判定不可达与度量限制』清单里」，落在清单外就按缺口处理 |

**覆盖率要用合并口径看**：各模块自己的 `jacoco.xml` 只统计本模块 `src/test` 的执行数据，而 L2/L3/L4 的用例跑在 `nextentity-tests-jpa` 的 JVM 里——只看模块报告会把「语义套件跑到了、模块单测没跑到」的行误判成缺口（`JpaOperands` 的 `sub`/`mul`/`div` 分支就是这么被误判的）。所以 `nextentity-tests-jpa/pom.xml` 绑了 `jacoco:report-aggregate`，`mvn -pl nextentity-tests-jpa -am verify` 之后看 `nextentity-tests-jpa/target/site/jacoco-aggregate/jacoco.xml`，那才是「全部测试合起来」的口径。

用合并口径实测（`655` H2 + `590` 容器用例全跑，三条通道各 skip 1 条（H2 surefire / MySQL failsafe / PG 兼容 surefire），见下「方言矩阵」），生产代码里没被执行的行**只剩清单里那些**，但四个模块要分开看口径：

- **聚合报告只含 `nextentity-jpa` 与 `nextentity-testing` 两个 group**（`report-aggregate` 只收直接编译依赖，`api`/`core` 是传递依赖、`spring` 是 test 作用域，都不进聚合文件）。`nextentity-jpa` 组里只剩两处：`jpa/meta/JpaEntityAttribute.java:48-49`、`:58-59`（`catch` 行与 `throw` 行都从未执行，所以台账登记的 `:49`/`:59` 字符串拼接变异体是 NO_COVERAGE——两处说的是同一件事，不是行号打架）。行成形已从 `JpaExecutor` 移到 `builder.RowAssembler`，`JpaExecutor` 里原先那几行「行已被模块单测执行、只剩分支没走全」的条目随之消失（见下表）。
- `api`/`core`/`spring` 看各自模块的 `jacoco.xml`：`api` 剩 **0 行 1 分支**（`api/Conditions.java:104` 的 `parent == null` 子分支）、`core` 剩 3 行（`core/builder/PathRefs.java:90-92`）、`spring` 零行零分支。
- 两处口径合起来 = **清单外没有一条未覆盖的生产代码行**。剩余未覆盖行都在 `nextentity-testing/src/main` 的 oracle 自身（各差分基类的内存求值/生成器分支、`Shape` 自检的抛错分支），属于测试基础设施的自检路径，不是产品能力。

行覆盖率不作门禁的理由：覆盖率只说明「执行到了」，不说明「断言到了」——空断言、只断言非空、只断言 `size()` 都能拿到接近 100%。把它设成门禁，只会奖励「多写方法少写断言」。换成上面那条「未覆盖行必须进清单」的判据就不一样：清单本身就要求写清不可达的论证，凑数进不了清单。

**覆盖率口径**（数字必须能复算，否则不许引用）：

- 官方数字 = **除 `nextentity-tests-legacy` 之外**全部模块的 `jacoco.exec` 合并后按模块出报表。legacy 只作 2.2.3 语义回归证据，**不进分子也不进分母**——它覆盖的是旧语义，并进来会让新能力的缺口被旧用例填平。
- 单模块 `jacoco:report` 只反映本模块用例的贡献，不能当官方数字：`nextentity-testing` 没有自己的测试源码（它是被 `tests-jpa` 继承执行的基类库），单跑它只会得到 0；`api` 的单模块数字也漏掉 `core` 用例对它的执行。
- 复算（仓库根目录，先跑 `mvn verify` 产出各模块 `target/jacoco.exec`）：

```bash
java -jar <本地仓库>/org/jacoco/org.jacoco.cli/<版本>/org.jacoco.cli-<版本>-nodeps.jar \
    merge nextentity-core/target/jacoco.exec nextentity-jpa/target/jacoco.exec \
          nextentity-spring/target/jacoco.exec nextentity-tests-jpa/target/jacoco.exec \
          nextentity-arch/target/jacoco.exec --destfile tmp/cov/merged.exec
java -jar <同上> report tmp/cov/merged.exec \
    --classfiles nextentity-<模块>/target/classes \
    --sourcefiles nextentity-<模块>/src/main/java --xml tmp/cov/<模块>.xml
```

**覆盖率快照**（合并口径，第 5 轮结束时；复算命令见上）：`api` 行 286/286（100%）、分支 80/81（98.8%）；`core` 477/480（99.4%）、分支 222/222（100%）；`jpa` 365/369（98.9%）、分支 163/166（98.2%）；`spring` 12/12、零分支；`testing`（oracle 自身的自检路径）3095/3120（99.2%）、分支 582/623（93.4%）。**生产代码四个模块合计行 1140/1147（99.4%）、分支 465/469（99.2%）**。剩下的 **7 行 / 4 分支**就是下表里 `api/Conditions.java:104`、`core/builder/PathRefs.java:90-92`、`jpa/meta/JpaEntityAttribute.java:48-59`、`jpa/exec/JpaExecutor.java:122/124` 四条（第 1、4 条是分支，第 2、3 条是行）；表里另有几行是「模块级 JaCoCo 归因范围」与「探针位置」问题（合并口径下已被执行），逐行说明见对应条目。**注意**：这是装配重构之前那一轮的快照；`JpaExecutor` 的 `shape`/`shapeRow` 已整段删除、行成形移入 `builder.RowAssembler`，因此该文件不再有 122/124 行这两处缺口，下一轮复算时 `jpa` 与 `core` 的分母都会随之变化。

**已判定不可达与度量限制**（这些未覆盖行**已论证**，不再按缺口追；新增未覆盖行要么补用例，要么先在这里写下论证）：

| 位置 | 为什么不可达 |
|---|---|
| `api/Conditions.java:104` 的 `parent == null` 子分支 | `PathOperatorValue` 只在 `AlwaysTrue.or(ref)` 时 `parent` 为 `null`，而那条路径 `suppressed == true`，在 `:102` 就返回了 |
| `core/builder/PathRefs.java:90-92` | `writeReplace.invoke(ref)` 在合法方法引用上不会抛 `ReflectiveOperationException` |
| `jpa/meta/JpaEntityAttribute.java:48-49`、`:58-59` | `resolveField` 已 `setAccessible(true)`，之后的 `IllegalAccessException` 在正常 JVM 上不会发生 |
| ~~`jpa/exec/JpaExecutor.java:122`、`:124`~~（**已随重构消失**） | 该文件原来的 `shape`/`shapeRow` 已整段删除，行成形移入 `builder.RowAssembler`：现在「单列却返回长度≠1 的 `Object[]`」不再是「公开查询造不出来、只能靠台账记账」的不可达分支，而是 `RowAssembler#tuple` 里显式拒绝的一类输入（装配层看得见，见「测试策略」一节的「看不见」清单）。原先那四行变异体的台账条目（`JpaExecutor:113/:121/:122×2/:124`）已从 `tools/equivalents/nextentity-jpa.txt` 删除 |
| `jpa/compile/JpaOperands.java:61-63`（`sub`/`mul`/`div`）、`jpa/compile/JpaPredicates.java:77-81`（列对列 `NE`/`GT`/`GE`/`LT`/`LE`）、`jpa/exec/JpaPersistExecutor.java:155-156`（`@Version` 类型不支持的 `UnsupportedOperationException`） | **不是未执行，是模块级 JaCoCo 的归因范围**：这三处的用例在 `nextentity-tests-jpa`（`ArithmeticDifferentialTest` 的五个算术格、`ColumnComparisonDifferentialTest` 的六格、写侧 `short` 版本往返），跑在另一个模块的 JVM 里，`nextentity-jpa` 自己的 `jacoco.xml` 看不到。判据是「模块单测没跑到、语义套件跑到了」——这正是上文「度量口径」说的两层互补 |
| `testing/TerminalConsistencyTest.java:52`、`ProjectionMapperConsistencyTest.java:103` | **不是未执行，是 JaCoCo 归因限制**：这两个 lambda 体唯一的退出路径是被调方抛异常，探针插在被调方调用之后、`return` 之前，永远到不了。用例本身是通过的，断言也确实生效（已用 `javap` 看探针位置 + `-Xlog:exceptions` 观测到异常进 `catchThrowable` 双向确认）。修不掉，除非给 lambda 套一层只骗探针的空包装——那会把证据写糊 |

**运行预算**（四档，按「多久能拿到结论」切分）：

| 档 | 何时跑 | 跑哪些层 | 预算 |
|---|---|---|---|
| 快 | 保存 / 提交 | L0 + L1 | < 60s |
| PR | push / PR | L0–L2（跑 `nextentity-tests-jpa` 时加 `-DskipITs`，只跳容器通道） | < 10min |
| 夜间 | 每晚 | L0–L4 + 变异测试（L4 需 Docker） | 不限 |
| 发版前 | 发版 | L0–L4 + 全量门禁 | 不限 |

**通道拆分**（surefire 与 failsafe 各管一半，靠类名分流）：

| 通道 | 收哪些类 | 依赖 | 跳过方式 |
|---|---|---|---|
| surefire | 除 `*IntegrationTest` 外的全部（H2，MySQL 兼容模式=默认） | 无 | — |
| surefire-pg | 与 surefire **同一批** 655 条（条数相等由凭证强制，少跑即红），H2 的 **PostgreSQL** 兼容模式 | 无 | 报告落 `target/surefire-pg-reports/`，见下「方言矩阵通道」 |
| failsafe | `**/*IntegrationTest.java`（显式 `<includes>`） | Docker + `mysql:8.0.18` | `-DskipITs` |
| failsafe-pg（`nextentity-tests-jpa`） | `**/Postgres*IntegrationTest.java`：真 PostgreSQL 容器通道，与 MySQL failsafe **同一批 590 条 / 49 类**（方言矩阵等式由凭证强制） | Docker + `postgres:16` | 报告落 `target/failsafe-pg-reports/`；`-Pjpa-postgres` 激活；skip 预算 1 条（`PostgresTemporalWriteIntegrationTest#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode`，H2 兼容模式专属，容器通道不适用） |
| surefire（`nextentity-tests-jdbc`） | 除 `*IntegrationTest` 外的全部（H2 MySQL 兼容模式；表由测试侧 DDL 建，不走 Hibernate） | 无 | — |
| surefire（`nextentity-examples`） | 真语料通道：2.2.3 examples 移植后的 130 条 H2 集成用例（6 个类），只作回归证据、模块不发布 | 无 | 报告落默认 `target/surefire-reports/`；step 名 `surefire:nextentity-examples`，下限 130、skip 预算 0 |
| failsafe（`nextentity-tests-jdbc`） | `**/MySql*IntegrationTest.java`：与 H2 通道**同一批 31 个基类的 `MySql*` 接线类** + 方言/锁/写入容器用例 | Docker + `mysql:8.0.18`（`MySqlL4Container` 同款约定） | `-DskipITs` |
| failsafe-pg（`nextentity-tests-jdbc`） | `**/Postgres*IntegrationTest.java`：同一批 31 个基类的 `Postgres*` 接线类，跑在真 PostgreSQL | Docker + `postgres:16`（`PostgresL4Container`） | 报告落 `target/failsafe-pg-reports/`；`-Pjdbc-postgres` 激活 |
| failsafe-mssql（`nextentity-tests-jdbc`） | `**/SqlServer*IntegrationTest.java`：同一批 31 个基类的 `SqlServer*` 接线类，跑在真 SQL Server | Docker + `mcr.microsoft.com/mssql/server:2022-latest`（`SqlServerL4Container`） | 报告落 `target/failsafe-mssql-reports/`；`-Pjdbc-sqlserver` 激活 |

**`nextentity-tests-jpa` 的 PG 容器通道**：`-Pjpa-postgres` 与 JDBC 侧 `-Pjdbc-postgres` 同形（failsafe include 切到 `Postgres*IntegrationTest`、报告落 `target/failsafe-pg-reports/`、凭证认 `failsafe-pg:nextentity-tests-jpa`），`-Dnextentity.requireContainer=true` 固化在 profile 内——无 Docker 直接红，不静默降级。2026-10-30 容器实测：**590 条 / 49 类全绿**，与 MySQL failsafe 的 590 条同批，方言矩阵等式成立；skip 1 条是 H2 兼容模式专属的 `Instant` 偏移用例。`tools/gate.sh` 的 `ci` / `night` / `release` 档都已接入 `ci-jpa-pg` / `night-jpa-pg`（`STEP_MIN_TESTS=failsafe-pg:nextentity-tests-jpa=590`、`STEP_MAX_SKIP=1`）。

**方言矩阵通道（`surefire-pg`）**：`-Pdialect-pg` 在**同一次 `verify`** 里加第二个 surefire execution（`-Dnextentity.h2mode=postgresql`），报告写进独立目录 `target/surefire-pg-reports/`，凭证按目录名认成独立通道 `surefire-pg:<模块>`。三个设计点都是被具体失真逼出来的：

1. **为什么不用第二次 `mvn verify`**：第二次要么带 `-am`（把 api/core/jpa/spring 全重跑，白烧两分钟），要么不带（触发门禁的「`-pl` 缺 `-am`」守卫）。放进同一次 verify 的第二个 execution，两边都躲开。
2. **为什么报告必须分目录**：surefire 默认把 XML 写死在 `target/surefire-reports/`，第二遍会**覆盖**第一遍。覆盖之后凭证读到的「655 条 0 失败」其实是 PG 那遍的，MySQL 兼容模式的结果凭空消失——这正是「读产物判绿」最典型的失真。`-Dsurefire.reportsDirectory` **不是** surefire 的 user property（实测传了不生效，53 个报告照样落在默认目录），只能在 pom 里显式写死。
3. **为什么当独立通道而不并进 `surefire`**：并进去就成了「两个模式的失败数相加」，既看不出是哪个模式红的，也没法给 PG 模式单独挂预期失败基线。

**默认不激活**：PG 兼容模式有 9 条已定性的方言差异（3 条 SQL 文本快照 + 5 条 H2 不认的 PG 锁语法 + 1 条 H2 兼容层把 `Instant` 读回偏移一个 JVM 时区，真 MySQL/PG 容器原样读回），清单与租约在 `tools/expected-failures/h2-postgresql.txt`。让这 9 条在每次 `mvn test` 里都红，等于训练所有人忽略红；所以只在 `pr`/`ci`/`night`/`release` 档由 `gate.sh` 显式带 `-Pdialect-pg`，配合基线逐条判定（判据见下「执行凭证」）。**跳过预算也要分开申报**：两个模式的跳过项恰好相反——默认模式跳「PG 专属那条」，PG 模式跳「`Instant` 严格相等那条」（H2 的 PG 兼容模式把 `Instant` 绑成丢偏移的 `timestamp`）。

两个方向都写死：surefire `<excludes>` 排掉 `*IntegrationTest`，failsafe `<includes>` 收这些类。failsafe 的默认 includes 是 `**/IT*`、`**/*IT`、`**/*ITCase`，与项目约定的 `*IntegrationTest` 一个都不匹配——不显式配置时容器用例会被 surefire 跑掉、failsafe 一条也收不到。容器用例统一 `@Tag("container")` + `@ExtendWith(MySqlL4Gate.class)`。当前实测条数：surefire **655** 条（全 H2）、failsafe **590** 条（真 MySQL，含 `@Version` 乐观锁、`@Convert`、批量写、悲观锁四类写路径场景，另加嵌入/时间/自增主键三族写侧维度），三条通道各跳过 **1** 条（H2 surefire / MySQL failsafe / PG 兼容 surefire）——就是 `TemporalWriteContract` 里那条 PG 兼容模式专用的 `Instant` 偏移用例（`@DisabledIfSystemProperty` 标记，默认两通道都不跑，PG 通道跑它、同时关掉严格相等那条），**不是**新引入的静默跳过。拆分之初是 130 / 110，差额来自后续补的差分 oracle（见下「差分 oracle 台账」）、写侧往返用例与写侧维度覆盖（见上「写侧维度覆盖」）。**第二后端同款拆分**：`nextentity-tests-jdbc` 的 surefire 跑 H2（表由测试侧 DDL 建，不经 Hibernate；H2 用 `MODE=MySQL`，DDL 是 MySQL/PG/MSSQL 三家都能执行的子集，库名/列类型差异由 `JdbcSchema` 按方言替换）；failsafe 默认跑真 MySQL（`MySqlL4Container`），`-Pjdbc-postgres` / `-Pjdbc-sqlserver` 把 failsafe 的 `include` 与 `reportsDirectory` 切到对应方言（`failsafe-pg-reports` / `failsafe-mssql-reports`）——三条容器通道**互斥**，各自跑同名基类的 31 个方言接线类（`MySql*` / `Postgres*` / `SqlServer*`，共 31×3 = 93 个接线类），即每家容器上都是同一个「31 个基类」的语义面。通道条数下限登记在 `tools/gate.sh` 的 `STEP_MIN_TESTS`（**H2 593 / MySQL 544 / PG 544 / MSSQL 544**，2026-10-30 全程实测，含 golden schema 与 extended capabilities 镜像，四条通道 skip 全 0）；`nextentity-jdbc` 单测通道 `surefire:nextentity-jdbc` = **329**，三条容器通道跳过预算默认 0。

**时区必须钉在构建参数上，不能钉在用例里**：`nextentity-tests-jpa/pom.xml` 的 `pinned.timezone`（默认 `Asia/Shanghai`）同时进 surefire/failsafe 的 `-Duser.timezone` 与 `-Dnextentity.pinnedTimezone`，后者是时间契约自检的期望值——两个 `-D` 同源，「换过时区也全绿」才不是空话。理由有两层：`Instant` 往返断言的区分力全在「墙钟被挪过就会差整小时」，跑测机器若在 UTC，这条断言对「按 JVM 时区换算」这类缺陷的区分力归零；而 Hibernate 在 `buildSessionFactory()` 时就缓存了默认时区，装置又由子类字段初始化创建，所以 `@BeforeEach` 里 `TimeZone.setDefault` 是「晚于建连」的——实测会把 `LocalDate.of(2024,3,10)` 写成 `2024-03-09`，等于把装置变成非法的时区实验。换时区验证用 `-Dpinned.timezone=America/New_York` 整通道跑；**不要**用 `-DargLine=...`，jacoco `prepare-agent` 会覆盖 `argLine` 属性、且 pom 已把 `-Duser.timezone` 写进同一个 `argLine`，后写胜出，改了等于没改。副作用要记住：这是**模块级** fork 参数，本模块全部 655 条用例都在该时区下跑（本模块只有 `TemporalWriteContract` 读 `ZoneId.systemDefault()`，其余用例与时间无关），CI 机器若在别的时区，行为与本地不同。

**容器通道不许静默降级**：`MySqlL4Gate` 是自定义的 `ExecutionCondition`，默认「没有 Docker 就跳过」（本地开发需要这个宽进），但加上 `-Dnextentity.requireContainer=true` 后不再跳过——Docker 缺失时容器在静态初始化阶段启动失败，构建直接变红。**CI 必须带这个属性**，否则真方言对拍会悄悄变成 skip，报表上看不出区别，L4 就成了纸面门禁（数字以「通道拆分」一节的实测条数为准）。**CI 已接入**：`.github/workflows/ci.yml`（push/PR）与 `nightly.yml`（每日 02:00 Asia/Shanghai）都经 `tools/gate.sh` 的 `ci`/`night` 档调 `-Dnextentity.requireContainer=true`，且凭证对「跳过数超过申报预算」判缺口（容器通道在 `ci`/`night` 档申报预算 `1`，就是那条 PG 专用用例；预算写死在 `tier_ci`/`tier_night` 里，改不动的是默认值 0）——所以这条门禁不再依赖人记得加属性。三条路径都实测过：宽进 + 无 Docker → 容器用例全部 skip / SUCCESS；强制 + 无 Docker → 容器类整体 error / FAILURE；强制 + 有 Docker → 590 条全 pass（1 条 PG 专用用例按标记 skip）/ SUCCESS。JPA 侧的 PG 容器通道另计一条 `failsafe-pg:nextentity-tests-jpa` 通道（590 条 / 49 类，见上「通道拆分」）。

**镜像源**：容器镜像默认走 `docker.xuanyuan.run`（`docker.xuanyuan.run/library/mysql:8.0.18`、`docker.xuanyuan.run/library/postgres:16` 与 `docker.xuanyuan.run/testcontainers/ryuk:0.11.0`；SQL Server 镜像在 `mcr.microsoft.com` 上，未走镜像站），不走 `registry-1.docker.io`——受限网络下直连 Docker Hub 会 `EOF`，而容器用例是 L4 的唯一载体，镜像拉不到就等于这层没跑。两处细节：`MySQLContainer` 会校验镜像仓库名，用镜像站前缀必须显式 `asCompatibleSubstituteFor("mysql")`，否则被判「不兼容」拒启；Ryuk 是 Testcontainers 另起的回收容器，默认也从 Docker Hub 拉，所以它要单独用 `ryuk.container.image` 属性（同时给 `TESTCONTAINERS_RYUK_CONTAINER_IMAGE` 环境变量，防版本差异只认其中一条）指到同一个源。换源或换版本用 `-Dnextentity.mysql.image=` / `-Dnextentity.postgres.image=` / `-Dnextentity.ryuk.image=` 覆盖，不必改 pom（SQL Server 通道默认 `mcr.microsoft.com/mssql/server:2022-latest`，另有 `-Dnextentity.sqlserver.image=`）。实测确认：运行期 `docker ps` 里出现的就是这些镜像站的引用，跑完由镜像站的 Ryuk 回收干净。

**CI 上的镜像源是反过来的**：GitHub runner 拉不到 `docker.xuanyuan.run`，所以在 CI 里把这三个属性覆盖回 Docker Hub 原名（`library/mysql:8.0.18` / `library/postgres:16` / `testcontainers/ryuk:0.11.0`，见 `.github/workflows/ci.yml` 与 `nightly.yml` 的 `env.GATE_EXTRA_MVN_ARGS`）。其中 `nextentity.postgres.image` 是**必需项**而不是可选优化：`gate.sh` 的 ci/night/release 档都会跑 `-Pjdbc-postgres` 容器通道，该通道默认从根 pom 取 `docker.xuanyuan.run/library/postgres:16`，在 runner 上拉不到就整步失败。注入通道是 `tools/gate.sh` 的 `GATE_EXTRA_MVN_ARGS` 环境变量——它给每条 mvn 命令追加参数，**只接受 `-D` 开头的属性**：`-pl` / `-DskipITs` 这类开关会改变「实际跑了什么」，若能从环境里悄悄塞进来，凭证的可核对性就没了。代价是吃 Docker Hub 的匿名拉取限流（每 IP 每 6 小时 100 次），偶发 429 会让容器步随机红；要彻底摆脱这个外部依赖，得改成 `services:` 起 MySQL + 外部实例模式（复用 `MySqlL4Container` 的接线）。

**变异测试（PIT，已接入五个模块）**：`mvn -pl nextentity-api test-compile pitest:mutationCoverage`，`-pl nextentity-core` / `-pl nextentity-jpa` / `-pl nextentity-spring` / `-pl nextentity-jdbc` 同理。它只出分数、不新增用例形态，所以它不替代别的层，只回答一个问题：**这一行被跑到过、而且断言足够紧到能发现它被改了吗**。

`test-compile` 不能省：`pitest:mutationCoverage` 自己不编译，陈旧的 `target/test-classes` 会让它拿旧用例去杀新代码，表现成「刚写的用例好像没生效」。同理，PIT 不带 `-am`，它从 `~/.m2` 解析依赖模块，跑前先 `mvn install -DskipTests -Djacoco.check.skip=true`（`-DskipTests` 下没有 jacoco exec 数据，不带 skip 会让 `jacoco:check` 判红）。

当前实测（算子集 `STRONGER` + `NON_VOID_METHOD_CALLS`，且关掉 `FRECORD` 过滤器，见下）：

| 模块 | 变异范围 | 生成 | 杀死 | 分数 | 台账内等价变异 |
|---|---|---|---|---|---|
| `api` | `io.github.nextentity.api.*` | 466 | 459 | 98% | 7 条 |
| `core` | `io.github.nextentity.core.*` | 863 | 855 | 99% | 8 条 |
| `jpa` | `io.github.nextentity.jpa.*`（全模块，约 3 分钟） | 529 | 506 | 96% | 23 条 |
| `jdbc` | `io.github.nextentity.jdbc.*`（全模块，含方言实现） | 1599 | 1546 | 96.7% | 44 条（`tools/equivalents/nextentity-jdbc.txt`；覆盖 53 个未杀死变异体 = 44 SURVIVED + 9 NO_COVERAGE，expires 2027-04-26） |
| `spring` | `io.github.nextentity.spring.*` | 14 | 14 | 100% | — |

**算子集必须显式调，默认集会漏掉本项目的核心写法**：

- **关掉 `-FRECORD`**。PIT 默认开启这个过滤器，自述是「过滤编译器为 record 生成的代码」，实测却把**整个** record 类的变异全部吃掉。`nextentity-core` 的 `spec` 包里 27 个节点类型全是 record，开启时零变异、零覆盖——看起来「这些类没什么可测的」，实际是它们从未被变异过。关掉后 `core` 的变异体从 242 涨到 863。
- **加 `NON_VOID_METHOD_CALLS`**。默认集不变异 `Objects.requireNonNull` 这类非 void 调用，而本项目的入参校验全在紧凑构造器里：用默认集时，把整行校验删掉也无人发现。

**度量口径（上表只覆盖本模块 `src/test`，别读成「全部测试都在杀变异」）**：各模块 pom 的 `targetTests` 都是本模块包（`io.github.nextentity.<模块>.*`），所以 `nextentity-tests-jpa` 的语义套件不在 `jpa` 的分数里。两层各有盲区、并集才齐：语义套件擅长「跨层语义」，模块单测擅长「边界与拒绝分支」。

**剩下的变异体逐条进台账，不靠调阈值消化**：`tools/equivalents/<模块>.txt`，一行一个已论证杀不死的变异体（`类 | 行 | 方法 | 算子 | 条数 | reason=… | expires=…`）。判据与预期失败基线同款，是**双向**比对而不是「台账非空即过」：

- 实际未杀死、台账没有 → 漏测，判红；
- 台账申报了、报告里没有 → 台账腐化（已修好仍留着，或该行被删改），判红；
- 同位置条数不符 → 算子集或代码结构变了，原论证不再覆盖，判红；
- `expires` 到期未续 → 与 `@Disabled` 超期同性质，判红；空台账按配置错误处理。

逐条论证写在台账的 `reason=` 里，不在这里重复（本文件只留口径）。三条最典型的形态：`new ArrayList<>(size)` 的容量提示、javac 为 switch 模式变量/匿名类捕获生成的非空校验、以及「条件恒真时两条分支返回同一个对象」的冗余判断。

**判定顺序**：先确认语义套件是否已覆盖（它不在任何变异分数里），再确认是否等价；两步都不成立才是缺口。反过来说，「`STRONGER` 下还有存活体」不等于「还有盲区」——但也不等于「已经论证过」，台账就是这两者之间的唯一凭据。

**故障注入（一次性实验，不是常驻门禁）**：变异测试度量「这一行有没有被跑到、断言够不够紧」，但不回答「**整套测试能不能抓住一个真实语义缺陷**」——这两件事不等价，因为一个缺陷可能被几十条用例执行到却一条都断言不到。验证后者的唯一办法是反向注入：把已知缺陷手工改进生产代码，看测试红不红。

做法：两棵 `git worktree`，生产代码完全相同、只有测试不同——`h-baseline`（detached 在补测前的 `063e48a`，语义套件 166 条）与 `g-faults`（补测后的套件，362 条）。对每个缺陷：改生产代码 → 跑 `./mvnw -pl nextentity-jpa,nextentity-tests-jpa -am test -DskipITs -Dmaven.test.failure.ignore=true` → `git checkout -- .` 还原 → 换另一棵重跑。脚本是**一次性本地产物**（`tmp/` 不入库，要复现照上面五步手搓即可），唯一硬要求是「注入的字面量必须恰好命中 1 次」与「还原要 `git checkout -- <具体生产文件>`、绝不能用 `-- .`（会把还没提交的测试改动一起冲掉）」——否则注入没落盘或没还原，实验结论就是假的（第一次跑就踩过：`mvnw.cmd` 按 CWD 向上找 `.mvn` 定 basedir，脚本没先 `Push-Location` 到仓库根，结果编译的是另一棵工作树的 WIP，报了个假绿）。

12 个注入缺陷：分页 `total` 混入 `offset`、分页上界 off-by-one、多列投影列序对调、投影存在时 `distinct` 丢失、`between` 开区间化、`notIn(空集合)` 取反、排序方向翻转、`count(列)` 当 `count(*)`、`having` 变 `where`、`contains` 少一个通配符、`@Version` 不自增、关联嵌套路径 LEFT→INNER。

结果：**12/12 两边全红**，没有「两边都绿」也没有「旧红新绿」——所以这些缺陷本来就不是靠新用例才抓到的，新套件买到的是**精度与证据层**：L4 真库层红条数普遍是旧套件的 2–4 倍（排序翻转 28→121、列序对调 10F+11E→53F+10E），且 **7/12 由差分 oracle 直接命中**（旧套件只有 1/12，多数只靠一条手算常量用例报红，改个夹具就漏）。`notIn(空集合)` 那格最能说明问题：旧套件只有 `inEmptyCollectionMatchesNothing` 报红，NOT_IN 半支完全无感知，新套件的差分格把它单独钉住了。

这次实验还抓出四个真缺口（都已补）：投影+`distinct` 无差分格、`having` 无差分基类、`contains` 的差分格取 `"app"` 而夹具里 `apple`/`apple pie` 都以 `app` 开头（`%app%` ≡ `app%`，**前缀盲**，只有抽到 `berry` 这类非前缀子串才区分——已改成确定性格子，不靠随机）、关联嵌套路径无差分基类。

同时暴露一个门禁漏洞：**默认 `mvn test` 在第一个失败的模块就停**，12 个缺陷的「先红」全是既有 `core`/`jpa` 单测，`nextentity-tests-jpa` 的 L4 真库层在默认流水线里根本没跑；必须加 `-Dmaven.test.failure.ignore=true` 才看得到分层结果。这也说明「跑绿了」与「每一层都跑了」是两件事，夜间档要按层落日志、按层判读。

**第二轮故障注入（35 条，覆盖面换成 api 短路语义 / 分页与投影的静默降级 / 写侧版本与影响行数）**：脚本 `tmp/scripts/fault-campaign.ps1`（一次性本地产物，不入库），单个 `git worktree add ../nextentity3-semantic-pit`，逐条「`Get-Content -Raw` 精确替换 → `mvnw -pl nextentity-api,nextentity-core,nextentity-jpa,nextentity-tests-jpa test -DskipITs -fae` → `git checkout -- .` 还原」，按日志判读。结果 **30 杀 / 4 等价 / 1 脚本自身编译失败（30 + 4 + 1 = 35），零真盲区**。**判读口径本身踩过坑**：第一版按「退出码 ≠ 0 = KILLED」判，把注入后格式不合规的 4 条（`core14`、`jpa07`、`jpa08`、`jpa17`）算成了 KILLED——`spotless:check` 绑在 `validate`，格式违规同样让构建失败。按日志重新分类、再加 `-Dspotless.check.skip=true` 复跑后，这 4 条里 3 条确实是真 KILLED，第 4 条（`jpa08`：嵌套路径一律 `join`）**是 SURVIVED**。也就是说「退出码不为 0」只证明"构建没过"，要证明"测试抓到了"必须看失败来自哪个阶段。四条的等价论证：api `Conditions#skip()` 的两支、`core/spec/Window.limited()`、`jpa/compile/JpaPaths:62`（见上等价清单）。其中 api 第二支判等价的过程值得记下来，因为它差点被当成缺口：一开始按「无 parent 时会返回 `null`」判成**真盲区**并补了用例，结果把末行改成 `return parent;` 后那条新用例仍然全绿——查下去才发现 `skip()` 只有 `or(PathRef)` 造出来的链走得到，而两个构造点一个传 `this`（`Conditions:156`）、一个传 `null` 但 `suppressed = true`（`Conditions:196`），`parent == null` 这一支是死代码。**结论：等价变异只能靠"注入后跑一遍"证明，不能靠读代码论证**——所以那条 api 用例保留了，但改为钉另一半（`PathValues` 自己的短路必须返回恒真占位而不是 `null`，否则 `Path.of(X).eqIfNotNull(null).or(Y::getScore)` 会 NPE），javadoc 里写明"`skip()` 末行是等价变异"。**可复核性边界**：35 条的逐 id 判定表只存在于 `tmp/`（一次性本地产物，不入库）；能长期复核的是上面的聚合数（30 / 4 / 1 = 35）与四条等价变异各自的论证，脚本的精确补丁形态见 `tmp/scripts/fault-campaign.ps1`——重跑一遍比引用这张表可靠。

这轮还确认了两件此前只有猜测的事：

1. **夹具退化会把一整族用例变成同义反复**：关联嵌套路径的 17 个谓词格，在「只有 alpha / beta 两个非空取值」的夹具下只产出 6 种不同结果集——`gt(alpha) ≡ ne(alpha)`、`le(alpha) ≡ eq(alpha)`、`between(alpha, beta) ≡ isNotNull`、`like("al%") ≡ like("_lpha")`，于是把 `gt` 渲染成 `<>`、把 `between` 渲染成 `is not null`、把 `_` 通配丢掉都能全绿（每格原有的"期望非空 / 非全表"自检挡不住这种退化）。已把夹具扩到 7 行（`aardvark` < `alpha` < `alps` < `amber` < `beta` 五个可区分取值 + 一个 `null` 关联锚点），结果集从 6 种涨到 15 种（17 格里只剩两组语义本等价的同义反复：`ne ≡ notIn`、`contains() ≡ isNotNull()`；`notIn([])` 恒真、连关联为 `null` 的行也算命中，所以它与 `isNotNull` 不是同一格），并新增 `AssociationDifferentialTest#predicateCellsAreMutuallyDistinguishable()` 逐对断言「该互相区分的格子，期望集合确实不同」+ 一条按名字断言「重复的格组就是这两组」的兜底断言（不写「去重后应有 N 种」这类裸数字：加一格不必改数字，撞车时直接点名是哪两格撞了）——**夹具再退化就先在这里红**，而不是让 17 条谓词格继续假装在验证算子。
2. **跨算子族的碰撞比族内碰撞更隐蔽**：`contains` 格原先取子串 `bet`（命中 `beta` 那行），与 `ge(beta)` 的期望集合完全相同——把 `contains` 渲染成 `>=` 照样全绿。族内自检（同族算子两两可区分）发现不了这种跨族退化，所以可区分性自检里补了 `contains` vs `ge` 这一对，子串也换成 `am`（只命中 `amber`，`{6}` 在 17 格里唯一）。注意子串作用在**关联方的名字**（`alpha`/`beta`/`aardvark`/`amber`/`alps`）上，不是实体自己的名字——第一版选的 `ch` 命中空集合，当场被「期望集合不能为空」的自检拦下。同类的第二处是下划线通配格：原模式 `_lpha` 只命中 `alpha`，与 `eq(alpha)` 同集合，把 `like` 渲染成 `=` 全绿；改成 `a___`（四字符、以 `a` 开头，只命中 `alps`）后与所有比较格都不同集合，并补了 `eq(alpha)` vs `like(a___)` 这一对。
3. **模块 PIT 的存活体不能直接当盲区清单**：`STRONGER` 的 16 条存活体里，只有 1 条（`jpa/exec/JpaExecutor:55` 的锁模式下发）是被语义套件真杀死的覆盖，11 条等价、4 条防御性不可达（见上「变异组本身的强度也会骗人」）。把模块分数直接当结论会同时犯两个错：把等价变异当缺口，把语义套件的覆盖当成没覆盖。

**方言矩阵（已实测五次，opt-in，不是默认通道）**：`nextentity-tests-jpa` 的建库入口 `HibernateHarness#start`（`nextentity-tests-jpa/src/test/java/io/github/nextentity/tests/jpa/support/HibernateHarness.java`）按系统属性 `-Dnextentity.h2mode` 分派，判定逻辑是纯函数 `HibernateHarness#resolveMode(String)`：缺省（属性缺省或空白）`mysql`，URL 与从前逐字节相同；`postgresql` 时改连 `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH` 并钉 `PostgreSQLDialect`；其他取值（含大小写变体）归一化后仍不认识就抛 `IllegalArgumentException`，不静默回退（打错一个字母必须当场炸，否则等于什么都没跑）。**不读环境变量**：环境变量会被 fork 出来的 surefire JVM 继承，一条 `NEXTENTITY_H2MODE=postgresql` 就能让后续所有构建静默跑成另一个方言，而日志里看不出来——方言必须是命令行显式声明的。跑法：`./mvnw -pl nextentity-tests-jpa -am test -DskipITs -Dnextentity.h2mode=postgresql`（**要带 `-am`**；不带时 `nextentity-testing` 从 `~/.m2` 取陈旧快照，表现是新写的基类构造器「找不到方法」）。开关自身的正确性有独立用例 `HibernateHarnessModeTest`（5 条，纯函数，不连库）：钉住 MySQL 模式的 URL 与历史逐字节一致、PG 模式的三个兼容开关一个不少、方言只在 PG 下钉、未知取值失败而不是静默回退、NULL 序映射与方言一致——这几行的错误形态都是「默认通道照样全绿」，只能靠直钉。

第一次实测（H2 侧 535 条）：**25 红，其中 16 条指向 oracle 而非库**——排序/分组/关联差分的内存侧内建了 MySQL 的「ASC 时 NULL 在前」，PG 是 NULLS LAST。这 16 条按「一致性基类保持后端中立」那条红线属于违规，已修（见下「NULL 序参数化」），不是被当成「已知差异」记下来的。

第二次实测（H2 侧 539 条，修完 R1 之后的基线；此后新增方言开关自身的 5 条纯函数用例，现为 540 条）：**8 红，全部可归因，零生产缺陷**——3 条是 SQL 文本快照/渲染（`ItemSqlSnapshotTest` / `ItemProjectionMapperRenderingTest` / `ItemTier1RenderingTest` 各一格），快照钉的是字面量 SQL，换方言必然不同，这不是缺陷；5 条是锁模式（`PostgreSQLDialect` 给 `PESSIMISTIC_WRITE` 渲染 `for no key update`、给 `PESSIMISTIC_READ` 渲染 `for share`，H2 的 PG 兼容模式不认这两条语法，真 PG 接受），其中 `ItemQueryEntrySnapshotTest` 的两条失败按 cause 归这一类（两条 cause 都是 `for no key update`），不属于快照类。其余 531 条（现 532 条；含全部谓词格、排序、分组、关联、窗口、写侧往返）在两个方言下断言逐字相同——**这才是「换后端也通过」的证据**，快照与锁模式两类明确排除在外。`Oracle` 模式没做：H2 的 Oracle 兼容模式覆盖不到 Oracle 的实质差异（空串即 NULL、`NUMBER` 精度），做了只会给假证据。

第三次实测（H2 侧 558 条，补完写侧维度用例之后）：**10 红**，上一轮那 8 条一条不多一条不少，新增的 2 条都在 `TemporalWriteContract`（`insertAndReloadPreserveTemporalValues`、`updateWritesAllTemporalColumns`）：`Instant` 写进去 `2024-03-01T10:15:30Z`、读回 `2024-03-01T02:15:30Z`，正好差一个 JVM 时区（+08:00）。**这不是生产缺陷，已用纯 Hibernate 对照证伪**：同一个实体在 PG 模式下不走 `nextentity`、直接 `em.persist` + `em.find`，一样差 8 小时，原生查询取回的原始值是 `OffsetDateTime 2024-03-01T10:15:30+08:00`；两种模式建出来的列类型都是 `timestamp with time zone(6)`（`information_schema.columns` 实测），差异只在 `PostgreSQLDialect` 对 `Instant` 的绑定路径与 H2 的 PG 兼容模式——真 PostgreSQL 的 `timestamptz` 存的是时刻，不会有这个偏移。归入「H2 的 PG 兼容模式不认 PG 语义」这一类（与锁语法同因），不改成跳过、也不削弱断言：默认通道（H2 的 MySQL 模式 + 真 MySQL 容器）两处都逐字通过。
第四次实测（H2 侧 563 条，补完 reviewer 的两轮整改之后）：**8 红，回到「3 快照 + 5 锁」，零生产缺陷**。上一轮那 2 条 `Instant` 红已消掉，但**不是靠跳过**：`TemporalWriteContract` 里 `Instant` 的往返断言改成按方言分派（[TemporalWriteContract#insertAndReloadPreserveTheInstant](nextentity-tests-jpa/src/test/java/io/github/nextentity/tests/jpa/TemporalWriteContract.java) 与互斥的 [TemporalWriteContract#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode](nextentity-tests-jpa/src/test/java/io/github/nextentity/tests/jpa/TemporalWriteContract.java)：能保时刻的通道钉**相等**，PG 兼容模式钉「丢掉的偏移恰好是一个 JVM 时区偏移」——**当轮**用的是「漂移量小于 24 小时」的容忍带，第五次实测已换成精确断言，见下条），于是两种模式下这条用例都在跑、都在断言，skip 计数不变；写成 `assumeTrue` 会让该通道少一条证据且不进任何计数，正是「方言跳过必须可数」要防的那种静默降级。其余 555 条在两个方言下断言逐字相同（当轮 0 skip，`563 − 8 = 555`；第五次实测多出一对互斥用例，口径随之变成 `564 − 8 − 2 = 554`，见下条）。

第五次实测（H2 侧 564 条，第三轮审查整改之后）：**仍是 8 红、仍是「3 快照 + 5 锁」，零生产缺陷**（`ItemSqlSnapshotTest` / `ItemProjectionMapperRenderingTest` / `ItemTier1RenderingTest` 各 1 条，`ItemLockConsistencyTest` 3 条、`ItemQueryEntrySnapshotTest` 2 条），另有 1 条**严格相等**用例按标记 skip（互斥对的另一半，PG 专用那条在 PG 通道是跑的）。这次把 `Instant` 那条从「容忍带」换成**精确断言**：先实测漂移量——PG 兼容模式 `driftSeconds=-28800`、`offsetSeconds=28800`，恰好 `-1 ×` JVM 偏移；H2 的 MySQL 模式 `driftSeconds=0`、读回原值——于是 PG 分支钉 `drift == -offset`。上一轮那个「漂移小于 24 小时」覆盖 UTC−12…UTC+14 全部合法偏移，对「`Instant` 被时区挪动」零区分力，属于假断言，已废弃。严格相等那条与 PG 专用那条用 `@DisabledIfSystemProperty` / `@EnabledIfSystemProperty` 互斥，**两个通道的 skip 计数恒为 1**，且两条都在报表明面上。其余 554 条在两个方言下断言逐字相同。

> 口径提示：上面「第 N 次实测」里的 563 / 564 / 536 是**当轮**的条数，不是当前值；当前条数以「通道拆分」表的 655 / 590 为准（差异来自后续补的差分 oracle 与写侧维度用例）。


**NULL 序参数化（R1，已落，方言中立那一格补齐）**：`ORDER BY x` 里 `null` 排前还是排后是**后端事实**，不是可推导语义，所以两处都改成显式传入、默认值 = MySQL：

- 分组/排序差分族（`ConsistencyTestBase`）：protected `nullOrdering()`，默认 `NULLS_FIRST`；`SortDifferentialTest` 的排序 oracle、`HavingDifferentialTest` 的分组 oracle 全部读它。
- 关联嵌套路径族（`AssociationDifferentialTest`）：构造器注入 `NullOrdering`（不设可覆写方法——这一族的方法数已顶到 PMD 的 `TooManyMethods` 上限，参数走构造器既不占方法位也不会被忘掉）。
- 两个族的 JPA 适配器从同一处取值：`HibernateHarness#nullOrdering(H2Mode)`（`MYSQL` → `NULLS_FIRST`，`POSTGRESQL` → `NULLS_LAST`）；无参 `nullOrdering()` 只负责读 `-Dnextentity.h2mode` 再转调它。
- **容器通道的差分类不读属性**，各自钉死 MySQL：`MySqlItemSortDifferentialIntegrationTest` / `MySqlItemHavingDifferentialIntegrationTest` 覆写 `nullOrdering()` 返回 `NULLS_FIRST`，`MySqlItemAssociationDifferentialIntegrationTest` 构造器传 `HibernateHarness.nullOrdering(H2Mode.MYSQL)`。它们连的是真 MySQL，与 H2 兼容模式开关无关；带 `-Dnextentity.h2mode=postgresql` 跑容器通道时若沿用属性值，排序/分组 oracle 会用 `NULLS_LAST` 去比真 MySQL 的 `NULLS_FIRST`，整族假红。只有 H2 侧的 `ItemSortDifferentialTest` / `ItemHavingDifferentialTest` / `ItemAssociationDifferentialTest` 读属性。

默认值仍是 MySQL，所以既有用例的断言逐字未变；接第二后端时只覆盖适配器那一行，不许放宽成「只钉非空部分」。

**L4 的写侧关联语义（3.1.0 已改并补容器证据）**：3.0.0 的 `update` 一律跳过关联列（当时只有 H2 证据：`JpaPersistAssociationTest` 对 `customer_id` 的 `.doesNotContain(...)` 片段断言，容器通道一条都没有），这正是「关联写不进去 / 新会话读回为空」的真实成因；把跳过条件改坏，H2 会红、真 MySQL 通道静默放过。3.1.0 改为：本表持外键的单值关联（`@ManyToOne` 与持有侧 `@OneToOne`）从关联对象 `@Id` 写外键、`null` 关联在 JPA 侧写 `SQL NULL`，集合关联与 `mappedBy` 反侧仍跳过。容器对端是 `MySqlAssetWriteAssociationIntegrationTest` 与 `PostgresAssetWriteAssociationIntegrationTest`（各 4 条）：语句层断 `SET` 里出现 `owner_id=?`（只断言「出现该列」挡不住「拿旧值原样回写」），结果层再断库里的外键变成**新**关联的主键；另断「`null` 关联写 `NULL` 把外键清掉」以及 `insert` 正向对照。H2 侧的 `JpaPersistAssociationTest` 保留「集合 / join 表关联跳过」与 `owningAssociation` 分类判定。

**Spring 接线的容器变体（已补）**：`nextentity-spring` 的 `NextEntityRealBackendTest` 跑的是 H2，而「自动配置 + `SharedEntityManagerCreator` 的事务绑定代理 + 真方言」这一组合此前没有任何实证。现由 `nextentity-tests-jpa` 的 `MySqlSpringRepositoryIntegrationTest`（4 条，容器通道）补上：自动配置产出的工厂喂给 `AbstractRepository` 子类，在真 MySQL 上断三件事——回滚真丢数据（`setRollbackOnly` 后 `count()` 为 0）、本线程未提交的行对另一线程不可见（插入若被静默自动提交会立刻变红）、4 线程并发各查各的行。它用自己的实体 `SpringWiredEntity` 独占一张表，理由是**行集隔离**：多个容器类都在断言精确行集（`containsExactly`），共用一张表会互相污染行集。`create-drop` 不是理由——它只处理本 PU 映射的表，不会删别人的表，JUnit 默认也串行。这与 H2 版不是重复证据——H2 版证「代理是线程绑定的」，这里证「绑定的是真 MySQL 连接且提交边界正确」。

**执行凭证（`tools/gate.sh` + `tools/gate-receipt.py`，L0）**：前面每一条门禁都有一类共同的失效方式——**构建绿了，但该跑的通道没跑**。`-DskipITs`、无 Docker、`@Disabled`、PIT 的零变异、`~/.m2` 里的陈旧产物，五种都能让 `BUILD SUCCESS` 与「证据为零」同时成立，而人读日志只看最后一行。所以门禁的产物不是日志而是**凭证**：`tools/gate.sh <fast|ci|pr|night|release>` 跑完把 surefire/failsafe XML、静态检查报告与 PIT 报告一起解析成 `tmp/gate/<commit>-<ts>/receipt.json`，判定只看这份 JSON。

判据不是「报告存在」，而是七条：

- **报告比本轮新**：`mtime >= 本轮开始 epoch`。上一次构建留下的报告不算——这是「`Nothing to compile` 复用前序产物」的直接防线。
- **条数有下限**：`tests == 0` 的通道报「报告存在但 tests=0」，不报通过；每个通道另有**绝对下限**（`gate.sh` 的 `STEP_MIN_TESTS` 逐步申报，如 `surefire:nextentity-tests-jpa=655`），低于下限即红。只钉「方言通道与基础通道条数相等」不够——两边一起被缩到只剩那 8 条基线用例时相等仍成立，等于允许把矩阵悄悄削成空壳。下限是单调的：加用例不用改它，只有删用例才会撞红，正好是要人停下来解释的那个动作。
- **必需通道必须真跑**：`-DskipITs` 下 failsafe 一份报告都不新（目录里上一轮的 XML 还在，靠逐文件 mtime 过滤才抓得住，这也是 `--require` 必须点名通道的原因）、`-Dnextentity.requireContainer` 下容器类整体 error，两种都判缺口；档位声明的步骤没落快照（被 `-am` 守卫拦下）同样算缺口，见 `merge --expect-step`。
- **PIT 不许零变异**：除阈值外额外要求 `mutations > 0`，堵住根 pom `failWhenNoMutations=false` 那条假绿通道；分数按 PIT 自己的口径算并与 pom 的 `mutationThreshold` 比对。
- **静态检查也要进凭证**：checkstyle / PMD / SpotBugs 都绑在 `verify`，改一处
  `failOnViolation`、把 `violationSeverity` 抬高、或删掉某个模块 pom 里的插件 `<execution>` 块，
  `mvn verify` 照旧退出 0 而报告缺席或带违规——退出码在这件事上不是证据。所以 `gate-receipt.py`
  另立 `static:<模块>` 通道：违规条数由凭证自己数 XML（checkstyle 的 `error`、PMD 的 `violation`，
  被抑制的不算、SpotBugs 的 `BugInstance`），报告缺席 / 不新鲜 / 有违规三条任一即红。
  **期望集按模块形状推导**（`pom` 打包只要 checkstyle；有 `src/main/java` 要三件套；纯测试模块要
  checkstyle+PMD），**不读模块 pom 的插件配置**——后者正是漏洞本身：删执行块的同时期望也一起消失，
  检查等于没写。仓库明文规定的例外（`nextentity-tests-legacy` 是移植语料，跳过 checkstyle/PMD）
  以 `STATIC_EXEMPT` 常量列在凭证工具里，改它要走门禁脚本的 diff，而 CI 取的是基线分支的脚本版。
- **跳过要有申报预算**：每个通道的跳过条数上限由 `gate.sh` 逐步申报（`STEP_MAX_SKIP`），未申报的通道默认 0。跳过数超预算即红，且 problems 里逐个点名用例与理由——「跳过就是没跑，没跑不能记绿」。
- **预期失败必须逐条申报且会腐烂**：某通道上「按设计就会红」的用例不能靠 `@Disabled` 藏起来（那样既不跑、也看不出哪天修好），改由 `--expected-failures <file>` 声明（`gate.sh` 用 `STEP_EXPECTED_FAILURES` 逐步点名）。判据是**双向完全相等**：实际失败集多出一条 = 新回归（不是已知差异，不许夹带）；少一条 = 基线腐化（已修好却未删条目，或用例被删/改名）。基线条目不在 `--require` 里也判红：那是「申报了却被静默忽略」。当前唯一的基线是 `tools/expected-failures/h2-postgresql.txt`（9 条 PG 兼容模式方言差异，见上「通道拆分」）。五条配套约束，缺一条基线就会变成垃圾桶：
  - **失败性质参与比对**：每条带 `type=failure|error`。`failure` 是断言不符（方言渲染差异），`error` 是被测代码抛异常。只比用例名的话，某条已知差异哪天变成生产代码 NPE，名字不变、失败条数不变，凭证照旧判绿。
  - **租约与字段都收紧**：`type` / `issue` / `expires` 三个字段必填，其余字段一律报错（拼错的字段写了不生效还不报错，比没写更危险）；`expires` 用 `date.fromisoformat` 校验（只校验形状的话 `2026-13-45` 能过且字典序比较下永不过期），且不得超过今天 + 180 天——期限长到看不见就等于没有期限。空基线文件按配置错误处理：空基线等于没有基线。
  - **规模也要相等**：方言矩阵通道必须与基础通道跑同一批用例（`surefire-pg.tests == surefire.tests`）。只钉失败集不钉规模，等于允许把矩阵悄悄缩到只剩那几条预期红。同理，同名失败用例被折叠（`@ParameterizedTest` 的 display name 撞车）会判红——否则一条基线条目能盖住 N 条失败。
  - **必需通道必须列全反应堆，不是只列目标模块**：`-pl nextentity-tests-jpa -am` 会把 api/core/jpa/spring 一起拉进来跑测试，它们的 surefire 报告同样落盘。只点名 tests-jpa 的话，那四个模块的失败**不进凭证**（不在 `required` 里，失败检查那段根本不执行），而 mvn 的非零退出又被下面的基线降级分支当成「预期内」——真回归由此静默变绿。所以每档的 `--require` 都按 `-am` 实际反应堆列全，配 `STEP_MIN_TESTS` 卡住条数。
  - **申报基线的步骤不许 fail-fast，且豁免必须绑在 execution 上**：PG 那遍按设计必然红，默认 fail-fast 会让 `nextentity-tests-jpa` 的 checkstyle / PMD / SpotBugs / jacoco:report（全绑在 `verify`）**一次都不执行**——「静态检查根本没跑」会被「测试红是预期内」顺带盖过去。放行方式写在 `nextentity-tests-jpa/pom.xml` 的 `pg-dialect` execution 里（`<testFailureIgnore>true</testFailureIgnore>`），**不能**用 `-Dmaven.test.failure.ignore=true`：后者是全局 user property，会把同一次反应堆里 api / core / jpa / spring 的 surefire 失败一并吞掉（实测：那一遍 fail 4 error 4，mvn 仍 exit 0）。绑在 execution 上，只有 PG 兼容模式那一遍忽略失败，其余模块照旧 fail-fast。判定权完全交给凭证：mvn 退出码为 0 时只看基线逐条比对；退出码非零则必须同时满足「失败全部来自 surefire」且「receipt 判绿」才按预期内解释，否则照样红。

三件事只有凭证能给出、日志给不出：**skip 清单连理由**（`方言跳过必须可数` 从「PR 里手写」变成自动抽取）、**`~/.m2` 与源码的时间戳倒挂**（`-pl` 不带 `-am` 时会读到旧语义）、以及**跨模块合并口径的条数**（`-am` 反应堆会把 jpa/spring 一起带进来，比单看目标模块多）。

档位与「该跑什么」一一对应：`fast` = 格式化检查 + 架构规则 + `api`/`core` 快测试（无容器；跑 `test` 阶段，故不含静态检查——静态检查只在 `verify` 上，落点在 `pr` 及以后各档）；`pr` = 架构规则 + H2 645 + PG 兼容模式 655（基线 9 条）+ 遗留 727（`nextentity-arch` 不在 tests-jpa 的依赖链上，`-am` 带不上它，必须显式写进 `-pl`；漏了就是「提交前红、推之前绿」）；`ci` = 前者 + 真 MySQL 容器通道 + `-Dnextentity.requireContainer=true`；`night` = `install` 刷快照 + 全量含容器 + 遗留 h2/mysql 双跑 + PIT×5；`release` = `clean verify` + api-compat。**`pr` 与 `ci` 都不覆盖遗留语料的 MySQL 通道**：两档的遗留步骤**都不传 `-Ddbc`**，`Env` 在未设置时退化为 `List.of("h2")`，于是只有 H2 一遍（PR 期换 30 秒级反馈），那条通道最早在 `night` 才有结论——所以「遗留方言回归」的反馈延迟是「最长到次日 02:00」，本地要提前看必须自己跑 `-Ddbc=h2,mysql -Dnextentity.requireContainer=true`。`ci`/`pr` 刻意不跑全量 PIT（单模块分钟级，不该挡 push），PIT 的落点在 `night` 与 `release`。
**唯一例外是 `ci` 档多跑一个 `nextentity-core` 的 PIT**（`: ci-pit-core`）。它买到的是**反馈时延**，不是
「堵住一个漏洞」——这一点初稿写错过，这里改正：等价变异台账是工作树里的数据文件，与被它豁免的用例、生产代码
同源，**没有独立锚点**。因此「PR 里删一条断言、同时给对应变异体加一行台账」这条路径**在哪一档校验都同样绿**，
包括夜间档——`_check_equivalents` 读的是工作树台账、只比集合，把校验提前到 `ci` 并不会让它变红。真正的堵法只有
「台账需要独立锚点」（例如变异体指纹由 PIT 报告与台账外的第三方清单双向约束），当前**没有**实现，属已知缺口。

`ci-pit-core` 的实际收益只有两条，都值这个成本，但都不是安全边界：① core 台账与当次 PIT 报告在同一档里校验，
台账腐化（新增未杀死变异体却没登记）在 PR 阶段就红，而不是等到夜里；② `core` 是变异体最多、台账最长的模块，
它同时把「PIT 例外路径必须先刷 `~/.m2`」这条守卫放进每一条 PR 的执行路径里。`jpa`/`api`/`spring` 仍只在
`night`/`release` 校验，加上上面的自指缺口，这两条残余风险都是已知且明写的，不是漏掉。

**尚未实施**（方向，不是已有能力——写进这里的东西不得当成现状引用）：

- ~~排序差分的方言化~~（已完成，见上「NULL 序参数化」）：`NULLS FIRST` / `NULLS LAST` 已是显式参数，接第二后端只需在适配器里覆盖一行。
- 故障注入常驻化：现在只是两次一次性实验（12 条 + 35 条手工注入），还没进流水线；连接中断、锁超时、方言不支持路径这三类也还没注入过。
- 变异测试的历史文件（`historyInputFile` / `historyOutputFile`）：现在每次都全量重算，接上增量后夜间档可以更快。
- L2 的参数绑定取值：`StatementInspector` 只拿得到语句文本，`where id = ?` 的两个占位符互换后 SQL 逐字节相同。唯一"文本相同而结果不同"的组合是 `single()` + `lock`，已补成同时断返回行（见上「L2 只证明…」一段）；其余位置由 L3 真库结果兜底。
- **凭证管不到的三处绕过面**（承认，不假装已堵）：① `tools/gate.sh` 与 `tools/expected-failures/*.txt` 都是工作树里的文件，CI 只把 `gate.sh` 换成基线分支的版本，基线数据文件与 `pom.xml` 没钉——「改哑 PG profile + 清空基线」这类组合拳挡不住，只是空基线会先被判配置错误；② 删用例只能靠 `STEP_MIN_TESTS` 的绝对下限挡，而下限本身写在 `gate.sh` 里、会随版本一起被改（下限调低与删用例同时做就看不出来）；③ 跨一次合并的「先提交弱门禁、下次由弱门禁把关」路径无解——它需要 CODEOWNERS 或分支保护，属于仓库设置而不是脚本能力。这三条都靠 review 兜，不要当成已解决。

**golden schema 夹具（3.1.0）**：能力证据不能只靠 `Item` 夹具——它只有 `Long`/`String`/`Integer`/枚举等少数几种字段，3.0.0 的 JDBC 类型白名单（CUT-11）就是照它的形状裁的。3.1.0 在 `nextentity-testing/src/main/java/io/github/nextentity/testing/golden/` 新增 `GoldenSchema`（三张贴近真实的表 `golden_customer`/`golden_order`/`golden_order_line`：`DECIMAL(12,2)`、`BOOLEAN`、`Instant`/`LocalDateTime`、`@Enumerated(STRING)`、`@Convert`、`@Embedded`、`@Version`、`@ManyToOne`/`@OneToMany`）与 `GoldenFixtures`；JPA 侧 `nextentity-tests-jpa` 的 `golden/` 套件、JDBC 侧 `nextentity-tests-jdbc` 的 `golden/` 契约与落地套件都建在它上面，DDL 按 H2/MySQL/PostgreSQL/SQL Server 各一份维护（`nextentity-tests-jdbc/src/test/resources/schema-*.sql`）。它替换的是「把 `Item` 加宽」的做法：`Item` 是差分/变形 oracle 的底座，改字段会动摇既有期望；golden schema 只承担「能力面能不能落库」的类型覆盖。

**与 §6 的关系**：L3 是第二后端的验收口——新后端必须原样通过同一套一致性用例，不得为它新增特例分支（见 §6 第 8 条）。

---

## 6. 第二后端（JDBC）接缝

**已兑现**：`nextentity-jdbc` 是 `core` 接缝的第二实现（纯 JDBC，内置 **H2 / MySQL / PostgreSQL / SQL Server** 四种方言，且方言本身是开放接缝——三方数据库实现 `JdbcDialect` 即可接入，不需要改 `jdbc` 模块）。八条接缝全部落地：

1. `Spec` 不含后端类型（纯值对象）。
2. `Compiler<S>` 泛型化：JPA 产出 Criteria 持有者，JDBC 产出 `{sql, bindings}`（`JdbcCompiler` → `JdbcQuery`）。
3. `Executor<S>` 与 `Compiler<S>` 成对出现，终止符都在接口层；`JdbcCompiler` 额外把 `forCount`/`forExists` 之后的规格渲染成计数/存在语句（见 §7 决策表对应行）。
4. `Meta` 接口化：JPA 实现走 `EntityManager.getMetamodel()`，JDBC 实现走 `jakarta.persistence` 注解反射（`JdbcMeta`，不带 Hibernate）。
5. `LockMode` 用自研枚举，不引 `jakarta.persistence`；JDBC 把 `PESSIMISTIC_WRITE` 映射成 `for update`、`PESSIMISTIC_READ` 映射成 `for share`（H2 无 `FOR SHARE`，用 `for update` 兜底），其余模式在契约层拒绝（读锁于 3.1.0 恢复，对应 §7 CUT-09）。
6. `PersistExecutor` 接口化（insert/update/批量）；JDBC 用方言声明的生成键策略回填自增主键、`WHERE id=? [AND version=?]` 实现乐观锁并回写新版本号。
7. 事务边界由调用方持有：JDBC 默认自动提交，需要事务就由调用方 `setAutoCommit(false)`/提交，后端不自带事务模板。
8. `nextentity-testing` 的一致性基类只依赖接口，`nextentity-tests-jdbc` 原样继承同一批基类（31 个基类 × 三方言接线 = 93 个接线类，H2 与真 MySQL / PostgreSQL / SQL Server 各跑一遍），没有后端特例分支。

### 6.1 方言接缝：`JdbcDialect` 的渲染钩子

`JdbcDialect` 是**公开 API**（`io.github.nextentity.jdbc.dialect`），职责是「SQL 方言差异的**唯一**集中点」，不是名字标签。接口共 10 个成员（静态 `of(String)` + 9 个实例钩子），除必答题 `name()` 外全部有默认实现，默认形态 = H2 / MySQL / PostgreSQL；四个内置方言都覆写 `quote`（H2/PG `"…"`、MySQL 反引号、SQL Server `[…]`），SQL Server 另覆写 `selectHead` / `limitClause` / `lock` / `averageOf` / `likeEscape` / `lengthOf`：

| 钩子 | 方言差异 | H2 | MySQL | PostgreSQL | SQL Server |
|---|---|---|---|---|---|
| `name()` | 方言名（`h2`/`mysql`/`postgresql`/`sqlserver`），用于日志与错误消息 | h2 | mysql | postgresql | sqlserver |
| `quote(String identifier)` | 标识符引用：**默认裸名（恒等）**；只有 meta 标记 `quoted=true`（注解名被反引号包裹）的标识符才调用 → 按方言渲染：H2/PG `"…"`、MySQL 反引号、SQL Server `[…]`。调用点只有两类：查询侧 4 处（表名/列名/join 目标表/join 列）+ 写侧（INSERT 表名·列名、UPDATE 表名·SET 列·WHERE id/version 列），写侧同样只对 quoted 生效；DDL 不由模块生成（§6.2） | 默认恒等 | `` `…` `` | `"…"` | `[…]` |
| `selectHead(String columns, boolean exists)` | SELECT 头 | `select <columns>` | `select <columns>` | `select <columns>` | exists → `select top (1) <columns>`，普通查询 `select <columns>` |
| `limitClause(Window, boolean exists, boolean hasOrderBy, SqlParameterSink)` | 语句尾部窗口片段 | `limit ? [offset ?]`；窗口缺省时不追加 | 同 H2 | 同 H2 | `offset ? rows [fetch next ? rows only]`；`hasOrderBy=false` 时先补 ` order by 1`；exists 不追加 |
| `lock(LockMode, List<String> aliases)` | 行锁片段（返回 `LockClause(tableSuffix, statementSuffix)`；`aliases` 里根别名在前），按模式区分：`PESSIMISTIC_WRITE` → `for update`；`PESSIMISTIC_READ` → `for share`（3.1.0 恢复） | ` for update`（读模式同为 ` for update`：H2 无 `FOR SHARE`） | ` for share` | ` for share` / ` for share of <根别名>` | 抛 UOE（autocommit 下 `with (rowlock)`/`with (updlock)` 语义不等价；MSSQL 不做行锁的裁决未变） |
| `averageOf(String operand)` | 平均值表达式 | `avg(x)` | `avg(x)` | `avg(x)`（PG 的 `avg(int)` 返回 numeric，无需转换） | `avg(cast(x as float))` |
| `likeEscape()` | 追加在 `like ?` 后的转义子句 | 空串 | 空串 | 空串 | ` escape '\'` |
| `substring(String operand, int position, Integer length)` | 3.1.0 加性钩子：`SUBSTRING` 形态。`position` 是 **1 起算**（调用方已把 API 的 0 起算偏移 +1），`length == null` 表示「截到结尾」 | `substring(x, pos)` / `substring(x, pos, len)` | 同 H2 | 同 H2 | `substring(x, pos, 2147483647)`（T-SQL 必须 3 参；用 int 上界表达「到结尾」，**该上界形态尚待 SQL Server 容器实测**；见 USAGE §13.4） |
| `lengthOf(String operand)` | 3.1.0 加性钩子：字符串长度表达式 | `length(x)` | `length(x)` | `length(x)` | `len(x)`——**`LEN` 会裁掉尾随空格，与 `LENGTH` 不等价**，属「同表达式两后端结果不同」的语义差异，已披露在 USAGE §13.4 |

**自定义方言接入**（用户明确要求的能力）：实现 `JdbcDialect`（只有 `name()` 是必答题，其余用默认实现即可起步），然后：

```java
JdbcDialect myDialect = new MyDialect();                     // 自研方言
EntityOperationsFactory factory = new JdbcEntityOperationsFactory(dataSource, myDialect);
```

`JdbcEntityOperationsFactory(DataSource, JdbcDialect)` 是**显式注入**路径：不探测、不自动注册，也是唯一保证「用户自定义渲染真的生效」的路径。单参构造器 `JdbcEntityOperationsFactory(DataSource)` 在构造期取一条连接、读 `DatabaseMetaData.getDatabaseProductName()`、交给 `JdbcDialect.of(String)` 探测（忽略大小写；`PostgreSQL 16.2` / `Microsoft SQL Server 2022` 按前缀容错；`MySQL`/`H2` 全等）；**探测不到时 `JdbcDialect.of` 抛 `UnsupportedOperationException`，消息列出内置四种方言并引导用 `(DataSource, JdbcDialect)` 传入自定义实现**——不猜、不回退到默认方言。

**不变量（自定义方言也必须遵守）**：① 参数只能经 `SqlParameterSink.append(...)` 追加，不得把字面量拼进 SQL（防注入 + PIT 可覆盖）；② 锁只接受 `PESSIMISTIC_WRITE` 与 `PESSIMISTIC_READ`（读锁 3.1.0 恢复），其余三种模式在 `JdbcCompiler` 层就 `UnsupportedOperationException`；③ 生成的 SQL 必须让 `JdbcExecutor` 按 `QuerySpec` 的投影列数读回（钩子不得改 SELECT 列表的**内容**，只允许 `selectHead` 加 `top (1)` 这类行数上限）；④ `limitClause` 只追加语句**尾部**，SELECT 头的方言差异归 `selectHead`，两钩子不交叉；⑤ `quote` 只允许上述两类调用点，且**只对 `quoted=true` 的标识符**调用（未 quoted 恒裸名，默认方言不产生任何引号 → 既有 SQL 快照零改动）；新增调用点要同步改本文件。

**标识符引用（保留字 / 存量表，v5）**：注解名（`@Table/@Entity(name)`、`@Column(name)`、`@JoinColumn(name|referencedColumnName)`）**被反引号包裹 ⇒ 需要引用**；未包裹 ⇒ 裸名。反引号是跨后端**标记**，渲染字符由方言决定：JDBC 侧 H2/PG `"…"`、MySQL 反引号、SQL Server `[…]`；**JPA 侧同一规则由 Hibernate 原生支持，生产代码零改动**（H2 上 Hibernate 实测渲染 `"…"`，与 JDBC 的 H2 方言逐字节一致；MySQL 两侧都是反引号）。**JPA 的承诺范围只有 H2 + MySQL**：JPA + PG/MSSQL 未验证、不在矩阵（Hibernate 在该两库上的引用形态属推断），实测失败形态 PG `must appear in the GROUP BY`、MSSQL `INVALID_OBJECT_NAME` 记入已知限制，不据此改 JPA 生产代码、也不引入 `columnQuoting` 类第二套配置。MSSQL 方括号只存在于 JDBC `quote()`。meta 归一化：剥引号存裸名 + `quoted` 标志，所有名字比较/生成键回读用裸名；测试侧 DDL 按方言引号建表（`schema-h2.sql` 用 `"`、`schema-mysql.sql` 反引号、`schema-postgres.sql` `"`、`schema-sqlserver.sql` `[]`）。

**升级指南**：`jdbc` 是 3.0.0 首发模块，无 2.x 兼容负担。相对设计期草稿的唯一破坏点是 `JdbcDialect` 的接口形态（草稿里是「两个方法 + 名字」，定稿是上表 9 个实例钩子 + 静态 `of(String)`，共 10 个成员）——照草稿写过自研方言的消费方需按上表补 `limitClause`/`lock` 的签名；3.1.0 新增的 `substring`/`lengthOf` 两个钩子带默认实现，**加性、不需消费方改源码**。`JdbcEntityOperationsFactory(DataSource)` 单参构造器保留，只装默认路径的代码不受影响。


**装配面契约：`DataSource` 的 `getConnection()` 语义**。`JdbcEntityOperationsFactory` **不持有连接**；`JdbcExecutor` / `JdbcPersistExecutor` 每次操作都向 `DataSource` 取连接、在 try-with-resources 里 close（设计意图：后端不管理事务，见第 7 条）。因此消费方的 `DataSource` 合约是「`getConnection()` 每次返回可用连接（池化，或每次新建）」**或**「返回的包装连接 `close()` 是 no-op」（Spring `SingleConnectionDataSource` 一类需自行包成 no-op 代理）。反例（reviewer 探针实测，非生产缺陷）：把一条物理连接包成「close 真关」的数据源，第二条 SQL 即报 `The object is already closed`；`nextentity-tests-jdbc` 的 `SingleConnectionDataSource` 按 no-op 代理实现，不受影响。

**SQL 渲染面的边界：LIKE / `contains` 的绑定语义**。JDBC 后端只有一条渲染路径：`contains(v)` 的模式串 `%v%` 由 `core` 的 `Specs.contains` 预先拼好，`JdbcCompiler` 不再拼接、也不改通配符——`%`/`_` 一律**不转义**，与 JPA 一致（`contains("%")` 仍是通配匹配，2.2.3 语义不变）。唯一改写是**绑定前把字符串字面量里的 `\` 翻倍**（`\` → `\\`），使反斜杠按字面解释，与 JPA 后端口径等价：Hibernate 在 H2 上追加 `escape ''`、在 MySQL 上把占位符包成 `replace(?, '\\', '\\\\')`，效果相同。这层转换只发生在 `JdbcCompiler` 的绑定阶段，`Spec` 与共享一致性基类保持后端中立（对应快照 `containsDoublesBackslashesBeforeBinding` / `likePatternDoublesBackslashesBeforeBinding` / `wildcardsInLikeValuesAreLeftUnescaped`）。

准入条件（原三条）已由 jdbc 分支兑现：出现 Hibernate 无法满足的明确需求（纯 JDBC、无 Hibernate 依赖的消费方）；不需要 Criteria 原生 SQL 逃生口（`spec` 仍零原生 SQL 片段）；能通过一致性测试（同一批基类 + H2/MySQL 双通道）。

**新后端的接线清单**（照 `nextentity-jdbc` 抄，顺序即依赖顺序）：模块 pom（enforcer 禁其它后端 + pitest 阈值 95）→ `nextentity-bom` + 根 pom `modules`/`dependencyManagement` → `nextentity-arch/pom.xml` 加 test 依赖并加 `JDBC_IS_A_BACKEND_ONLY` 等规则（ArchUnit 只看 classpath，不加依赖时规则匹配空集=假绿）→ `nextentity-testing` **不动**，只新增 `nextentity-tests-<后端>` 继承基类 + 方言容器通道 → `tools/gate.sh` 注册通道/档位/skip 预算 → AGENTS 验证表 + 本文件 §2/§5/§6/§7 + CHANGELOG。

---

## 7. 决策与否决

本节是**可审计台账**，不是理由清单。2026-10 起，每条「能力面裁剪 / 否决」必须回答四个问题，缺一即视为未定稿：

1. **理由是什么**；
2. **理由类型**——**外部约束**（库外的事实，如驱动/数据库/JPA 规范限制或存量消费方）/ **尚未验证**（当前语料没覆盖，不等于没有需求）/ **自设规则**（本库自己的契约或设计偏好），三选一；
3. **不支持的实际代价**——具体例子 + 用户可走的替代路径；
4. **revisit 条件与触发器**——什么客观信号出现时必须重审。

`尚未验证` 类理由是**证据缺席**，不是需求缺席：「point-mall 0 调用点」「共享套件没有正向用例」「PIT 变异杀不死」只证明当前证据面没覆盖，不证明没人用。3.1.0 恢复的一批能力正是按此重审的结果：2.2.3 自带的 `nextentity-examples`（37 个 Java 文件 = 20 主 + 17 测）和双容器集成测试一直用着它们，而该语料从未进入 3.0.0 的证据面——「0 调用点」是拿一个被整体删除的语料证成的循环论证。下表已恢复的条目写「已在 3.1.0 恢复」并指向 [CHANGELOG](../CHANGELOG.md) 对应条目；恢复项另在 §7.3 逐条列**端到端归属**（api 声明 → core 节点/编译 → 后端渲染 → 测试证据），四段缺一即视为「节点在、能力没提供」；**已恢复条目的 revisit 触发器统一为**「恢复后出现跨后端行为不一致、或 §7.3 归属中任一环节缺测试证据时重审」，不再逐行重复；未恢复的条目必须自带代价与触发器。

### 7.1 能力面裁剪台账

| 条目 | 决策 | 理由（类型） | 不支持的实际代价与替代路径 | revisit 条件与触发器 |
|---|---|---|---|---|
| CUT-01 | 删除显式 `fetch(...)` 预取 | **尚未验证**：point-mall 0 调用点，共享套件无 fetch 正例 | 关联访问退化为运行期惰性加载，N+1 静默出现（无编译错误、无告警）；替代：`@EntityGraph`、显式投影或手写 join 查询——三种都要离开本库 DSL | **已在 3.1.0 恢复**（CHANGELOG「Added → `fetch(...)` 预取」；2.2.3 examples 5 处调用点 + 3 个集成类）。触发器已触发 |
| CUT-02 | 删除 `select(Class)` DTO/record 投影，只留显式 `ProjectionMapper` | **自设规则**（列投影入口只做扁平）：原裁理由是「反射方案编译期零保护」；恢复时把嵌套/改名归 `ProjectionMapper`——列投影入口不塞第二层 join 语义，职责分离 | 目标类型必须能按**扁平**属名匹配（record 构造器参数 / 普通类 `setXxx`）；2.2.3 examples 的 `EmployeeWithDept`（`@EntityPath("name")`/`@EntityPath("department.name")`）不能原样 `select(Class)`，要改写为扁平 DTO 或 `ProjectionMapper`（后者 `expressions()` 可放嵌套路径，能力等价、形状不同）；接口投影不提供（要运行期代理） | **已在 3.1.0 恢复**（CHANGELOG「Added → `select(Class)`」；扁平映射 + `distinct().select(Class)` 表达去重）。revisit 触发器：真实消费方迁移时报「需要嵌套 `select(Class)`」 |
| CUT-03 | 删除条件更新 `update().set(...).where(...).execute()` | **尚未验证**：point-mall 0 调用点 | 批量改只能「先查出来逐行改」= N 次查询 + N 次写入；替代：原生 SQL | **已在 3.1.0 恢复**（CHANGELOG「Added → 条件更新」；examples `EmployeeRepository.java:85-88`） |
| CUT-04 | 删除条件删除 `delete().where(...).execute()` | **尚未验证**：同上 | 批量删只有逐行删或原生 SQL；替代：原生 SQL | **已在 3.1.0 恢复**（CHANGELOG「Added → 条件删除」；examples `:129-132`） |
| CUT-05 | 删除 `startsWith` / `endsWith` / `notLike` / `notStartsWith` / `notEndsWith` / `notContains` 及 `*IfNotNull` 变体 | **自设规则**：`like("v%")` 可等价表达 | 调用方自己拼模式串、处理 `%`/`_` 转义与 NULL 语义；模式写错只会静默返回错误结果；替代：手写 `like(...)` | **已在 3.1.0 恢复**（CHANGELOG「Added → 字符串算子」；examples `:237`/`:242`/`:252`/`:257`/`:262`/`:267`） |
| CUT-06 | 删除 `lower()` / `upper()` / `trim()` / `length()` / `substring(...)` | **尚未验证**：共享套件无字符串函数用例 | 条件只能退化为 Java 侧过滤 = 全表加载；替代：`like` 只覆盖大小写不敏感近似，不覆盖长度/截取语义 | **已在 3.1.0 恢复**（CHANGELOG「Added → 字符串函数」） |
| CUT-07 | 删除 `groupBy(...)` 之后的 `count()` / `exists()` / 分页 | **尚未验证**：共享套件无正例（2.2.3 的 having 在 groupBy 之后，与 3.x `HavingStep` 同名不同义，步骤链无法原样移植） | 分组统计只能全量 `list()` 后在 Java 侧数；替代：`list()` + Java 计数 | **已在 3.1.0 恢复**（CHANGELOG「Added → groupBy 之后的 count/exists/分页」；2.2.3 `HavingClauseIntegrationTest` 有 `.count()`/`.exists()`/`.list(2)` 正例） |
| CUT-08 | 删除 `Slice.offset()` / `limit()` | **自设规则**：`Slice` 只承担 `data`/`total`/`to` | 分页回显与游标计算拿不到窗口参数；替代：调用方自己保存 offset/limit | **已在 3.1.0 恢复**（CHANGELOG「Added → `Slice.offset()` / `limit()`」；examples 测试 `PaginationOrderingIntegrationTest.java:273-274`/`:296-297`） |
| CUT-09 | `LockMode` 只映射 `PESSIMISTIC_WRITE`，其余四种拒绝 | **外部约束**（JDBC 没有可移植的 `FOR SHARE` 等价语法，方言不一）+ **尚未验证**（无共享锁正例） | 读多写少场景被迫升级写锁（锁竞争、吞吐下降）；替代：无锁一致性读或原生 SQL | **3.1.0 恢复 JDBC 侧读锁并补齐 JPA 侧验证/文档**：JPA 自 3.0.0 起已用 `LockModeType.valueOf(spec.lock().name())` 映射五种模式（本版未改代码，只补用例与文档）；JDBC 由方言 `lock` 钩子渲染（MySQL/PostgreSQL `for share`，H2 无 `FOR SHARE` 用 `for update` 兜底——更强锁，语义安全但不等价），SQL Server 仍 UOE（外部约束：无标准 `FOR SHARE` 形态，`with (rowlock)` 是索引提示、未经实测）、未识别方言显式拒绝、不静默降级（CHANGELOG「Added → `PESSIMISTIC_READ`」） |
| CUT-10 | `lock(...)` 返回终止态 `Collector`，与分页互斥 | **自设规则**：`lock` 是终止符，`Collector` 不带窗口 | 锁行集只能整表取回（大表等于全表锁读）；替代：先分页再按 id 二次加锁 | **已在 3.1.0 恢复**（在 `Collector` 上加 `default` 分页方法，`lock(...)` 返回类型不动；CHANGELOG「Added → `lock(...)` 之后的继续分页」） |
| CUT-11 | JDBC 列类型白名单只有 `String`/`Integer`/`int`/`Long`/`long`/枚举；时间类型全拒 | **尚未验证**（共享夹具只有这六种字段，白名单就是夹具的镜像）+ **外部约束**（MySQL 驱动不支持 `getObject(Instant.class)`，这一条只约束 Instant） | 2.2.3 examples 的实体（`BigDecimal`/`Boolean`/`LocalDateTime`）全部无法用 JDBC 后端；替代：字符串列 + 手动转换，或换 JPA 后端 | **已在 3.1.0 恢复**（数值/布尔/时间/`java.sql.*` + 值转换层；CHANGELOG「Added → JDBC 列类型与值转换」）。仍拒绝：`byte`/`Byte`、`char`/`Character`、`byte[]`（尚未验证：`char` 的固定长度补空格语义、`byte[]` 的 BLOB/VARBINARY 各方言类型名与流式读写均未实测）。触发器：给出上述类型的跨方言形状与容器用例 |
| CUT-12 | 拒绝 `@Embedded` / `@Convert`（JDBC 后端） | **尚未验证**：共享套件无嵌入/转换正例（JPA 侧有 3 + 4 处正例） | 同一实体不能跨后端复用，等于把模型锁死在 JPA 一个后端；替代：平铺标量列、手写 `AttributeConverter` 也只解决一半 | **已在 3.1.0 恢复（有边界）**：扁平 `@Embedded`（列名默认 `<嵌入字段名>_<嵌套字段名>`、`@AttributeOverride` 可覆盖；嵌套 `@Embedded` 与点号形式的嵌套覆盖不做）+ **显式** `@Convert(converter=...)`（`autoApply` 不生效，`convertsTo` 列类型必须在受支持清单内）；CHANGELOG「Added → `@Embedded` / `@AttributeOverride` / `@Convert`」。触发器：需要嵌套嵌入/`autoApply` 的真实消费方 |
| CUT-13 | 生成键只允许 `IDENTITY` + `Integer`/`Long` | **自设规则**：最小可用集 | `AUTO`/`SEQUENCE` schema 不可用；`short`/`BigInteger` 键不可用；替代：手动赋值 id 或改用 `Long` | **部分恢复（3.1.0）**：`AUTO` 按方言落到 `IDENTITY`，生成键类型放宽到 `short`/`int`/`long` 族与 `BigInteger`；**`SEQUENCE`/`TABLE` 仍拒绝**（`SEQUENCE`=尚未验证：无取号钩子、autocommit 取号空洞、方言形态未实测；`TABLE`=外部约束：需要序列表 DDL），改由应用侧 `IdGenerator` 发号（CHANGELOG「Added → 生成键策略与生成键类型放宽」）。触发器：给出跨方言取号形态与容器实测后重审 |
| CUT-14 | `@Version` 只允许 `Integer`/`Long` | **自设规则** | `short`/`Timestamp` 版本实体不可用；替代：改成 `Integer`/`Long` | **部分恢复（3.1.0）**：`int`/`long`/`short`/`Integer`/`Long`/`Short` 六种数值型放宽；`Timestamp` 仍拒绝（尚未验证：应用侧生成当前时间需 `Clock` 注入，JPA 侧本轮未同步，两端口径会分叉）。触发器：JPA 与 JDBC 同时给出 `Clock` 注入的时间戳版本实现 + 一致性用例 |
| CUT-15 | `@Column` / `@JoinColumn` 的 `insertable/updatable=false` 直接拒绝 | **自设规则**：写侧列集最简 | 2.2.3 examples 三个实体用 `@JoinColumn(insertable=false, updatable=false)` 标出只读外键，命中即不可用；同一实体无法跨后端；替代：删注解或拆实体/DTO 写 | **已在 3.1.0 恢复**（写侧跳过该列，与 JPA 写侧语义一致；`@Column` 与 `@JoinColumn` 两类列标志都支持；CHANGELOG「Added → `@Column` / `@JoinColumn` 的 `insertable=false` / `updatable=false`」） |
| CUT-16 | `@ManyToOne` 缺 `@JoinColumn(name)` 抛 UOE（不推断默认命名） | **自设规则**：要求显式 | 与 JPA 默认命名不兼容，每个关联都要补注解；替代：显式 `@JoinColumn` | **已在 3.1.0 恢复**（按 JPA 默认命名 `字段名_被引用列名` 推断；CHANGELOG「Added → 写关联与默认 `@JoinColumn` 推断」） |
| CUT-17 | 写关联：JDBC 非 null 抛 UOE、null 跳过；JPA 静默跳过（两后端行为相反） | **尚未验证**（共享套件无写入关联正例）+ 声称「静默置 null 会丢数据」（JPA 侧实际就是静默跳过，理由被自家行为证伪） | 关联列写不进；两后端一个抛炸一个静默丢；替代：关联列拆成标量列手动写 | **已在 3.1.0 恢复**（两后端都从关联对象 `@Id` 写外键；JDBC null 仍跳过列——`update(entity)` 无法置空外键，等价写法是条件更新 `set(Path.of(X::getDept).get(Dept::getId), null)`，与 CUT-03 互相引用；见 USAGE §13.4）。触发器：真实消费方需要「整实体 update 直接置空外键」时重审 JDBC null 语义 |
| CUT-18 | 集合关联与 `@OneToOne(mappedBy=...)` 反侧静默忽略（不进 `attributes()`、不参与 SQL） | **自设规则**：不承诺级联写语义 | 集合/反侧关联的写入被静默丢弃，只能靠 DB 默认值、触发器或第二次手写；替代：显式先写子实体（持有外键一侧的 `@ManyToOne`/`@OneToOne` 已可写）。**查询路径穿过集合关联两侧都显式 `UOE`（`SqlPaths`/`JpaPaths`），不是静默忽略**——静默只限写入列集 | **3.1.0 仍不写**，但 USAGE §8/§13.4 明确披露「写入列集不进 `attributes()`、不参与 SQL；查询路径 fail-fast」，不再与「命中即抛 UOE」混为一谈。触发器：出现需要级联写或集合路径投影的消费方 → 按新能力重审，不是修 bug |
| CUT-19 | 聚合规格与多列 distinct 的 `count`/`exists` 显式拒绝 | **尚未验证**：一致性套件不覆盖该组合 | 组合计数只能取回数据后去重；替代：`select(a,b).distinct().list()` 后 Java 侧计数 | 3.1.0 恢复「groupBy 之后的 `count`/`exists`/分页」（CUT-07）；**多列 distinct 的 count/exists 仍拒绝**。触发器：补出跨方言一致的 distinct-count 差分基类 + 两方言对拍通过 |
| CUT-20 | 锁只支持实体查询：值选择/聚合规格带锁 → UOE | **外部约束**：H2 拒绝 `FOR UPDATE` + DISTINCT/GROUP BY、MySQL 接受，方言行为不一 | 聚合/投影结果不能锁读（对账场景要么放弃锁、要么拆两次查询）；替代：先锁实体行再聚合 | **3.1.0 仍拒绝**。触发器：H2 支持该组合 / 新方言统一行为 / 消费方给出跨方言对拍用例 |
| CUT-21 | `@JoinColumn(referencedColumnName=...)` 指向非 `@Id` 列 → meta 期 UOE（三段式） | **自设规则**：契约「`count(*)` 不放大、实体行数 = 结果行数」 | 存量 schema 外键指向唯一非主键列时不可映射；替代：标量列 + 手动 join | **3.1.0 仍拒绝**。触发器：需要目标列 join 的去重语义 + 两方言对拍 + 新基类用例 |
| CUT-22 | MSSQL 不做行锁（`lock` 抛 UOE） | **外部约束**：MSSQL 无标准 `FOR UPDATE`；`with (updlock)` 在 autocommit 下不持锁、语义不等价 | SQL Server 消费方拿不到行锁语义；替代：应用层锁、快照隔离或原生 SQL | **3.1.0 仍不做**。触发器：MSSQL 出现可移植锁实现并被容器用例钉住 |
| CUT-23 | JPA 测试矩阵只有 H2 + MySQL，不写 PG/MSSQL 的 JPA 用例 | **尚未验证**：仓库 JPA 通道本来只有两条 | PG 上的 Hibernate 方言差异只能按 H2 推断（已发生过 reserved word 探针失败、SQL 原文未保留的事故）；替代：用 JDBC 后端跑 PG/MSSQL | **3.1.0 恢复 PG 容器通道**（CHANGELOG「Added → PostgreSQL 容器通道」）；**MSSQL 的 JPA 组合仍未验证**。触发器：PG 已触发；MSSQL 待容器通道落地 |
| CUT-24 | 7 列以上投影不提供（`Tuple2`~`Tuple6` 封顶） | **自设规则**：`ExcessiveParameterListSix`（工厂方法参数 ≤6） | 7+ 列固定查询必须自定义 `ProjectionMapper`；替代：`ProjectionMapper` 透传（断言形态被迫改写） | **3.1.0 仍不提供**。触发器：消费方出现 7+ 列固定投影占比的证据 |
| CUT-25 | 92 个 `*IfNotNull`/`*IfNotEmpty` 改名为 `*IfPresent` | **自设规则**：保留 2.2.3 命名 | 否决改名；新用户可能误预期 `*IfPresent`；替代：现有命名已足够 | **已否决**（保留原命名）。触发器：出现兼容别名需求（只许加性） |
| CUT-26 | `BaseWhereStep<T,U>` 第二类型参数改名/收窄 | **自设规则**：`WhereStep<T>` 别名要多一层接口，收益为零 | 泛型写法冗余（存量写 `<Order,Order>`）；替代：无（无别名可用） | **不实施**。触发器：出现显式声明该类型为变量类型的调用点 |
| CUT-27 | `selectDistinct(Class)` 不单独提供 | **自设规则**：`API_INTERFACES_STAY_NARROW` 要求接口方法 ≤6，`SelectStep` 加 `select(Class)` 后正好 6 | 多一步 `distinct()`；替代：`distinct().select(Class)`（examples 移植按「只改调用形态、不弱化断言」处理） | **不提供单独方法**。触发器：接口上限规则变更，或 `distinct()` 在 `SelectStep` 上不可达 |
| CUT-28 | `EntityPath.of(String)` 等类型不安全重载 | **自设规则**：类型安全 | 动态字段名（元数据驱动）不可用；替代：方法引用或 `EntityPath` 逐段 `get` | **仍移除**。触发器：出现元数据驱动的动态字段需求 |
| CUT-29 | 字符串算子/函数的可达路径只有 `where(...)` 返回的 `StringOperator`：`Path.of(...)` / `EntityPath...get(...)` 得到的 `StringPath` 不提供 `startsWith`/`lower`/`length` 等 | **自设规则**：不给 `Path`/`StringPath` 的实现方制造源码义务（每加一个方法，所有路径实现者都要实现/覆写） | 条件里要过滤字符串时必须从 `where(属性引用)` 进入；`Path.of(Employee::getName).startsWith(...)` 编译不过；替代：`where(Employee::getName).startsWith(...)`（条件成立），或先把值查出来在内存过滤 | 真实消费方需要在 `select(...)` 的路径上调用这些函数，或自定义路径实现方主动要求接入时重审 |
| CUT-30 | 计算列投影不支持：`lower()` / `length()` 等函数结果不能出现在 `select(...)` / `ProjectionMapper` 里 | **自设规则**：投影管线只接受属性路径（`NumberOperator` 不 extends `Expression`），内部结构决定，不是外部限制 | 无法把 `LOWER(name)` / `LENGTH(name)` 之类的计算结果直接映射到 DTO 字段；替代：原生 `@Query` 或后端 SQL，或查出属性后在 Java 侧计算 | examples 出现该用法，或真实用户报告「投影要函数结果」时重审 |
| CUT-31 | 函数结果上的 `in` / `notIn` / `isNull` / `isNotNull` 拒绝（core 抛 UOE，有用例钉住） | **自设规则**：`in`/`isNull` 的语义针对**属性**，函数结果没有确定的 SQL 对应物（不同方言对 `LOWER(x) IS NULL` 的求值一致但 `IN` 与 NULL 的组合语义易分歧） | 无法写「长度在集合里」「转小写后为空」这类条件；替代：显式展开成等值/区间条件（`length().eq(n)` 或 `or(...)`），或在内存过滤 | SQL 方言给出可移植的函数结果 NULL/IN 语义并被跨方言差分用例钉住时 |

### 7.2 结构性决策

| 条目 | 决策 | 理由（类型） | 代价与替代路径 | revisit 条件与触发器 |
|---|---|---|---|---|
| DEC-01 | 单一引擎，JPA 优先 | **外部约束**：生产消费方就是 Hibernate；重复实现第二引擎是 2.x 主要体积来源 | 非 JPA 用户被排除在主引擎外，只能走 `jdbc` 第二后端；替代：同时维护 JDBC + JPA 双引擎（被否决） | Hibernate 无法满足、JDBC 后端也覆盖不了的需求出现时（准入条件见 §6 末） |
| DEC-02 | 保留方法引用，增量提供静态元模型 | **外部约束**：存量代码零迁移（87 个仓储） | 方法引用无编译期字段校验；替代：一次性改静态元模型（要改 87 个仓储，被否决） | 静态元模型的编译期安全成为消费方硬需求时 |
| DEC-03 | 自研 `Spec` AST（约 12 节点），不直接拼 Criteria | **自设规则**：可单测、可快照、是后端接缝的前提 | 新增谓词要改 sealed 节点 + 编译器分支（穷尽性检查会逼出全部分支）；替代：builder 里直接拼 Criteria（不可测、锁死后端） | 节点数超过维护阈值（当前约 12 个基础节点，3.1.0 按 §8 新增）时重审 |
| DEC-04 | 分页返回自有 `Slice` | **外部约束**：消费方 `Pageable` 已依赖该类型；`Slice` 能表达「不计数」 | 与 Spring Data 类型不通用，需 `to()` 映射；替代：直接返回 Spring `Page`（把 Spring 拖进 `api`） | 消费方要求直接消费 Spring `Page` 的证据 |
| DEC-05 | 一致性测试独立成模块并发布 | **自设规则**：后端实现与消费方共用同一套契约测试 | 发布物多一个模块；替代：基类塞进各模块 `src/test`（无法复用） | 外部后端实现者要求接入时（`jdbc` 已接入） |
| DEC-06 | 不建 ADR 目录，决策写在本文 | **自设规则**：同一决策只有一个出处（AGENTS 文档地图） | 决策历史与代码同仓演进，无独立时间线；替代：每决策一篇 ADR（内容重叠、易漂移） | 文档地图规则变更 |
| DEC-07 | 不做实体事件与拦截器 | **自设规则**：Spring 事件 / JPA 生命周期回调够用 | 需要实体生命周期钩子时只能离开本库；替代：自研事件总线 + CGLIB 代理（被否决） | 消费方明确要求本库提供生命周期回调 |
| DEC-08 | 引入 `core.spec.Operand` 值表达式层 | **自设规则**：列对列比较与表达式算术要求比较两侧都可能是表达式 | 比较右值类型从 `Object` 变 `Operand`（api 已消化）；替代：`Comparison` 加 value/expression 双字段（渲染端两条分支）或 `instanceof` 判定（运行期才报错） | 新值形态（如窗口函数结果）需要新 `Operand` 实现时，按 §8 落位图接入 |
| DEC-09 | 推翻「`Aggregates` 不加 `MIN`/`AVG`」，纳入第一梯队 | **尚未验证→已推翻**：原结论是调用点驱动（`.min(`/`.avg(` 当时 0 命中），不构成能力面证据 | 六种聚合都进能力面；保留约束「聚合结果不接聚合方法」（`max().max()` 编译期不可写） | 已触发（本轮重审）；出现「对聚合结果再聚合」需求时重审约束 |
| DEC-10 | 显式 `ProjectionMapper` 与 `select(Class)` 并存 | **自设规则**：显式映射器保列序可审计，`select(Class)` 保低摩擦 | 两套投影 API 的学习成本；`Tuple#size()` 契约下限从 2 放宽到 1；替代：只留一种（3.0.0 只留 mapper，被 2.2.3 语料反证） | 使用统计显示 `select(Class)` 覆盖 ≥90% 投影、且列序用例不再依赖 mapper |
| DEC-11 | 第二后端用 `JdbcCompiler` 额外公开 `count(QuerySpec)`/`exists(QuerySpec)`，`JdbcQuery` 携带 `QuerySpec` | **自设规则**：执行器不能改 SQL，编译期一次成型 `{sql, bindings}` | `jdbc` 内部包多两个公开方法（不承诺兼容）；替代：执行器套派生表 / 让 `SqlStatement` 携带三份 SQL | 第二后端接入需要同形接缝时，按 §6 末「新后端的接线清单」处理 |
| DEC-12 | 标识符默认裸名，需要引用时在注解名里用反引号显式标记，渲染字符由方言决定 | **外部约束**（用户要求保留字/存量表可用）+ **自设规则**（机械规则：注解名反引号标记 + 方言 `quote()` 渲染） | 名字内层定界符（`"`/`` ` ``/`[`/`]`）不支持，两后端一致拒绝；替代：改名 | 出现内层定界符的真实需求 |
| DEC-13 | `JdbcEntityOperationsFactory(DataSource)` 每次操作独立取连接，事务边界归调用方 | **外部约束**：与 JPA 后端「事务边界调用方持有」一致；JDBC 默认自动提交 | 消费方 `DataSource.getConnection()` 必须池化或包装成 close no-op；替代：自带事务模板（真要时随第二后端引入，不提前摆空接缝） | 出现需要库内事务管理的消费方 |
| DEC-14 | 「不限行」统一 `limit ? offset ?` + `Long.MAX_VALUE` 哨兵；`offset==0` 视为无 offset、`limit==0` 视为不限行 | **自设规则**：api 契约「0 表示不限行」；两方言共同语法无需分支 | 超大 offset 以哨兵值传递，个别驱动边界待实测；替代：MySQL 专用字面量或方言分支 | 驱动对哨兵值报错（实测出现即改方言钩子）或方言矩阵新增库 |
| DEC-15 | 聚合/算术按静态类型收窄（`COUNT→Long`、`AVG→Double`、`SUM/MIN/MAX→列类型`、算术提升） | **自设规则**：与 api 泛型签名一致，跨方言统一 | 驱动原生类型被抹平（H2 BIGINT / MySQL DECIMAL 分叉被隐藏）；替代：交驱动原生类型（跨库不可比） | 消费方需要原生精度/类型时（按方言钩子扩展） |
| DEC-16 | `EnumStorage` 提为 public enum（`NONE`/`NAME`/`ORDINAL`） | **自设规则**：`compile` 与 `exec` 分包都要判「枚举按 name 还是序号绑定」 | 内部包多一个公开类型；替代：在 `JdbcEntityAttribute` 上加 `toJdbcValue`/`fromColumn`（把转换逻辑塞进 meta 接口） | 枚举存储策略需扩展（新 `@Enumerated` 形态）时 |
| DEC-17 | `attributes()` 含关联属性，但 INSERT/UPDATE 列集与行装配只走标量属性 | **外部约束**：路径解析需要关联属性；与 JPA「关联字段不映射成列」一致 | 集合关联静默不写（CUT-18）；替代：显式先写子实体 | 级联写需求出现时（与 CUT-18 同一触发器） |
| DEC-18 | `SQLException` 统一包 `jakarta.persistence.PersistenceException` | **外部约束**：后端对调用方只暴露 JPA 异常族 | 顶层异常丢失 `SQLState`（仍在 cause 里可取）；替代：保留 `SQLException`（破坏后端中立） | 消费方需要按 `SQLState` 分支（用 cause，无需改契约） |
| DEC-19 | 内置方言 = H2 / MySQL / PostgreSQL / SQL Server | **外部约束**：用户明确要求 PG + MSSQL 方言 | 方言维护面 4 倍；替代：只内置 H2/MySQL + 自定义方言 | 新方言需求走自定义接缝（§6.1），不默认内置 |
| DEC-20 | 方言接口对外可实现：`name()` + `of()` + 8 个 default 钩子（3.1.0 加性的 `substring`/`lengthOf` 各带默认实现）+ `SqlParameterSink`/`LockClause` | **外部约束**：用户要求「碰到没预制的方言能自定义渲染 SQL」 | 方言作者要实现 8 钩子并守不变量 ①-⑤；替代：每子句钩子 / SQL AST / 配置驱动模板 | 自定义方言需要新钩子时按不变量评审（3.1.0 的两次新增即按此加性扩展） |
| DEC-21 | 窗口参数由方言经 `SqlParameterSink` 追加，渲染期校验 `?` 数 == append 次数 | **自设规则**：MSSQL 的 offset 参数在文本上先于 limit，按文本序绑定必错 | 方言实现的错误变成确定性异常（这是目的）；替代：「`?` 按文本顺序绑定」契约 | 新方言参数顺序更复杂时 |
| DEC-22 | `limitClause` 只出尾缀 + `hasOrderBy`；exists 行数上限默认字面量 ` limit 1`、MSSQL 用 `top (1)` | **自设规则**：MSSQL `OFFSET…FETCH` 强制 ORDER BY；exists 走尾缀会改旧快照 | 方言要自己处理 exists 的 SELECT 头差异（已封装）；替代：统一派生表 | 窗口语法重构时 |
| DEC-23 | `averageOf(operand)` 独立钩子 | **外部约束**：MSSQL 的 `avg(int)` 截断，需要 `avg(cast(x as float))` | 方言接口多一个钩子；替代：在 exec 层按方言转类型 | 新方言出现聚合差异时 |
| DEC-24 | 生成键 / 绑定 / 事务 / ResultSet 列读取不进方言接口 | **外部约束**：四种数据库的标准 JDBC 行为一致（容器探针验证） | 非 JDBC 标准行为无法经方言接缝表达，只能算「新后端」；替代：exec 层方言策略钩子 | 出现 JDBC 标准外的方言行为 |
| DEC-25 | 引用字符按方言；H2 上 JPA 与 JDBC 逐字节一致用双引号 | **自设规则**：避免两后端同库同表不同引号导致排查/快照做语义换算 | 每个方言的引用形态各写一遍；替代：JDBC 的 H2 用反引号（语义等价但形态不一致） | H2 兼容模式行为变化时 |
| DEC-26 | `Collector` 允许 **7** 个声明方法（其余 api 接口仍 ≤6）：`ArchitectureTest.WIDE_INTERFACE_ALLOWLIST = {"io.github.nextentity.api.Collector": 7}`，精确计数、只许持平不许增长，新增方法必须先改这条决策与白名单 | **自设规则**：`API_INTERFACES_STAY_NARROW` 是本仓自定 lint，不是外部约束；7 个终止符（`list`/`list(limit)`/`list(offset,limit)`/`first`/`single`/`count`/`exists`）是同一个契约面 | 外部实现者/阅读者看到的 `Collector` 比设计上限宽 1 个方法；拆分点在「取行（`list`/`first`/`single`）vs 聚合（`count`/`exists`）」，但拆接口要改 `builder` 与 `lock(...)` 的返回类型，属**主版本动作**，MINOR 做不了；第 8 个终止符会被门禁立刻拦下，不是无声增长 | 下一次主版本按「取行 / 聚合」拆分 `Collector`，届时删除白名单条目并把计数降回 ≤6；或 lint 规则本身被重新定义 |
| DEC-27 | 批量条件写（`update().set(...).where(...).execute()` / `delete().where(...).execute()`）不参与乐观锁：不校验、不自增 `@Version`，也不回写调用方对象；只有单实体 `update(entity)` 走版本校验并回写 | **外部约束**：Hibernate 的 `CriteriaUpdate`/`CriteriaDelete` 不施加版本自增，绕开它得绕开 Criteria 自建 SQL（绑定、方言、缓存全部自理），代价大于收益；JDBC 侧采用同一语义是为了两后端行为一致，与 2.2.3 也一致 | 并发下批量更新可能覆盖他人刚提交的行且不报 `OptimisticLockException`；替代：单实体 `update(entity)`（行级版本保护），或把版本列显式写进 `where(...)` 条件并按受影响行数判断失败重试（USAGE §10.1「并发语义」） | 出现「批量更新 + 乐观锁失败重试」的真实需求时重评（引入版本列自增需两端编译器同步改） |

### 7.3 3.1.0 恢复项的端到端归属

`§7.1` 已恢复的条目必须四段齐全：**api 声明 → core 节点/编译 → 后端渲染 → 测试证据**。只做前两段就是 3.0.0 的错误——「api 有节点」被当成「能力已提供」，没人负责落库。签名以 `nextentity-api` 的实现为准，本表只承诺**归属**；签名在对应 api 类型上自描述，不在此复写。

| 条目 | api 声明 | core 节点/编译 | 后端渲染 | 测试证据 |
|---|---|---|---|---|
| CUT-01 `fetch(...)` | `FetchStep`（抽象 `fetch(Collection<? extends Path<T,?>>)` + 1/2/3 条 `Path`、1/2 条 `PathRef` 的 default 重载） | spec 预取节点 + 编译器分支 | JPA：`LEFT JOIN FETCH`（`JpaPaths` 维护 fetch 链前缀缓存，嵌套 fetch 复用父节点）；JDBC：**显式拒绝（`UOE`）**——JDBC 不组装实体图、无懒加载代理，`fetch` 没有可兑现的语义，静默 no-op 会让调用方拿到 `null` 关联（理由类型=架构约束，USAGE §13.4.3） | JPA 侧 SQL 条数对照（不 fetch vs fetch）；`JdbcFetchRejectionTest`（`assertThatThrownBy`，断言类型 + 文案含替代路径）；examples 已移植：`EmployeeRepository`/`ProductRepository` 的 fetch 调用在 H2 集成类里跑通，JDBC 侧同类调用由 `JdbcFetchRejectionTest` 钉 `UOE` |
| CUT-02 `select(Class)` | `SelectStep.select(Class)`（`SelectStep` 第 6 个方法） | 反射投影（record 按规范构造器参数名/参数序；普通类按 `setXxx` + 无参构造器、列序按属性名排序；只做扁平映射，接口/抽象类与嵌套路径拒绝） | 后端不额外渲染：列集由 core 决定 | examples `EmployeeSummary`/`EmployeeInfo` 等扁平 DTO 的 projection 用例 + 列序用例（examples 已移植并跑通）；接口投影与嵌套路径的拒绝分支各有 `assertThatThrownBy` |
| CUT-03 条件更新 | `UpdateSetStep`（`set(PathRef, value)` / `set(Path, value)` 两个重载）/ `UpdateWhereStep` / `Executable`；入口 `PersistExecutor.update()` | spec + `PersistExecutor` 新增方法 | JPA：`CriteriaUpdate`；JDBC：`UPDATE ... WHERE` 绑定 | `ConditionalSqlTest`（赋值/WHERE/NULL/嵌入列/拒绝分支）、`JdbcPersistExecutorTest`（update 执行）、JPA `JpaCriteriaWriteTest`；examples `EmployeeRepository#deactivateEmployeesByDepartment` 已移植并跑通 |
| CUT-04 条件删除 | `DeleteWhereStep`；入口 `PersistExecutor.delete()` | spec + `PersistExecutor` 新增方法 | JPA：`CriteriaDelete`；JDBC：`DELETE ... WHERE` 绑定 | `ConditionalSqlTest#deleteWithoutWhereIsWholeTable`/`#deleteWithWhereBindsEnumLiteral`、`JdbcPersistExecutorTest`（delete 执行）、JPA `JpaCriteriaWriteTest`；examples `EmployeeRepository#delete`/`#deleteAll` 已移植并跑通 |
| CUT-05 字符串模式算子 | `StringPatternOps` / `StringPatternOpsIfNotNull` / `StringPatternOpsIfNotEmpty` | LIKE/NOT 节点（`notLike` 折成 `NOT(LIKE)`） | JPA/JDBC：`LIKE` / `NOT LIKE`，与纯 `like` 共用路径 | `ItemStringOperatorConsistencyTest` + MySQL/SQL Server 版（Where/LIKE/否定三值逻辑）；core `StringStepOperatorTest`/`OperatorMappingTest`；examples `EmployeeRepository` 的 startsWith/endsWith/notLike 与取反变体、嵌套路径 startsWith 已移植并跑通 |
| CUT-06 字符串函数 | `StringFunctions` / `NumberOperator` | `StringFunctionOperand(StringFunction{LOWER,UPPER,TRIM,LENGTH}, Operand subject)`、`SubstringOperand(Operand subject, int offset, Integer length)` spec 节点 + 编译器分支 | JPA/JDBC：`LOWER`/`UPPER`/`TRIM`/`LENGTH`/`SUBSTRING`（`length == null` 渲染成 `SUBSTRING(x,pos)`，否则 `SUBSTRING(x,pos,len)`；穷尽 switch，不许 default 分支） | `SqlOperandNodesTest`（节点渲染）、core `StringFunctionOperatorTest`、JPA `ItemTier1RenderingTest`/`ItemCapabilitySqlSnapshotTest`；SQL 快照已由 tester 波的 golden schema 快照用例补齐（见 §5「golden schema 夹具」） |
| CUT-07 groupBy 后 count/exists/分页 | `Collector` 契约：分组规格可 `count()`/`exists()`/`list(offset,limit)`/`slice(...)` | `CountScope{ROWS,GROUPS}`（`QuerySpec` 第 10 分量 `countScope`，缺省 `ROWS`）+ `QuerySpec.forGroupCount()`/`forGroupExists()`；分组编译分支 | JPA/JDBC：`count()` 把分组查询包成子查询计**组数**（having 已生效）、`exists()` 判是否存在任何组、`list(offset,limit)` 与 `slice(...)` 的窗口作用在组上（`slice` 的 total 即组数） | `ItemHavingConsistencyTest` + MySQL/PostgreSQL/SQL Server 版；JPA `ItemCapabilitySqlSnapshotTest`；examples 的 `groupBy` 聚合/计数投影（`AggregationIntegrationTest`）已移植并跑通；`having` 语义由 `ItemHavingConsistencyTest` 系列覆盖 |
| CUT-08 `Slice.offset()`/`limit()` | `Slice` 方法 | 值对象，无编译变化 | 无 | core `QueryBuilderTest`（`slice(...)` 带出请求的 offset/limit，含短路路径）；examples `PaginationOrderingIntegrationTest` 的 offset/limit 断言已移植并跑通 |
| CUT-09 `PESSIMISTIC_READ` | `LockMode` 既有常量 | 编译器分支 | JPA：`PESSIMISTIC_READ`（3.0.0 起已按名字映射，本版只补验证）；JDBC：`lock` 钩子出 `for share`（MySQL 与 PostgreSQL 容器均已实测；H2 无 FOR SHARE，用 for update 兜底；SQL Server 保持 UOE） | `LockContractTest` + `MySqlLockContractIntegrationTest` + `SqlServerLockContractIntegrationTest`（UOE）+ `PostgresLockContractIntegrationTest`（PG 容器 4 条全绿）+ `PostgresItemLockIntegrationTest`（PG 容器 2 条全绿）+ 方言 SQL 快照（H2 兜底与 for share 分开断言） |
| CUT-10 `lock(...)` 后分页 | `Collector.list(int offset, int limit)`（default 方法，`lock(...)` 返回类型不动） | 无 spec 变化 | 执行器沿用分页 | core `QueryBuilderTest`「lock(mode).list(offset, limit)：锁与分页窗口同时进入规格」+ api `CollectorTest#listWithWindowRejectedByDefault`（默认 UOE）；examples 上游本无锁分页调用点；带锁分页由 core `QueryBuilderTest` 与 tests-jpa 锁用例覆盖 |
| CUT-11 JDBC 列类型与值转换 | 注解面不变 | `jdbc` 列白名单 + 值转换层（枚举/布尔/数值/时间/`java.sql.*`；`byte`/`char`/`byte[]` 仍拒绝） | 绑定与回读（`getObject`/类型转换） | `ExtendedCapabilitiesE2ETest#extendedColumnTypesRoundTrip` + MySQL/SQL Server extended 集成测试；Item 差分/一致性套件（时间/精度） |
| CUT-12 JDBC `@Embedded`/`@Convert` | 注解面不变 | meta 扁平化（列名默认 `<嵌入字段名>_<嵌套字段名>`，`@AttributeOverride` 单层覆盖）+ 显式 `@Convert` 桥接（`autoApply` 不生效） | 绑定/回读 | `ExtendedCapabilitiesE2ETest#embeddedColumnsAreFlattenedAndRoundTrip`/`#convertBridgesEnumToDatabaseColumn`、`JdbcEmbeddedTest`；JPA 侧 `ItemConvertRoundTripTest`/`EmbeddedOverrideWriteContract`；嵌套 `@Embedded`/点号 `@AttributeOverride`/`autoApply` 拒绝分支各有用例 |
| CUT-13 生成键策略/类型 | 注解面不变 | meta 生成键校验 + `JdbcPersistExecutor` 回读（`AUTO`→方言落 `IDENTITY`；`SEQUENCE`/`TABLE` 显式拒绝） | `RETURN_GENERATED_KEYS` 回读（多列按 id 列名匹配；BigInteger 走 `getBigDecimal`，短整型做范围校验） | `ExtendedCapabilitiesE2ETest#shortGeneratedKeyIsWrittenBack`、`JdbcPersistExecutorMutationTest#generatedIdentifiersFollowTheFieldType` 等、JPA `GeneratedIdInsertContract`；examples 实体为手动 id，不覆盖生成键；生成键回读由 `ExtendedCapabilitiesE2ETest` / JPA `GeneratedIdInsertContract` 覆盖 |
| CUT-14 `@Version` 类型 | 注解面不变 | meta 校验六种数值型（`Timestamp` 仍拒绝）+ 乐观锁版本自增 | `WHERE id [AND version]`，0 行抛 `OptimisticLockException` | `ExtendedCapabilitiesE2ETest#shortVersionIncrementsOnUpdate`、`WriteSqlVersionTest`、JPA `ItemVersionRoundTripTest` |
| CUT-15 `@Column` / `@JoinColumn` 列标志 | 注解面不变 | 写侧列集跳过 | 无（列不出现在 INSERT/UPDATE 列表） | `ExtendedCapabilitiesE2ETest#notInsertableColumnIsSelectedButNotWritten`、`ConditionalSqlTest#updateRejectsNotUpdatableColumn`（条件更新 fail-fast）、`JpaConditionalWriteUpdatableTest`（JPA 侧两处 `assertThatThrownBy`，消息与 JDBC `ConditionalSql.java` 逐字一致，证明「两后端一致拒绝」有落点）、`JdbcEntityAttributeTest` 列标志 |
| CUT-16 `@JoinColumn` 默认命名 | 注解面不变 | meta 按 JPA 默认规则推断列名 | 无 | `JdbcEntityAttributeTest#associationIdComesFromTheReferencedIdField`、`JdbcEntityRulesTest` |
| CUT-17 写关联 | 注解面不变 | 写侧从关联对象 `@Id` 取外键列 | JPA：写 `@JoinColumn` 列；JDBC：绑定外键值 | `WriteSqlTest#insertWritesJoinColumnFromAssociationId`/`#insertSkipsNullAssociationColumn`/`#updateWritesJoinColumnFromAssociationId`、`RejectContractTest#associationWriteUsesReferencedIdAsForeignKey`；JPA `MySqlAssetWriteAssociationIntegrationTest`；两后端对等断言由 `nextentity-testing` 的 `AssociationWriteConsistencyTest` 承载（JPA/JDBC 接线类同批跑） |
| CUT-23 PG 容器通道 | 无 | 无 | 无 | **已在 3.1.0 恢复并实测**：`nextentity-tests-jpa` 的 `-Pjpa-postgres` profile、通道 `failsafe-pg:nextentity-tests-jpa`、2026-10-30 实测 590 条 / 49 类全绿（与 MySQL failsafe 同批 590 条）、skip 1（H2 兼容模式专属）；MSSQL 的 JPA 组合仍未验证（触发器：引入对应 profile 与容器用例后重审） |

---

## 8. 新能力落位图

| 要加什么 | 落在哪 | 是否改 `api` |
|---|---|---|
| 新谓词（如 `regexp`） | `spec` 加节点 + `compile` 加分支 + `api` 加算子 | 是（增量） |
| 新值形态（表达式/聚合结果参与比较） | `core.spec.Operand` 加实现 + `jpa.compile.JpaOperands` 加 `switch` 分支（唯一渲染点） | 是（增量：`ColumnComparison` 等） |
| 新聚合函数 | `core.spec.AggregateFunction` 加常量 + `JpaOperands` 的 `switch` 分支 | 否（`api.Aggregates` / `NumberPath` 已是入口） |
| 分组后过滤（`having`） | `core.spec.QuerySpec.having` + `jpa.compile.JpaQueryBuilder` | 是（增量：`HavingStep`） |
| 新终止符（如 `keyset`） | `exec` 加方法 + `api` 加步骤接口 | 是（增量） |
| 新投影形态（record/DTO/接口） | `api` 的 `SelectStep.select(Class)` + `builder` 的 `RowAssembler`（`Tuple` → 结果对象，经 `ValueSelection#rowMapper()`）；需要显式列序时仍用 `ProjectionMapper` + `ValueProjections` | 是（增量：`SelectStep` 的方法数已在 6 的上限，加新投影入口前先算 `API_INTERFACES_STAY_NARROW`） |
| 新预取形态（如多级 `fetch`） | `api` 的 `FetchStep` + `core.spec` 预取节点；渲染由后端各自实现（JPA 走 `LEFT JOIN FETCH`；JDBC 无实体图/懒加载，当前显式拒绝 `UOE`，见 §7.3 CUT-01） | 是（增量） |
| 新按条件写入（如 `update().set().where()` 的扩展） | `api` 加步骤接口 + `core.spec` 写规格节点 + `PersistExecutor` 加方法（只加方法，不改既有签名） | 是（增量） |
| 新字符串算子/函数（如 `regexp`、`replace`） | `core.spec` 加节点 + `compile` 加分支 + `api` 加算子（`StringOperator`/`StringExpression` 家族） | 是（增量：interface 默认方法不算破坏） |
| 新后端（已实现：`jdbc`） | 新模块实现 `Compiler`/`Executor`/`EntityOperationsFactory`，照 §6 末的接线清单抄 | 否 |
| 新方言差异 | 后端模块内部（`jdbc` 侧：`JdbcDialect` 加实现 + `JdbcDialect.of` 探测分支 + 方言各自一份 SQL 快照）；**不内置**的数据库走消费方自定义方言，`jdbc` 模块不改 | 否 |
