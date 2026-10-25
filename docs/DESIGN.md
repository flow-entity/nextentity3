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

testing / tests-jpa / tests-legacy / arch 仅被测试与 CI 使用（bom 只做版本对齐）
```

| 模块 | 放什么 | 不放什么 |
|---|---|---|
| `api` | 用户写查询会 import 的一切：`Path`、`PathRef`、`EntityPath`、`Predicate`、`Op`/`Operation`、步骤接口、`Slice`、`Tuple2`~`Tuple6`、`ProjectionMapper`/`ProjectionMappers`、`LockMode` | 任何 `jakarta.*`、`org.hibernate.*`、`org.springframework.*`、任何工具类 |
| `core` | `Meta`（属性解析）、`Spec`（AST）、`Compiler<S>`/`Executor<S>`（接缝）、`PersistExecutor`、`EntityOperationsFactory`/`EntityOperations` | JPA/Criteria 类型、Spring 类型、SQL 字符串 |
| `jpa` | `JpaMeta`、`JpaCompiler`、`JpaExecutor`、`JpaPersistExecutor`、`JpaEntityOperationsFactory` | Spring 类型（由 `spring` 模块桥接） |
| `spring` | `NextEntityAutoConfiguration`、属性绑定、`@Bean` 装配、`AbstractRepository`（构造器注入 `EntityOperationsFactory` + `query()` + `insert(T)`） | 查询逻辑 |
| `arch` | ArchUnit 规则：模块循环、core 包边界、`spec` 纯净与不可变、时间/随机注入、接口宽度 | 任何业务逻辑；只有测试代码 |

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

行成形之后由 `builder` 的 `RowAssembler` 把原始行装成 `Tuple` 或映射器结果（见下表 `builder` 行）。

| 层 | 职责 | 边界判据 |
|---|---|---|
| `meta` | 属性名 → JPA 属性（`JpaMeta` 按实体类型缓存）；不含方法引用解析 | 只认 JPA 映射注解，不认业务语义 |
| `builder` | 方法引用 → 属性名（`PathRefs` 解析 `SerializedLambda`，每次调用解析、无缓存）；步骤接口 → `Spec` 节点（表达式 → 投影项的唯一转换点是 `ValueProjections`）；可选条件（`eqIfNotNull` / `containsIfNotEmpty`）在此短路；后端原始行 → 结果对象（`RowAssembler`：`Tuple` 装配与 `ProjectionMapper` 映射） | 不产生 SQL/Criteria，不做类型转换以外的逻辑 |
| `spec` | 不可变 AST：条件 `Comparison`/`Between`/`In`/`NullCheck`/`Logical`/`Constant`；值表达式 `Operand`（`Literal`/`AttributePath`/`AggregateOperand`/`ArithmeticOperand` + `BinaryOperator`）；投影 `Projection`/`AggregateProjection`/`PathProjection`/`ComputedProjection`/`ValueSelection`/`EntitySelection`；枚举 `AggregateFunction`/`ComparisonOperator`/`LogicalOperator`；`Ordering`/`Window`/`QuerySpec`/`Selection` | 不含任何后端类型，可序列化、可断言、可复用 |
| `compile` | `Spec → S`，唯一渲染出口 | 一个 `switch` 分支处理一个节点，穷尽性由编译器保证 |
| `exec` | 执行、分页计数、后端行成形（多列 `Object[]` / 单列标量）、锁模式 | 不解释条件语义 |
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
| `Slice` / `Tuple` 与其 record 实现 `Tuple2`~`Tuple6` / `AggregateValue` / `ProjectionMapper`+`ProjectionMappers` / `LockMode` / `Op`+`Operation` | `jpa.*`、`spring.*` |

微调预算与破坏性变更的前置条件见 [AGENTS.md](../AGENTS.md)「公开 API 是契约」。

**已知的待收敛项**（实施时按此处理，均不破坏现有调用点）：

| 项 | 处理 |
|---|---|
| `BaseWhereStep<T,U>` 的第二类型参数恒等于第一（存量代码写 `<Order,Order>`） | **不实施**：`WhereStep<T>` 别名需要额外一层接口，而存量调用点全部容忍现状（0 处显式声明该类型为变量类型），收益为零 |
| 92 个 `*IfNotNull` / `*IfNotEmpty` | **已否决**：改名为 `*IfPresent` 要动 point-mall 154 处 `eqIfNotNull` 等调用点（破 D2/D3），且新名字并不更准确；保留 2.2.3 命名 |
| `Tuple3`~`Tuple10` | **只有 `Tuple2`~`Tuple6` 回来，7 列以上不提供**：三列由 `select(...)` 固定重载给 `Tuple3`，四~六列由 `ProjectionMappers.select(...)` 给 `Tuple4`~`Tuple6`。封顶六列是 `ExcessiveParameterListSix`（参数 ≤6）的直接后果——工厂方法的参数个数就是元组宽度；七列以上用自定义 `ProjectionMapper`。具名元组与 `Tuple` 统一是 record，实现不再放在 core |
| `lock(LockModeType)` 把 `jakarta.persistence` 带入 `api` | 已收敛：`api` 只提供自研 `LockMode` + `lock(LockMode)`，2.2.3 的 `lock(jakarta.persistence.LockModeType)` 重载已删除，`api` 回归零三方依赖（消费方 3 处调用点改用 `LockMode`） |
| `EntityPath.of(String)` 等类型不安全重载 | 已移除 |

---

## 5. 测试策略

五层防线，越往下越便宜、数量越多（金字塔底在 L0）。便宜层能挡住的问题，不允许流到贵的层。下文用 L0–L4 指代。

| 层 | 测什么 | 判定标准 | 依赖 | 位置 |
|---|---|---|---|---|
| 0. 架构门禁 | 模块循环、`core` 包边界、`spec` 纯净与不可变、时间/随机注入 | 编译期或测试期变红；同类问题不允许留到运行期 | 无 | `nextentity-arch` |
| 1. Spec 单测 | builder 调用 → AST 结构（对象断言） | 结构等价：断言节点类型与字段值，不看 SQL | 无 | `api` 与 `core` 的 `src/test`（api 钉线格式、core 钉 Spec 翻译，分工见下「api 值层断言的落点」） |
| 2. 编译快照 | Spec → SQL（H2 + `StatementInspector` 抓取） | 语句文本的确定性片段必须出现；期望值不得写进方言的引号、别名、大小写 | H2 | `nextentity-tests-jpa` |
| 3. 一致性 | 能力而非 SQL 文本：条件/分页/聚合/投影/写入/乐观锁 | 真库执行结果 vs 独立算出的期望；用例不引用任何后端类型 | 仅 `api`+`core` 接口 | `nextentity-testing` 抽象基类，由 jpa 子类继承 |
| 4. 集成 | 真实方言、`@Convert`、并发乐观锁 | 同一用例在各方言下结果集完全相同（载体：`nextentity-tests-jpa` 的 `*IntegrationTest`，跑真 MySQL 容器） | Docker | `nextentity-tests-jpa` |

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
| 变异分数（PIT） | 主指标 | `api` / `core` / `jpa` / `spring` 均 ≥ 95%；当前实测 99% / 100% / 98% / 100%（见下「变异测试」，阈值写在各模块 pom） |
| 语义等价律条数 | 辅助 | 每条律至少 1 条差分用例，条数只增不减 |
| 方言矩阵通过率 | 辅助 | 默认通道 100% 通过；skip 数单独报表，只许降不许升。矩阵列为 H2\MODE=MySQL\ + MySQL 8.0.18（默认、强制）+ H2\MODE=PostgreSQL\（opt-in，-Dnextentity.h2mode=postgresql，允许 8 条已知方言差异：3 条 SQL 文本快照 + 5 条 H2 不认的 PG 锁语法；`Instant` 绑定偏移不再计入（写侧断言按方言分派，既不红也不跳过，见下「方言矩阵」））；skip 报表**已建成**：每次门禁都从 surefire/failsafe XML 里抽 `<skipped>` 并连理由一起落进 `tmp/gate/<commit>-<ts>/receipt.json`，见下「执行凭证」 |
| 缺陷逃逸率 | 辅助 | 发版后发现的语义缺陷数 / 版本，趋势必须向下 |
| 行/分支覆盖率（聚合口径） | **不作门禁**，仅诊断报表 | 不设百分比门槛；判据是「新增的未覆盖行必须落在下文『已判定不可达与度量限制』清单里」，落在清单外就按缺口处理 |

**覆盖率要用合并口径看**：各模块自己的 `jacoco.xml` 只统计本模块 `src/test` 的执行数据，而 L2/L3/L4 的用例跑在 `nextentity-tests-jpa` 的 JVM 里——只看模块报告会把「语义套件跑到了、模块单测没跑到」的行误判成缺口（`JpaOperands` 的 `sub`/`mul`/`div` 分支就是这么被误判的）。所以 `nextentity-tests-jpa/pom.xml` 绑了 `jacoco:report-aggregate`，`mvn -pl nextentity-tests-jpa -am verify` 之后看 `nextentity-tests-jpa/target/site/jacoco-aggregate/jacoco.xml`，那才是「全部测试合起来」的口径。

用合并口径实测（`564` H2 + `536` 容器用例全跑，两个通道各 skip 1 条，见下「方言矩阵」），生产代码里没被执行的行**只剩清单里那些**，但四个模块要分开看口径：

- **聚合报告只含 `nextentity-jpa` 与 `nextentity-testing` 两个 group**（`report-aggregate` 只收直接编译依赖，`api`/`core` 是传递依赖、`spring` 是 test 作用域，都不进聚合文件）。`nextentity-jpa` 组里只剩四行：`jpa/meta/JpaEntityAttribute.java:48-49`、`:58-59`；`jpa/exec/JpaExecutor.java:122`、`:124` 的**行已被模块单测执行**，只剩分支没走全（见下表对应条目）。
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

**覆盖率快照**（合并口径，第 5 轮结束时；复算命令见上）：`api` 行 286/286（100%）、分支 80/81（98.8%）；`core` 477/480（99.4%）、分支 222/222（100%）；`jpa` 365/369（98.9%）、分支 163/166（98.2%）；`spring` 12/12、零分支；`testing`（oracle 自身的自检路径）3095/3120（99.2%）、分支 582/623（93.4%）。**生产代码四个模块合计行 1140/1147（99.4%）、分支 465/469（99.2%）**。剩下的 **7 行 / 4 分支**就是下表里 `api/Conditions.java:104`、`core/builder/PathRefs.java:90-92`、`jpa/meta/JpaEntityAttribute.java:48-59`、`jpa/exec/JpaExecutor.java:122/124` 四条（第 1、4 条是分支，第 2、3 条是行）；表里另有几行是「模块级 JaCoCo 归因范围」与「探针位置」问题（合并口径下已被执行），逐行说明见对应条目。

**已判定不可达与度量限制**（这些未覆盖行**已论证**，不再按缺口追；新增未覆盖行要么补用例，要么先在这里写下论证）：

| 位置 | 为什么不可达 |
|---|---|
| `api/Conditions.java:104` 的 `parent == null` 子分支 | `PathOperatorValue` 只在 `AlwaysTrue.or(ref)` 时 `parent` 为 `null`，而那条路径 `suppressed == true`，在 `:102` 就返回了 |
| `core/builder/PathRefs.java:90-92` | `writeReplace.invoke(ref)` 在合法方法引用上不会抛 `ReflectiveOperationException` |
| `jpa/meta/JpaEntityAttribute.java:48-49`、`:58-59` | `resolveField` 已 `setAccessible(true)`，之后的 `IllegalAccessException` 在正常 JVM 上不会发生 |
| `jpa/exec/JpaExecutor.java:122`、`:124`（行已被模块单测执行，剩 3 个分支未走全） | `shapeRow` 的两侧取决于 Hibernate 返回形状，公开查询造不出「单列却返回长度≠1 的 `Object[]`」。**这条不是靠读代码断的**：`STRONGER` 组已经把这四行变异过（`JpaExecutor:121`、`:122`×2、`:124`），注入是真做过的，存活原因是"构造不出能走到另一侧的输入"，不是"没试"。另外单独做了三次定向注入复核（`jpa` + `tests-jpa` 两模块一起跑）：把 `:121` 的 `itemCount == 1` 换成恒真、把 `:122` 的 `array.length == 1` 换成恒真——**都仍然全绿**（多列查询在两条路径上返回的是同一个 `Object[]`，单列查询恒是长度 1 的数组，无输入可区分）；把 `:124` 的整个 else 分支换成"一律包一层"——**红**，说明 `Object[]` 那一侧是活的、`new Object[] {row}` 那一侧才是不可达的。与 `JpaPersistExecutor:89/:90`（detach 分支）同类 |
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
| surefire | 除 `*IntegrationTest` 外的全部（H2） | 无 | — |
| failsafe | `**/*IntegrationTest.java`（显式 `<includes>`） | Docker + `mysql:8.0.18` | `-DskipITs` |

两个方向都写死：surefire `<excludes>` 排掉 `*IntegrationTest`，failsafe `<includes>` 收这些类。failsafe 的默认 includes 是 `**/IT*`、`**/*IT`、`**/*ITCase`，与项目约定的 `*IntegrationTest` 一个都不匹配——不显式配置时容器用例会被 surefire 跑掉、failsafe 一条也收不到。容器用例统一 `@Tag("container")` + `@ExtendWith(MySqlL4Gate.class)`。当前实测条数：surefire **564** 条（全 H2）、failsafe **536** 条（真 MySQL，含 `@Version` 乐观锁、`@Convert`、批量写、悲观锁四类写路径场景，另加嵌入/时间/自增主键三族写侧维度），两个通道各跳过 **1** 条——就是 `TemporalWriteContract` 里那条 PG 兼容模式专用的 `Instant` 偏移用例（`@DisabledIfSystemProperty` 标记，默认两通道都不跑，PG 通道跑它、同时关掉严格相等那条），**不是**新引入的静默跳过。拆分之初是 130 / 110，差额来自后续补的差分 oracle（见下「差分 oracle 台账」）、写侧往返用例与写侧维度覆盖（见上「写侧维度覆盖」）。

**时区必须钉在构建参数上，不能钉在用例里**：`nextentity-tests-jpa/pom.xml` 的 `pinned.timezone`（默认 `Asia/Shanghai`）同时进 surefire/failsafe 的 `-Duser.timezone` 与 `-Dnextentity.pinnedTimezone`，后者是时间契约自检的期望值——两个 `-D` 同源，「换过时区也全绿」才不是空话。理由有两层：`Instant` 往返断言的区分力全在「墙钟被挪过就会差整小时」，跑测机器若在 UTC，这条断言对「按 JVM 时区换算」这类缺陷的区分力归零；而 Hibernate 在 `buildSessionFactory()` 时就缓存了默认时区，装置又由子类字段初始化创建，所以 `@BeforeEach` 里 `TimeZone.setDefault` 是「晚于建连」的——实测会把 `LocalDate.of(2024,3,10)` 写成 `2024-03-09`，等于把装置变成非法的时区实验。换时区验证用 `-Dpinned.timezone=America/New_York` 整通道跑；**不要**用 `-DargLine=...`，jacoco `prepare-agent` 会覆盖 `argLine` 属性、且 pom 已把 `-Duser.timezone` 写进同一个 `argLine`，后写胜出，改了等于没改。副作用要记住：这是**模块级** fork 参数，本模块全部 564 条用例都在该时区下跑（本模块只有 `TemporalWriteContract` 读 `ZoneId.systemDefault()`，其余用例与时间无关），CI 机器若在别的时区，行为与本地不同。

**容器通道不许静默降级**：`MySqlL4Gate` 是自定义的 `ExecutionCondition`，默认「没有 Docker 就跳过」（本地开发需要这个宽进），但加上 `-Dnextentity.requireContainer=true` 后不再跳过——Docker 缺失时容器在静态初始化阶段启动失败，构建直接变红。**CI 必须带这个属性**，否则真方言对拍会悄悄变成 skip，报表上看不出区别，L4 就成了纸面门禁（数字以「通道拆分」一节的实测条数为准）。**CI 已接入**：`.github/workflows/ci.yml`（push/PR）与 `nightly.yml`（每日 02:00 Asia/Shanghai）都经 `tools/gate.sh` 的 `ci`/`night` 档调 `-Dnextentity.requireContainer=true`，且凭证对「跳过数超过申报预算」判缺口（容器通道在 `ci`/`night` 档申报预算 `1`，就是那条 PG 专用用例；预算写死在 `tier_ci`/`tier_night` 里，改不动的是默认值 0）——所以这条门禁不再依赖人记得加属性。三条路径都实测过：宽进 + 无 Docker → 容器用例全部 skip / SUCCESS；强制 + 无 Docker → 容器类整体 error / FAILURE；强制 + 有 Docker → 536 条全 pass（1 条 PG 专用用例按标记 skip）/ SUCCESS。

**镜像源**：容器镜像走 `docker.xuanyuan.run`（`docker.xuanyuan.run/library/mysql:8.0.18` 与 `docker.xuanyuan.run/testcontainers/ryuk:0.11.0`），不走 `registry-1.docker.io`——受限网络下直连 Docker Hub 会 `EOF`，而容器用例是 L4 的唯一载体，镜像拉不到就等于这层没跑。两处细节：`MySQLContainer` 会校验镜像仓库名，用镜像站前缀必须显式 `asCompatibleSubstituteFor("mysql")`，否则被判「不兼容」拒启；Ryuk 是 Testcontainers 另起的回收容器，默认也从 Docker Hub 拉，所以它要单独用 `ryuk.container.image` 属性（同时给 `TESTCONTAINERS_RYUK_CONTAINER_IMAGE` 环境变量，防版本差异只认其中一条）指到同一个源。换源或换版本用 `-Dnextentity.mysql.image=` / `-Dnextentity.ryuk.image=` 覆盖，不必改 pom。实测确认：运行期 `docker ps` 里出现的就是这两个镜像站的引用，跑完由镜像站的 Ryuk 回收干净。

**变异测试（PIT，已接入四个模块）**：`mvn -pl nextentity-api pitest:mutationCoverage`、`-pl nextentity-core`、`-pl nextentity-jpa`、`-pl nextentity-spring`。它只出分数、不新增用例形态，所以是**度量层**而不是五层防线之一。四处阈值都是 **95%**，写在各模块 pom，低于阈值构建失败；都不绑生命周期，由 `tools/gate.sh night` 与发版前的 `release` 档显式执行（`ci`/`pr` 档刻意不跑：单模块 PIT 分钟级，不该挡 push）。**零变异是已知假绿通道**：根 pom 的 `failWhenNoMutations=false` 让「一条都没变异」也算 BUILD SUCCESS，所以凭证额外要求 `mutations > 0`，并把这个分数按 PIT 自己的口径（`detected / (detected + survived + no_coverage)`）与模块 pom 的 `mutationThreshold` 交叉核对，两条任一不满足即红，见下「执行凭证」。`spring` 只有两个类（`AbstractRepository` 纯委派、`NextEntityAutoConfiguration` 纯条件装配），但「漏委托一个方法」（把某个 `query()`/写方法漏换成直通）与「开关条件写错」（`@ConditionalOnProperty` 的前缀/名字/`havingValue` 打错）正是编译过、测试假绿的典型形态，所以也纳入。**注意 PIT 不变异注解**，所以开关条件那一格由 `NextEntityAutoConfigurationTest` 的四条用例（缺失/`false`/`1` 不注册、`true` 注册且能解析真元模型）兜，不由变异分数兜。

当前实测：

| 模块 | 变异范围 | 生成 | 杀死 | 分数 | 已知存活 |
|---|---|---|---|---|---|
| `api` | `io.github.nextentity.api.*` | 186 | 185 | 99% | 1 条等价变异 |
| `core` | `io.github.nextentity.core.*` | 242 | 242 | 100% | — |
| `jpa` | `io.github.nextentity.jpa.*`（全模块，约 28s） | 125 | 123 | 98% | 2 条等价变异 |
| `spring` | `io.github.nextentity.spring.*` | 3 | 3 | 100% | — |

**度量口径（上表的数字只覆盖本模块 `src/test`，别读成「全部测试都在杀变异」）**：各模块 pom 的 `targetTests` 都是本模块包（`io.github.nextentity.<模块>.*`），所以 `nextentity-testing` 的 20 个一致性基类与 `nextentity-tests-jpa` 的语义套件**一条都不计入任何变异分数**——它们是 L2/L3 的正确性证据，不是度量输入。想知道语义套件单独能杀多少，得手工跨模块合并测量：先 `./mvnw -pl nextentity-tests-jpa test-compile`，再用 `-DadditionalClasspathElements` 把 `nextentity-tests-jpa/target/test-classes` 连同 `dependency:build-classpath`（`-Dmdep.includeScope=test`）出来的测试类路径一起喂给 jpa 模块的 PIT，并把 `-DtargetTests` 换成 `io.github.nextentity.tests.jpa.*`。

本次实测（同一份 `jpa` 代码、同样 125 个变异）：模块单测单独 **123/125（98%）**，语义套件单独 **119/125（95%）**，两者并集 **123/125**。两层各有盲区、并集才齐：语义套件单跑漏掉的 6 条（`JpaPersistExecutor.nextVersion:152`、`JpaEntityAttribute.type:41`、`JpaMeta.association:54` 的 NO_COVERAGE，加 `JpaPersistExecutor:89`/`:90`、`JpaPredicates.between:92` 的存活）里有 3 条由模块单测补上。补测过程中语义套件从 118 涨到 119（`whereClause:144` 的 `return null` 被写侧往返覆盖）。结论：**变异分数不能替代语义套件，语义套件也不能替代变异分数**，两者覆盖面不互相包含，夜间档两条都要跑。

等价变异不算待补的债，但每新增一条都必须在对应测试的 javadoc 里留下论证，没有论证的存活一律按缺口处理：

- `api/Conditions:206` 的 `new ArrayList<>(others.size() + 1)` 被 `MATH` 变异成 `- 1`：只影响局部 `ArrayList` 的初始容量；所有调用点都先做 `others.isEmpty()` 守卫（到达时 `size() ≥ 1`），返回的 `List` 内容、顺序、类型与对外可观察行为完全一致。要杀它只能改生产代码（把守卫挪进方法或去掉容量提示）。
- `jpa/exec/JpaPersistExecutor:89`、`:90`：`detach` 分支的条件取反与调用删除。走到该行时 `contains()` 已在方法开头判过为 `false`（为 `true` 时方法已早退），而 JPA 规定「对未与持久化上下文关联的实体调用 `detach` 会被忽略」，因此该调用及其条件都不改变任何可观测状态。**已实验证实**：把 `:90` 那行删掉后跑全量语料（模块单测 + 语义套件 + MySQL 容器通道）零新增失败；论证同时写在 `JpaPersistVersionTest#updateLeavesEntityDetached` 的 javadoc 里，那条用例只钉「更新后入参仍是 detached」，不假装覆盖这两条变异体。要杀它们只能删掉生产代码里这行冗余调用。

