# CHANGELOG

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

破坏性变更必须在对应条目里写明「为什么必须破」与迁移方式（见 [AGENTS.md](AGENTS.md)「公开 API 是契约」）。

## [3.0.0-SNAPSHOT] — 未发布

### Added

- 仓库骨架：`api` / `core` / `jpa` / `spring` / `testing` / `tests-jpa` / `bom` 七个模块与依赖方向约束。
- 设计正文 `docs/DESIGN.md`、使用正文 `docs/USAGE.md`。
- 本地门禁：Spotless(Palantir) + Checkstyle + PMD + SpotBugs + JaCoCo + Enforcer + ArchUnit + japicmp(API 兼容)。
- 新增 `nextentity-arch` 模块：ArchUnit 规则集中到一处，覆盖模块循环、`core` 包边界、`spec` 纯净与不可变、时间/随机注入、接口宽度、产物边界（api 的 class 只能来自 `nextentity-api`）。原 `core` 内的 `ArchitectureTest` 因 classpath 看不到 `jpa`/`spring` 而迁出。
- 静态检查补强：PMD 增加类级 NCSS、方法数、参数数、圈复杂度、耦合度、过宽 catch；Checkstyle 增加「catch 后返回默认值」拦截。
- `nextentity-api`：`Op` / `Operation`（19 个算子）、`LockMode`（5 常量，与 `jakarta.persistence.LockModeType` 同名）、`Path#refs()`、`SliceStep`、`Aggregates`，以及按「接口声明方法 ≤6」拆出的 `Comparison` / `Range` / `RangeIfNotNull` / `Nullness` / `Membership` / `Between` / `StringOps` / `PathOperator` / `StringOperator`。
- `nextentity-core`：`spec` 不可变 AST（`Comparison` / `Between` / `In` / `NullCheck` / `Logical` / `Constant` + `Projection` / `AggregateFunction` / `Selection` / `Ordering` / `Window` / `QuerySpec`）、`meta`（`EntityType` / `EntityAttribute`）、`builder`（步骤接口 → Spec、`Op` 解释器）、接缝（`Compiler` / `Executor` / `PersistExecutor` / `EntityOperationsFactory` / `EntityOperations` / `TransactionOperations`）。
- `nextentity-jpa`：`JpaMeta` / `JpaCompiler` / `JpaQueryBuilder` / `JpaPaths`（嵌套路径 LEFT JOIN + 前缀缓存）/ `JpaPredicates` / `JpaProjections` / `JpaExecutor` / `JpaPersistExecutor` / `JpaEntityOperationsFactory`。
- `nextentity-spring`：`NextEntityAutoConfiguration`（`nextentity.enabled=true` 时注册唯一的 `EntityOperationsFactory` bean）、`AbstractRepository`（构造器注入工厂 + `query()` + `insert(T)`）。
- `nextentity-testing` / `nextentity-tests-jpa`：后端无关的一致性基类 + SQL 快照工具（H2 `StatementInspector`），共 65 例。

### Changed

- `count()` 用 `count(TRUE 字面量)`、`exists()` 用 `select TRUE` + `limit 1`，与 2.2.3 的 `SELECT_COUNT_ANY` / `SELECT_ANY` 逐行对应（对照表见 `tmp/contract/JPA-SEMANTICS.md` §3）。
- 嵌套关联路径（`EntityPath.of(A::getB).get(C::getD)`）固定走 **显式 LEFT JOIN**，与 2.2.3 相同；同前缀路径只 join 一次。
- `single()` 命中多行抛 `IllegalStateException("found more than one result, expected one")`（2.2.3 的 api Javadoc 写的就是 `IllegalStateException`，虽然其实现抛 `NextEntityException`；point-mall 无 `catch` 依赖）。
- 写入失败改抛标准 JPA 异常（`OptimisticLockException` / `EntityNotFoundException`），不再抛 2.2.3 的 `NextEntityException`。
- `lock(jakarta.persistence.LockModeType)` 由抽象方法变为 `default`（内部转 `api.LockMode`），以保留 2.2.3 的 3 处存量调用点；抽象方法改为 `lock(api.LockMode)`。
- `Path` / `NumberPath` / `StringPath` 不再继承 `PathRef`：`PathRef` 只表示用户写的方法引用，路径值由新增的 `where(Path)` / `where(StringPath)` / `orderBy(Path)` 承载。**为什么必须破**：继承会让后端的 `where(PathRef)` 把 api 值对象当 lambda 解析（`writeReplace()` 抛 `NoSuchMethodException`）。

### Removed

