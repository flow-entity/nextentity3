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

L2 只证明「语句下发正确」，不证明「参数绑定正确」：`SqlRecorder` 只记语句文本，绑定顺序与取值抓不到。这一半证据属于待补项，补上之前由 L3（真库执行结果）兜底。

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

**度量体系**：

| 指标 | 角色 | 目标 |
|---|---|---|
| 变异分数（PIT） | 主指标 | `api` / `core` / `jpa` 均 ≥ 95%；当前实测 99% / 100% / 98%（见下「变异测试」，阈值写在各模块 pom） |
| 语义等价律条数 | 辅助 | 每条律至少 1 条差分用例，条数只增不减 |
| 方言矩阵通过率 | 辅助 | 100% 通过；skip 数单独报表，只许降不许升（矩阵目前是 H2 + MySQL 8.0.18 两列；skip 报表待建，见上「通道拆分」） |
| 缺陷逃逸率 | 辅助 | 发版后发现的语义缺陷数 / 版本，趋势必须向下 |
| 行/分支覆盖率（聚合口径） | **不作门禁**，仅诊断报表 | 不设百分比门槛；判据是「新增的未覆盖行必须落在下文『已判定不可达与度量限制』清单里」，落在清单外就按缺口处理 |

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

**覆盖率快照**（聚合口径，第 4 轮结束时）：`api` 行 286/286（100%）、分支 80/81（98.8%）；`core` 447/450（99.3%）、分支 196/196（100%）；`jpa` 360/364（98.9%）、分支 163/166（98.2%）；`spring` 10/10；`testing` 652/652（100%）、分支 48/48。合计行 1755/1762（99.6%）、分支 487/491（99.2%）。剩下的 8 行 / 4 分支就是下表全部内容。

**已判定不可达与度量限制**（这些未覆盖行**已论证**，不再按缺口追；新增未覆盖行要么补用例，要么先在这里写下论证）：

| 位置 | 为什么不可达 |
|---|---|
| `api/Conditions.java:104` 的 `parent == null` 子分支 | `PathOperatorValue` 只在 `AlwaysTrue.or(ref)` 时 `parent` 为 `null`，而那条路径 `suppressed == true`，在 `:102` 就返回了 |
| `api/Conditions.java:398` `AbstractAggregate.subject()` 的 `return null` | 为满足 `Op` 接口而存在，无调用点：聚合值当主语时 `subject` 挂在 `Leaf` 上 |
| `core/builder/PathRefs.java:90-92` | `writeReplace.invoke(ref)` 在合法方法引用上不会抛 `ReflectiveOperationException` |
| `jpa/meta/JpaEntityAttribute.java:48-49`、`:58-59` | `resolveField` 已 `setAccessible(true)`，之后的 `IllegalAccessException` 在正常 JVM 上不会发生 |
| `jpa/exec/JpaExecutor.java:122`、`:124` | `shapeRow` 的两侧取决于 Hibernate 返回形状，公开查询造不出「单列却返回长度≠1 的 `Object[]`」 |
| `testing/TerminalConsistencyTest.java:57`、`ProjectionMapperConsistencyTest.java:103` | **不是未执行，是 JaCoCo 归因限制**：这两个 lambda 体唯一的退出路径是被调方抛异常，探针插在被调方调用之后、`return` 之前，永远到不了。用例本身是通过的，断言也确实生效（已用 `javap` 看探针位置 + `-Xlog:exceptions` 观测到异常进 `catchThrowable` 双向确认）。修不掉，除非给 lambda 套一层只骗探针的空包装——那会把证据写糊 |

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

两个方向都写死：surefire `<excludes>` 排掉 `*IntegrationTest`，failsafe `<includes>` 收这些类。failsafe 的默认 includes 是 `**/IT*`、`**/*IT`、`**/*ITCase`，与项目约定的 `*IntegrationTest` 一个都不匹配——不显式配置时容器用例会被 surefire 跑掉、failsafe 一条也收不到。容器用例统一 `@Tag("container")` + `@ExtendWith(MySqlL4Gate.class)`。改完后的实测条数：surefire 130 条（全 H2，与拆分前一致）、failsafe 110 条（真 MySQL，含 `@Version` 乐观锁、`@Convert`、批量写、悲观锁四类写路径场景）。

**容器通道不许静默降级**：`MySqlL4Gate` 是自定义的 `ExecutionCondition`，默认「没有 Docker 就跳过」（本地开发需要这个宽进），但加上 `-Dnextentity.requireContainer=true` 后不再跳过——Docker 缺失时容器在静态初始化阶段启动失败，构建直接变红。**CI 必须带这个属性**，否则 110 条真方言对拍会悄悄变成 110 条 skip，报表上看不出区别，L4 就成了纸面门禁。三条路径都实测过：宽进 + 无 Docker → 110 skip / SUCCESS；强制 + 无 Docker → 18 个类 error / FAILURE；强制 + 有 Docker → 110 pass / SUCCESS。

**变异测试（PIT，已接入三个模块）**：`mvn -pl nextentity-api pitest:mutationCoverage`、`-pl nextentity-core`、`-pl nextentity-jpa`。它只出分数、不新增用例形态，所以是**度量层**而不是五层防线之一。三处阈值都是 **95%**，写在各模块 pom，低于阈值构建失败；都不绑生命周期，只在显式执行时校验。

当前实测：

| 模块 | 变异范围 | 生成 | 杀死 | 分数 | 已知存活 |
|---|---|---|---|---|---|
| `api` | `io.github.nextentity.api.*` | 186 | 185 | 99% | 1 条等价变异 |
| `core` | `io.github.nextentity.core.*` | 221 | 221 | 100% | — |
| `jpa` | `io.github.nextentity.jpa.*`（全模块，约 28s） | 125 | 123 | 98% | 2 条等价变异 |

等价变异不算待补的债，但每新增一条都必须在对应测试的 javadoc 里留下论证，没有论证的存活一律按缺口处理：

- `api/Conditions:206` 的 `new ArrayList<>(others.size() + 1)` 被 `MATH` 变异成 `- 1`：只影响局部 `ArrayList` 的初始容量；所有调用点都先做 `others.isEmpty()` 守卫（到达时 `size() ≥ 1`），返回的 `List` 内容、顺序、类型与对外可观察行为完全一致。要杀它只能改生产代码（把守卫挪进方法或去掉容量提示）。
- `jpa/exec/JpaPersistExecutor:89`、`:90`：`detach` 分支的条件取反与调用删除。走到该行时 `contains()` 已判 false，按 JPA 规范 `detach` 是 no-op，属死分支。

`jpa` 的门禁原先只覆盖 `jpa.compile.*`（49 个变异），理由是「exec/meta 每个变异都要起 Hibernate，太慢」；实测全模块只需 28s，该理由不成立，已扩到全模块并把阈值从 90 提到 95。

**尚未实施**（方向，不是已有能力——写进这里的东西不得当成现状引用）：

- 属性测试：Spec 生成器 + 差分 oracle，用生成替代手写组合。
- H2 兼容模式矩阵：以 `MODE=MySQL` / `PostgreSQL` / `Oracle` 跑同一套一致性用例。
- 故障注入：连接中断、锁超时、方言不支持路径。
- 变异测试的历史文件（`historyInputFile` / `historyOutputFile`）：现在每次都全量重算，接上增量后夜间档可以更快。

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