`jpa` 的门禁原先只覆盖 `jpa.compile.*`（49 个变异），理由是「exec/meta 每个变异都要起 Hibernate，太慢」；实测全模块只需 28s，该理由不成立，已扩到全模块并把阈值从 90 提到 95。

**变异组本身的强度也会骗人（`STRONGER` 组，一次性测量，不进流水线）**：默认 `DEFAULTS` 组只做条件取反、算术值替换、返回值常量替换三类，而「整条 `if (...)` 被换成恒真/恒假」与「操作符互换」都在 `STRONGER` 里——恰好是 fail-open 缺陷的形状。用 `-Dmutators=STRONGER -DmutationThreshold=0` 跑四个模块，实测 **api 218 生成 / 215 杀、core 321 / 320、jpa 187 / 175、spring 3 / 3**，存活合计 **16 条**（刻意不写分模块数字：`target/pit-reports` 不入库，分模块数既不可复核、又会被一次重测推翻，逐条清单见下）；同一次 `DEFAULTS` 测量的存活是 3 条（186 / 185、242 / 242、125 / 123、3 / 3），即 `STRONGER` 多暴露 13 条。逐条核对后：**1 条是被语义套件杀死的**（`jpa/exec/JpaExecutor:47` 的锁模式下发，语义套件的锁用例覆盖它，而模块级 PIT 只看本模块 `src/test`，见上「度量口径」），**11 条判等价**（8 条见下面各条，另 3 条 `api/Conditions:206`、`jpa/exec/JpaPersistExecutor:89`、`:90` 已在上面的等价变异清单里），**4 条是防御性不可达分支**（`jpa/exec/JpaExecutor:121`、`:122`×2、`:124` 的 `shapeRow`：已注入验证，存活原因是构造不出走到另一侧的输入——论证与 `JpaPersistExecutor:89/:90` 的 detach 分支同类，见上文「已判定不可达与度量限制」表，此处不重述）。复现方式：`./mvnw -pl <模块> install -DskipTests` 之后先 `./mvnw -pl <模块> test`（PIT 不触发 `test-compile`，复用陈旧 `test-classes` 会算出假存活体），再 `./mvnw -pl <模块> pitest:mutationCoverage -Dmutators=STRONGER -DmutationThreshold=0`，读 `target/pit-reports/mutations.xml`。等价清单（**来源有两种，别混读**：多数是 `STRONGER` 存活体，`core/spec/Window.limited()` 来自下面第二轮故障注入的 `core11`，它不在 `STRONGER` 的存活体里）：