- 相对 nextentity 2.x 不再提供：JDBC 第二引擎与方言、自研元模型、实体事件总线、CGLIB 仓储代理、`Tuple5`~`Tuple10`。
- `api` 包 44 个类型整体移除（完整清单与逐条理由见下方 Migration 第 1 节）。
- `core.expression.*`、`core.meta.impl.DefaultMetamodel`、`core.util.Iterators`、`core.EntityTemplateFactory`、`spring.EntityFactoryBuilder` 等 2.x 内部符号不再提供。

### Migration

**0. 消费方实测结论（point-mall 2.2.3 → 3.0.0）**

- 改 2 行版本号 + 重写 1 个仓储基类实现文件即可切换；25 个引用 `io.github.nextentity` 的文件中 24 个零改动。
- 版本号（`pom.xml` 两行）：
  ```xml
  <nextentity.version>3.0.0-SNAPSHOT</nextentity.version>
  <id.generator.version>2.0.0-SNAPSHOT</id.generator.version>
  ```
- 唯一需要改代码的地方：若曾像 point-mall 的 `AppRepositoryImpl` 那样直接 import 2.x 内部类（`DefaultMetamodel`、`EntityTemplateFactory`、`SimpleExpressionImpl`、`PathNode`、`Iterators`），改为通过 `core.EntityOperationsFactory` 取 `EntityOperations`：`query()` 查询、`insert/insertAll/update/updateAll` 写入、`entityType().version()` 取 `@Version` 属性回写。
- 回滚只需把上面两行改回 `2.2.3` / `1.3.3`（无 schema 变更、无数据迁移）。

**1. 整类移除（44 类，按原因归组）**

| 移除的类型 | 为什么必须破 |
|---|---|
| `ExpressionBuilder` + `PathOperator`/`NumberOperator`/`StringOperator`/`Conjunction`/`Disjunction` | 2.2.3 用 50+ 方法的单接口承载全部算子，违反「接口声明方法 ≤6」；3.x 拆成 7 个窄算子接口 + 2 个顶层算子接口 |
| `SimpleExpression` / `StringExpression` | 同上拆分后的空壳；`count/countDistinct/max` 迁到 `Aggregates`，字符串算子迁到 `StringOps` |
| `EntityRoot` | 2.x 的 `api` 通过它 `import core.util.DefaultEntityRoot`（api→core 倒挂）；3.x `api` 零依赖后不存在，改由 `Path.of(...)` 的 api 内值实现承担 |
| `EntityContext` / `EntityDescriptor` / `EntityOperations` / `EntityPersistor` / `Executable` / `Entity` / `Persistable` | 全库 0 引用；写入面由消费方自己的仓储接口提供，3.x 不再重复定义持久化契约 |
| `FetchStep` / `HavingStep` / `SubQueryBuilder` | `fetch(...)` / `having(...)` / `toSubQuery()` 全库 0 调用点 |
| `UpdateSetStep` / `UpdateWhereStep` / `DeleteWhereStep` | `update().set().where()` / `delete().where()` 0 调用点；写入走仓储级 `insert/update/insertAll/updateAll` |
| `WhereStep` | 仅用于承载 8 个 `where` 重载的中间接口；3.x 由 `BaseWhereStep` + `Whereable` 承担 |
| `BooleanPath` / `PathRef.BooleanRef` | 0 引用；布尔属性走 `where(..).eqIfNotNull(b)` |
| `PathRef.BigDecimalRef`/`ByteRef`/`DoubleRef`/`FloatRef`/`IntegerRef`/`LongRef`/`ShortRef` / `PathRef.EntityPathRef` | 0 引用；数值统一由 `PathRef.NumberRef` 承载，关联统一走 `EntityPath` |
| `model.Tuple` + `model.Tuple3`~`Tuple10` | 非目标（停止演进）；全库只用 `Tuple2` |
| `model.Order` / `SortOrder` | 0 引用；排序只保留 `orderBy(PathRef\|Path) → asc()/desc()` |

**2. 方法移除 · 收窄（同类合并）**