- `api/Conditions:101` 的 `if (suppressed) return alwaysTrue();` 整个分支：`suppressed == true` 只由 `AlwaysTrue.or(PathRef)` 产生（`Conditions:196` 显式传 `parent = null`），而 `skip()` 的 `parent == null` 分支返回的是同一个 `ALWAYS_TRUE` 单例，两条路径的可观测结果完全相同。
- `api/Conditions:104` 的 `return parent == null ? alwaysTrue() : parent;` 被换成 `return parent;`：`skip()` 只有 `or(PathRef)` 造出的链才走得到，而 `PathOperatorValue` 的两个构造点里，传 `parent = null` 的那个（`Conditions:196`）同时 `suppressed = true`——执行到第 104 行时 `parent` 必然非 `null`，`parent == null` 这一支是死代码。**已实验证实**：注入后跑 `-pl nextentity-api test -Dtest=PredicateOrOperatorTest` 全绿（41/41）。
- `core/spec/Window.limited()` 的 `return limit != null` 被换成 `return offset != null`：该方法全仓库只在 `QueryBuilder#spec(Window)` 读一次，而到达那里的 `Window` 要么两字段皆 `null`（`Window.NONE`），要么两字段皆非 `null`（`first()` / `limit(n)` / `slice(offset, limit)`），两种写法对该判定等价。**这条等价依赖"读点只有一个"**：`QuerySpec#forExists()` 造的是 `new Window(null, 1)`（只有 `limit` 非 `null`），现在没人读 `limited()` 所以无差异；哪天 exists 路径开始读它，这个变异就不再等价。
- `core/util/ImmutableList.ofIterable` 的 `iterable instanceof Collection<?> sized` 同理（只影响新建 `ArrayList` 的初始容量）。
- `jpa/compile/JpaPaths:62` 的 `meta.association(...)` 判定被换成恒真（中段一律 `join`）：Hibernate 7 对嵌入属性的 `join` 是"虚拟"的——渲染成同一批列，语句与结果逐字节不变。**已实验证实两次**：单层嵌入（`Shipment#address.city`，`JpaPathsNestedTest#embeddedMiddleSegmentUsesGet`）与两段嵌入（`Warehouse#location.coordinates.zone`，`JpaEmbeddedChainTest#nestedEmbeddedPathUsesGet`）在注入后都仍然绿，且整句 SQL 一字不差；三层嵌入的四段路径（`location.coordinates.datum.code`，`JpaEmbeddedChainTest#nestedEmbeddedPathAtFourthSegmentUsesGet`）走的是同一段递归，并顺带覆盖了前缀缓存第一次新建前缀项的那一步。要杀它只能改生产代码（例如把 `association` 判定换成显式的 `get`/`join` 双分支断言），或换一个不把嵌入 `join` 摊平的 JPA 实现。**这条等价依赖 Hibernate 的实现细节**：若某个实现给嵌入 `join` 加上 `is not null` 谓词（INNER 的常见实现），嵌入列全 `null` 的行会静默消失——那是结果差异。`JpaEmbeddedChainTest#nullEmbeddedRowIsReturned` 钉住"嵌入为 null 的行必须返回"，换大版本或接第二后端时由它先报；`JpaPaths.navigate` 的 javadoc 也记了同一句限定。
- `jpa/meta/JpaMeta` 的 `resolved instanceof PluralAttribute` 判定**曾经**是等价变异（`PluralAttribute#isAssociation()` 本来就返回 `true`，去掉特判后集合关联仍走 `isAssociation()` 一侧）。它现在**不再等价**：该判定已抽成 `JpaMeta#pluralAssociation`，成为 `JpaPaths` 拒绝「集合关联路径」的唯一依据——去掉它，`where(Article::getTags)` 会渲染成一个 JOIN 并静默返回错行数（`JpaPluralAssociationRejectionTest` 三条用例钉住拒绝分支，`mvn -pl nextentity-jpa pitest:mutationCoverage` 已无这条存活体）。
- `jpa/compile/JpaQueryBuilder:60`、`:70` 的 `!spec.groupBy().isEmpty()` / `!spec.orderBy().isEmpty()` 判定被换成恒真：`builder.groupBy(List.of())` / `builder.orderBy(List.of())` 是 JPA 的 no-op，空列表照样下发与不下发等价。

所以「`STRONGER` 下还有存活体」不等于「还有盲区」：判定顺序永远是「先确认语义套件是否已覆盖（它不在任何变异分数里），再确认是否等价」，两步都不成立才是缺口。

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
3. **模块 PIT 的存活体不能直接当盲区清单**：`STRONGER` 的 16 条存活体里，只有 1 条（`jpa/exec/JpaExecutor:47` 的锁模式下发）是被语义套件真杀死的覆盖，11 条等价、4 条防御性不可达（见上「变异组本身的强度也会骗人」）。把模块分数直接当结论会同时犯两个错：把等价变异当缺口，把语义套件的覆盖当成没覆盖。

**方言矩阵（已实测五次，opt-in，不是默认通道）**：`nextentity-tests-jpa` 的建库入口 `HibernateHarness#start`（`nextentity-tests-jpa/src/test/java/io/github/nextentity/tests/jpa/support/HibernateHarness.java`）按系统属性 `-Dnextentity.h2mode` 分派，判定逻辑是纯函数 `HibernateHarness#resolveMode(String)`：缺省（属性缺省或空白）`mysql`，URL 与从前逐字节相同；`postgresql` 时改连 `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH` 并钉 `PostgreSQLDialect`；其他取值（含大小写变体）归一化后仍不认识就抛 `IllegalArgumentException`，不静默回退（打错一个字母必须当场炸，否则等于什么都没跑）。**不读环境变量**：环境变量会被 fork 出来的 surefire JVM 继承，一条 `NEXTENTITY_H2MODE=postgresql` 就能让后续所有构建静默跑成另一个方言，而日志里看不出来——方言必须是命令行显式声明的。跑法：`./mvnw -pl nextentity-tests-jpa -am test -DskipITs -Dnextentity.h2mode=postgresql`（**要带 `-am`**；不带时 `nextentity-testing` 从 `~/.m2` 取陈旧快照，表现是新写的基类构造器「找不到方法」）。开关自身的正确性有独立用例 `HibernateHarnessModeTest`（5 条，纯函数，不连库）：钉住 MySQL 模式的 URL 与历史逐字节一致、PG 模式的三个兼容开关一个不少、方言只在 PG 下钉、未知取值失败而不是静默回退、NULL 序映射与方言一致——这几行的错误形态都是「默认通道照样全绿」，只能靠直钉。