| 类型 | 移除/收窄 | 为什么必须破 |
|---|---|---|
| `Collector` | `exists(int)` / `list(int,int)` / `toSubQuery()` 移除；`slice(int,int)` 迁到 `SliceStep` | 前三个 0 调用点；`Collector` 已顶到 6 个方法上限 |
| `Collector` | `first()` / `list(int)` 由 default 变 abstract | 2.2.3 的 default 实现 import 了 `core.expression.SliceImpl` + `core.util.ImmutableList`（api→core 反向依赖） |
| `model.Slice` | `offset()` / `limit()` / `map(Fn)` 移除；`to(Fn)` default 变 abstract | 前三个 0 调用点（`offset(` 全是 `Pageable.offset()`）；`to()` 同理依赖 core 实现 |
| `model.Tuple2` | `get0()` / `get1()` default 变 abstract | 2.2.3 的 default 转发到已删除的 `Tuple.get(int)` |
| `SelectStep` | 44 个投影重载 → 4 个：`select(PathRef)`、`select(Expression)`、`select(Expression,Expression)`、`selectDistinct(PathRef)` | 其余均 0 调用点 |
| `Whereable` | 8 个 `where` → 5 个：移除 `where(NumberRef)`、`where(NumberPath)`、`where(EntityPathRef)` | 三个重载 0 调用点；保留的 5 个全部有调用点 |
| `GroupByStep` / `OrderByStep` | 变长与 `Collection` 重载全部移除 | 0 调用点（`groupBy` 6 处、`orderBy` 138 处全是单参形态） |
| `OrderOperator` | 移除 14 个终止符 default 转发 | 虚空防御：`OrderOperator` 只该给方向，终止符由 `BaseWhereStep` 继承 `Collector` 提供 |
| `NumberExpression` | 移除 `add/subtract/multiply/divide/mod`（含 `*IfNotNull`）15 个、`avg()`、`min()` | 全库 `.avg(` / `.min(` 0 调用点；算术表达式非目标 |
| `StringExpression` | 移除 `notLike/notContains/notStartsWith/notEndsWith`、`startsWith/endsWith`、`lower/upper/substring/trim/length` 及其 `*If*` 变体 | 0 调用点（point-mall 里这些全是 `java.lang.String` 的方法） |
| `Predicate` | 移除 `not()` / `ofTrue()` / `ofFalse()` 与变长 `and`/`or` | 0 调用点；短路由「不实现 `Op` 的 `Predicate`」表达，恒假由后端从 `IN` + 空参推导 |
| `EntityPath` / `Path` / `NumberPath` / `StringPath` / `Expression` | 移除 `of(String)`、`of(BooleanRef)`、`of(EntityPathRef)`、`apply(T)`、`get(NumberRef)` 等类型不安全或 0 调用的重载 | 0 调用点；`of(String)` 是 DESIGN §4 明令移除的类型不安全重载 |

**3. 形状变化（只改返回类型/修饰符，调用点可解析）**

| 类型 | 变化 | 为什么必须破 |
|---|---|---|
| `SelectStep` / `GroupByStep` | `select(...)` / `groupBy(...)` 返回 `BaseWhereStep` | `WhereStep`、`HavingStep` 删除后中间步骤收敛为一个；调用点只链 `where/orderBy/slice/list` |
| `OrderOperator` | `asc()` / `desc()` 返回 `BaseWhereStep` | 去掉「步骤顺序自由化」的中间接口，避免 `groupBy` 之后还能 `.where()` 的非法顺序 |
| `Whereable` | `where(...)` 返回顶层 `PathOperator` / `StringOperator` | 算子接口拆分后类型名随之变化 |
| `LockStep` | `lock(LockModeType)` abstract → default；新增抽象 `lock(api.LockMode)` | 保留 3 处存量调用点，同时让 `core` / `core.spec` 不接触 jakarta |
| `Path` / `NumberPath` / `StringPath` | 不再继承 `PathRef` / `PathRef.NumberRef` / `PathRef.StringRef` | 继承会让后端的 `where(PathRef)` 把 api 值对象当 lambda 解析（`writeReplace()` 抛 `NoSuchMethodException`） |

**4. japicmp 报告口径**

`-Papi-compat -Djapicmp.oldVersion=2.2.3` 使用 `breakBuildBasedOnSemanticVersioning=true`：主版本升级内的二进制破坏不使构建失败，但报告仍会生成（`nextentity-api/target/japicmp/japicmp.{md,xml}`，254 KB）。原始报告统计为「移除 44 类 / 新增 14 类 / 修改 18 类」；上表按**功能**归组，因此同一处破坏不会重复计数（原始 xml 里「返回类型所属接口被改名」会同时出现 `METHOD_REMOVED` + `METHOD_MODIFIED`，属工具口径）。

**5. 与 2.2.3 的有意差异（不影响存量调用点）**

| 差异 | 说明 |
|---|---|
| `fetch(...)` 无对应能力 | 只影响 SQL 形状/性能，不影响结果集；point-mall 0 调用点 |
| `@Version` 支持 `Integer` / `Long` | 2.2.3 只支持 `Integer`；point-mall 版本列全为 `integer` |
| 分组计数需投影非空列 | SQL 的 `count(列)` 不数 NULL，需要「组内实体行数」时应投影主键等非空列（point-mall 6 处分组查询均如此） |
| `selectDistinct` + `count/slice` 显式拒绝 | 当前 0 调用点；`JpaExecutor.count` 抛 `UnsupportedOperationException` 而不是静默返回错的总数。将来出现调用点时修法在 `QuerySpec.forCount()`（去重计数形状） |