第一次实测（H2 侧 535 条）：**25 红，其中 16 条指向 oracle 而非库**——排序/分组/关联差分的内存侧内建了 MySQL 的「ASC 时 NULL 在前」，PG 是 NULLS LAST。这 16 条按「一致性基类保持后端中立」那条红线属于违规，已修（见下「NULL 序参数化」），不是被当成「已知差异」记下来的。

第二次实测（H2 侧 539 条，修完 R1 之后的基线；此后新增方言开关自身的 5 条纯函数用例，现为 540 条）：**8 红，全部可归因，零生产缺陷**——3 条是 SQL 文本快照/渲染（`ItemSqlSnapshotTest` / `ItemProjectionMapperRenderingTest` / `ItemTier1RenderingTest` 各一格），快照钉的是字面量 SQL，换方言必然不同，这不是缺陷；5 条是锁模式（`PostgreSQLDialect` 给 `PESSIMISTIC_WRITE` 渲染 `for no key update`、给 `PESSIMISTIC_READ` 渲染 `for share`，H2 的 PG 兼容模式不认这两条语法，真 PG 接受），其中 `ItemQueryEntrySnapshotTest` 的两条失败按 cause 归这一类（两条 cause 都是 `for no key update`），不属于快照类。其余 531 条（现 532 条；含全部谓词格、排序、分组、关联、窗口、写侧往返）在两个方言下断言逐字相同——**这才是「换后端也通过」的证据**，快照与锁模式两类明确排除在外。`Oracle` 模式没做：H2 的 Oracle 兼容模式覆盖不到 Oracle 的实质差异（空串即 NULL、`NUMBER` 精度），做了只会给假证据。

第三次实测（H2 侧 558 条，补完写侧维度用例之后）：**10 红**，上一轮那 8 条一条不多一条不少，新增的 2 条都在 `TemporalWriteContract`（`insertAndReloadPreserveTemporalValues`、`updateWritesAllTemporalColumns`）：`Instant` 写进去 `2024-03-01T10:15:30Z`、读回 `2024-03-01T02:15:30Z`，正好差一个 JVM 时区（+08:00）。**这不是生产缺陷，已用纯 Hibernate 对照证伪**：同一个实体在 PG 模式下不走 `nextentity`、直接 `em.persist` + `em.find`，一样差 8 小时，原生查询取回的原始值是 `OffsetDateTime 2024-03-01T10:15:30+08:00`；两种模式建出来的列类型都是 `timestamp with time zone(6)`（`information_schema.columns` 实测），差异只在 `PostgreSQLDialect` 对 `Instant` 的绑定路径与 H2 的 PG 兼容模式——真 PostgreSQL 的 `timestamptz` 存的是时刻，不会有这个偏移。归入「H2 的 PG 兼容模式不认 PG 语义」这一类（与锁语法同因），不改成跳过、也不削弱断言：默认通道（H2 的 MySQL 模式 + 真 MySQL 容器）两处都逐字通过。
第四次实测（H2 侧 563 条，补完 reviewer 的两轮整改之后）：**8 红，回到「3 快照 + 5 锁」，零生产缺陷**。上一轮那 2 条 `Instant` 红已消掉，但**不是靠跳过**：`TemporalWriteContract` 里 `Instant` 的往返断言改成按方言分派（[TemporalWriteContract#insertAndReloadPreserveTheInstant](nextentity-tests-jpa/src/test/java/io/github/nextentity/tests/jpa/TemporalWriteContract.java) 与互斥的 [TemporalWriteContract#insertAndReloadLoseExactlyOneJvmOffsetOnPostgresCompatibleMode](nextentity-tests-jpa/src/test/java/io/github/nextentity/tests/jpa/TemporalWriteContract.java)：能保时刻的通道钉**相等**，PG 兼容模式钉「丢掉的偏移恰好是一个 JVM 时区偏移」——**当轮**用的是「漂移量小于 24 小时」的容忍带，第五次实测已换成精确断言，见下条），于是两种模式下这条用例都在跑、都在断言，skip 计数不变；写成 `assumeTrue` 会让该通道少一条证据且不进任何计数，正是「方言跳过必须可数」要防的那种静默降级。其余 555 条在两个方言下断言逐字相同（当轮 0 skip，`563 − 8 = 555`；第五次实测多出一对互斥用例，口径随之变成 `564 − 8 − 2 = 554`，见下条）。

第五次实测（H2 侧 564 条，第三轮审查整改之后）：**仍是 8 红、仍是「3 快照 + 5 锁」，零生产缺陷**（`ItemSqlSnapshotTest` / `ItemProjectionMapperRenderingTest` / `ItemTier1RenderingTest` 各 1 条，`ItemLockConsistencyTest` 3 条、`ItemQueryEntrySnapshotTest` 2 条），另有 1 条**严格相等**用例按标记 skip（互斥对的另一半，PG 专用那条在 PG 通道是跑的）。这次把 `Instant` 那条从「容忍带」换成**精确断言**：先实测漂移量——PG 兼容模式 `driftSeconds=-28800`、`offsetSeconds=28800`，恰好 `-1 ×` JVM 偏移；H2 的 MySQL 模式 `driftSeconds=0`、读回原值——于是 PG 分支钉 `drift == -offset`。上一轮那个「漂移小于 24 小时」覆盖 UTC−12…UTC+14 全部合法偏移，对「`Instant` 被时区挪动」零区分力，属于假断言，已废弃。严格相等那条与 PG 专用那条用 `@DisabledIfSystemProperty` / `@EnabledIfSystemProperty` 互斥，**两个通道的 skip 计数恒为 1**，且两条都在报表明面上。其余 554 条在两个方言下断言逐字相同。


**NULL 序参数化（R1，已落，方言中立那一格补齐）**：`ORDER BY x` 里 `null` 排前还是排后是**后端事实**，不是可推导语义，所以两处都改成显式传入、默认值 = MySQL：

- 分组/排序差分族（`ConsistencyTestBase`）：protected `nullOrdering()`，默认 `NULLS_FIRST`；`SortDifferentialTest` 的排序 oracle、`HavingDifferentialTest` 的分组 oracle 全部读它。
- 关联嵌套路径族（`AssociationDifferentialTest`）：构造器注入 `NullOrdering`（不设可覆写方法——这一族的方法数已顶到 PMD 的 `TooManyMethods` 上限，参数走构造器既不占方法位也不会被忘掉）。
- 两个族的 JPA 适配器从同一处取值：`HibernateHarness#nullOrdering(H2Mode)`（`MYSQL` → `NULLS_FIRST`，`POSTGRESQL` → `NULLS_LAST`）；无参 `nullOrdering()` 只负责读 `-Dnextentity.h2mode` 再转调它。
- **容器通道的差分类不读属性**，各自钉死 MySQL：`MySqlItemSortDifferentialIntegrationTest` / `MySqlItemHavingDifferentialIntegrationTest` 覆写 `nullOrdering()` 返回 `NULLS_FIRST`，`MySqlItemAssociationDifferentialIntegrationTest` 构造器传 `HibernateHarness.nullOrdering(H2Mode.MYSQL)`。它们连的是真 MySQL，与 H2 兼容模式开关无关；带 `-Dnextentity.h2mode=postgresql` 跑容器通道时若沿用属性值，排序/分组 oracle 会用 `NULLS_LAST` 去比真 MySQL 的 `NULLS_FIRST`，整族假红。只有 H2 侧的 `ItemSortDifferentialTest` / `ItemHavingDifferentialTest` / `ItemAssociationDifferentialTest` 读属性。

默认值仍是 MySQL，所以既有用例的断言逐字未变；接第二后端时只覆盖适配器那一行，不许放宽成「只钉非空部分」。

**L4 的写侧关联语义（已补）**：`update` 的 SET 子句跳过关联列这件事，此前只有 H2 证据（`nextentity-jpa/src/test/java/io/github/nextentity/jpa/JpaPersistAssociationTest.java:247` 的 `.doesNotContain("customer_id")`），容器通道一条都没有——把 `JpaPersistExecutor` 的跳过条件改坏，H2 会红、真 MySQL 通道静默放过。现在有 `MySqlAssetWriteSkipsAssociationIntegrationTest`（3 条）：语句层断「`update asset` 的 SET 里没有 `owner_id`」，结果层断「库里的外键仍是原值」（片段断言挡不住「多写一条 UPDATE 再覆盖回来」），再加一条 insert 的正向对照（证明跳过只作用于 `update`，没被误扩展到写入路径）。

**Spring 接线的容器变体（已补）**：`nextentity-spring` 的 `NextEntityRealBackendTest` 跑的是 H2，而「自动配置 + `SharedEntityManagerCreator` 的事务绑定代理 + 真方言」这一组合此前没有任何实证。现由 `nextentity-tests-jpa` 的 `MySqlSpringRepositoryIntegrationTest`（4 条，容器通道）补上：自动配置产出的工厂喂给 `AbstractRepository` 子类，在真 MySQL 上断三件事——回滚真丢数据（`setRollbackOnly` 后 `count()` 为 0）、本线程未提交的行对另一线程不可见（插入若被静默自动提交会立刻变红）、4 线程并发各查各的行。它用自己的实体 `SpringWiredEntity` 独占一张表，理由是**行集隔离**：多个容器类都在断言精确行集（`containsExactly`），共用一张表会互相污染行集。`create-drop` 不是理由——它只处理本 PU 映射的表，不会删别人的表，JUnit 默认也串行。这与 H2 版不是重复证据——H2 版证「代理是线程绑定的」，这里证「绑定的是真 MySQL 连接且提交边界正确」。

**执行凭证（`tools/gate.sh` + `tools/gate-receipt.py`，L0）**：前面每一条门禁都有一类共同的失效方式——**构建绿了，但该跑的通道没跑**。`-DskipITs`、无 Docker、`@Disabled`、PIT 的零变异、`~/.m2` 里的陈旧产物，五种都能让 `BUILD SUCCESS` 与「证据为零」同时成立，而人读日志只看最后一行。所以门禁的产物不是日志而是**凭证**：`tools/gate.sh <fast|ci|pr|night|release>` 跑完把 surefire/failsafe XML 与 PIT 报告一起解析成 `tmp/gate/<commit>-<ts>/receipt.json`，判定只看这份 JSON。

判据不是「报告存在」，而是五条：

- **报告比本轮新**：`mtime >= 本轮开始 epoch`。上一次构建留下的报告不算——这是「`Nothing to compile` 复用前序产物」的直接防线。
- **条数 > 0**：`tests == 0` 的通道报「报告存在但 tests=0」，不报通过。
- **必需通道必须真跑**：`-DskipITs` 下 failsafe 一份报告都不新（目录里上一轮的 XML 还在，靠逐文件 mtime 过滤才抓得住，这也是 `--require` 必须点名通道的原因）、`-Dnextentity.requireContainer` 下容器类整体 error，两种都判缺口；档位声明的步骤没落快照（被 `-am` 守卫拦下）同样算缺口，见 `merge --expect-step`。
- **PIT 不许零变异**：除阈值外额外要求 `mutations > 0`，堵住根 pom `failWhenNoMutations=false` 那条假绿通道；分数按 PIT 自己的口径算并与 pom 的 `mutationThreshold` 比对。
- **跳过要有申报预算**：每个通道的跳过条数上限由 `gate.sh` 逐步申报（`STEP_MAX_SKIP`），未申报的通道默认 0。跳过数超预算即红，且 problems 里逐个点名用例与理由——「跳过就是没跑，没跑不能记绿」。

三件事只有凭证能给出、日志给不出：**skip 清单连理由**（`方言跳过必须可数` 从「PR 里手写」变成自动抽取）、**`~/.m2` 与源码的时间戳倒挂**（`-pl` 不带 `-am` 时会读到旧语义）、以及**跨模块合并口径的条数**（`-am` 反应堆会把 jpa/spring 一起带进来，比单看目标模块多）。

档位与「该跑什么」一一对应：`fast` = 格式化检查 + 架构规则 + `api`/`core` 快测试（无容器）；`pr` = H2 564 + 遗留 727；`ci` = 前者 + 真 MySQL 容器通道 + `-Dnextentity.requireContainer=true`；`night` = `install` 刷快照 + 全量含容器 + 遗留 h2/mysql 双跑 + PIT×4；`release` = `clean verify` + api-compat。`ci`/`pr` 刻意不跑 PIT（单模块分钟级，不该挡 push），PIT 的落点在 `night` 与 `release`。

**尚未实施**（方向，不是已有能力——写进这里的东西不得当成现状引用）：

- ~~排序差分的方言化~~（已完成，见上「NULL 序参数化」）：`NULLS FIRST` / `NULLS LAST` 已是显式参数，接第二后端只需在适配器里覆盖一行。
- 故障注入常驻化：现在只是两次一次性实验（12 条 + 35 条手工注入），还没进流水线；连接中断、锁超时、方言不支持路径这三类也还没注入过。
- 变异测试的历史文件（`historyInputFile` / `historyOutputFile`）：现在每次都全量重算，接上增量后夜间档可以更快。
- L2 的参数绑定取值：`StatementInspector` 只拿得到语句文本，`where id = ?` 的两个占位符互换后 SQL 逐字节相同。唯一"文本相同而结果不同"的组合是 `single()` + `lock`，已补成同时断返回行（见上「L2 只证明…」一段）；其余位置由 L3 真库结果兜底。

**与 §6 的关系**：L3 是第二后端的验收口——新后端必须原样通过同一套一致性用例，不得为它新增特例分支（见 §6 第 8 条）。

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
| 投影成调用方类型走显式 `api.ProjectionMapper`（`expressions()` 声明列集 + `map(Tuple)` 造结果） | 反射方案编译期零保护（列名写错、DTO 字段改名都要到运行期才知道），且与 2.2.3 的 `select(Class<R>)` 同构——那正是 3.x 要去掉的东西；显式映射器把「查什么」与「怎么造」放进同一个对象，两者不可能错配，且映射代码是普通 Java（IDE 可跳转、编译期可查）。代价：`Tuple#size()` 的契约下限从 2 放宽到 1（映射器路径单列也产出 `Tuple`），并新增一条「列序 ↔ 取值下标」的漂移风险，靠「`IDX_*` 常量紧邻 `expressions()`」的写法约定 + 渲染列序用例（`ItemProjectionMapperRenderingTest#projectionColumnsAppearInExpressionOrder`）兜住 | `project(Class<R>)` 这类反射 + 注解自动映射 |

---

## 8. 新能力落位图

| 要加什么 | 落在哪 | 是否改 `api` |
|---|---|---|
| 新谓词（如 `regexp`） | `spec` 加节点 + `compile` 加分支 + `api` 加算子 | 是（增量） |
| 新值形态（表达式/聚合结果参与比较） | `core.spec.Operand` 加实现 + `jpa.compile.JpaOperands` 加 `switch` 分支（唯一渲染点） | 是（增量：`ColumnComparison` 等） |
| 新聚合函数 | `core.spec.AggregateFunction` 加常量 + `JpaOperands` 的 `switch` 分支 | 否（`api.Aggregates` / `NumberPath` 已是入口） |
| 分组后过滤（`having`） | `core.spec.QuerySpec.having` + `jpa.compile.JpaQueryBuilder` | 是（增量：`HavingStep`） |
| 新终止符（如 `keyset`） | `exec` 加方法 + `api` 加步骤接口 | 是（增量） |
| 新投影形态（record/DTO） | `api` 的 `ProjectionMapper` + `builder` 的 `RowAssembler`（行 → 结果对象）与 `ValueProjections`（表达式 → 投影项） | 否（映射器由调用方实现） |
| 新后端（JDBC） | 新模块实现 `Compiler`/`Executor` | 否 |
| 新方言差异 | 后端模块内部 | 否 |
