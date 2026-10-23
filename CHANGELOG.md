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
- `nextentity-tests-legacy`：把 2.2.3 的 `integration` 用例移植过来做语义回归，1,063 条注解 → 保留 603 条（单环境执行 593 条），H2 与真 MySQL 8.0 各全绿。装置用真实 Hibernate 而非 Spring，建表用 2.2.3 自带 DDL + 驼峰转下划线命名策略，交付上下文前复位以保证用例隔离。460 条按能力缺口删除，逐条依据（含 api 源码 `文件:行`）与 4 项裁决见模块 `porting/REPORT.md`。
- **第一梯队能力（10 项）**，`api` 侧：列对列比较 `ColumnComparison`（`eq`/`ne`/`ge`/`gt`/`le`/`lt` 接 `Expression`）、表达式算术 `NumberArithmetic` + `NumberValue`（`add`/`sub`/`mul`/`div`/`mod`，可链式）、逻辑组合 `LogicalOps`（`not()` + n 元 `and`/`or(Collection)`）、聚合结果 `AggregateValue`（可比较/可区间、**不接聚合**）、`HavingStep`（`having(Expression)`）、`model.Tuple`（3 列及以上投影，`get(int)`/`size()`；`Tuple2` 也实现它）、`Between` 增 `between(Expression,Expression)` 与 `notBetween(U,U)`、`StringOps.like(String)`（模式自带 `%`）、`SelectStep` 增 3 列及以上投影重载、`GroupByStep` 增多列 `groupBy(PathRef...)`、`Aggregates` 增 `min()`/`avg()`（`avg` 返回 `Double`）、`NumberPath.sum()` 与全部聚合方法改为返回 `AggregateValue`、`Operation` 由 19 个常量扩到 27 个（新增 `NOT`/`MIN`/`AVG`/`ADD`/`SUB`/`MUL`/`DIV`/`MOD`）、`Op` 增 `subject()`（左侧表达式，`null` 表示主语就是 `refs()` 的路径）。
- **第一梯队能力（10 项）**，`core` / `jpa` 侧：`core.spec` 新增值表达式层 `Operand`（`Literal` / `AttributePath` / `AggregateOperand` / `ArithmeticOperand` + `BinaryOperator`）与 `ComputedProjection`，`Comparison`/`Between` 的字段升级为 `Operand`，`AggregateFunction` 增 `MIN`/`AVG`，`QuerySpec` 增 `having`（`forCount`/`forExists` 保留它）；`core.builder` 的 `OpInterpreter` 负责 `NOT`/`AND`/`OR` 的语义（`and(空)`/`or(空)` 都保留接收者（`a ∧ 空 = a`、`a ∨ 空 = a`）、`or` 混入短路条件=恒真）与 3 列以上 `TupleValue`；`jpa.compile.JpaOperands` 是 `Operand → Criteria Expression` 的**唯一**渲染点（聚合与算术的 `switch` 各只有一份），`JpaPredicates` 支持表达式右值，`JpaQueryBuilder` 渲染 `HAVING`。
- **显式投影映射器**，`api` 侧：新增 `ProjectionMapper<T, R>`（`expressions()` 声明列集、`map(Tuple)` 造结果）与 `SelectStep.project(ProjectionMapper)`。列集由映射器对象声明，结果直接是调用方类型 `R`，可在运行期组装列集合；**映射**零反射零注解（属性名解析仍靠方法引用的 `SerializedLambda`，与既有 `select(...)` 一致）。单列也以长度为 1 的 `Tuple` 交给 `map`（`Tuple#size()` 下限放宽到 1）。
- **显式投影映射器**，`core` 侧：新增 `builder.ValueProjections`（api 表达式 → `core.spec.Projection` 的唯一转换点，`select(...)` / `project(...)` 都经过它；映射器列集的整批校验也在这里，违规在 `project()` 阶段抛 `IllegalArgumentException` 且消息带列下标）与 `builder.RowAssembler`（后端原始行 → 结果对象：`Tuple` 装配与映射器映射），`QueryBuilder` 不再自己装配行。
- **去重修饰符** `api.DistinctStep#distinct()`（由 `SelectStep` 继承）：把「是否去重」从「投影形态」里正交出来，单列 / `Tuple2` / 3 列起 `Tuple` / `ProjectionMapper` 四种投影一视同仁。它返回 `SelectStep` 而非终止符，因此「声明了去重却没投影」在编译期不可写，`SELECT DISTINCT 实体` 也写不出来。多列去重是新能力（原先只有单列 `selectDistinct`）；多列去重的 `count()` 抛 `UnsupportedOperationException`——`COUNT(DISTINCT a, b)` 在 MySQL 与 PostgreSQL 形态不同，只数第一列会静默给出错数。
- `model.Tuple#size()` 契约从「恒大于等于 2」放宽到「恒大于等于 1」：映射器路径单列也产出 `Tuple`，`Tuple` 类注释同步去掉「3 列及以上」的限定。
- `select(Expression)` / 2 列 / 3 列以上重载现在也接受 `PathRef`（裸方法引用）：它们与 `select(PathRef)` / `project(...)` 共用 `builder.ValueProjections.of(...)` 这条「api 表达式 → 投影项」转换，因此在静态类型被拓宽为 `Expression` 的 `PathRef` 上也能投影，不再抛 `IllegalArgumentException`（原先只有 `select(PathRef)` 重载能接，是既有的洞）。

### Changed

- `count()` 用 `count(TRUE 字面量)`、`exists()` 用 `select TRUE` + `limit 1`，与 2.2.3 的 `SELECT_COUNT_ANY` / `SELECT_ANY` 逐行对应（对照表见 `tmp/contract/JPA-SEMANTICS.md` §3）。
- 嵌套关联路径（`EntityPath.of(A::getB).get(C::getD)`）固定走 **显式 LEFT JOIN**，与 2.2.3 相同；同前缀路径只 join 一次。
- `single()` 命中多行抛 `IllegalStateException("found more than one result, expected one")`（2.2.3 的 api Javadoc 写的就是 `IllegalStateException`，虽然其实现抛 `NextEntityException`；point-mall 无 `catch` 依赖）。
- 写入失败改抛标准 JPA 异常（`OptimisticLockException` / `EntityNotFoundException`），不再抛 2.2.3 的 `NextEntityException`。
- `lock(...)` 只接受自研 `LockMode`；2.2.3 的 `lock(jakarta.persistence.LockModeType)` 重载**已删除**，`api` 回归零三方依赖（消费方 3 处调用点改用 `LockMode.PESSIMISTIC_WRITE`，各 1 行）。
- 聚合方法（`count` / `countDistinct` / `max` / `min` / `avg` / `sum`）统一返回 `AggregateValue`（仍是 `Expression`）：`max().max()`、`count().count()` 这类"聚合再接聚合"的写法现在**编译期就不可写**（原先编译通过、运行期抛 `UnsupportedOperationException`）。
- 聚合 / 分组规格上的 `count()` 仍抛 `UnsupportedOperationException`（SQL 需子查询计数，3.x 未实现）；`exists()` 不受影响，语义是"有行"。`having(...)` 不校验"必须伴随 `groupBy`"——SQL 允许不带 `GROUP BY` 的 `HAVING`（全表聚合），由调用方负责。
- `a = b` 这类列对列比较按**数据库排序规则**执行：字符串列的大小写敏感性、尾随空格处理由方言决定（H2 与 MySQL 可能不同），不要拿它做跨库一致的业务语义。
- 单列 `distinct()` 的 `count()` / `.slice(...)` 现在真正可用：`JpaExecutor.count` 走 `count(distinct 投影列)`，不再显式拒绝（原先抛 `UnsupportedOperationException`）。
- `Op` 改为 `sealed`：`Op` 是「值 → AST」的线格式，语义由后端按 `operation()` 穷尽解释，不允许外部再实现出后端无法解释的运算。
- `Path` / `NumberPath` / `StringPath` 不再继承 `PathRef`：`PathRef` 只表示用户写的方法引用，路径值由新增的 `where(Path)` / `where(StringPath)` / `orderBy(Path)` 承载。**为什么必须破**：继承会让后端的 `where(PathRef)` 把 api 值对象当 lambda 解析（`writeReplace()` 抛 `NoSuchMethodException`）。
- `SelectStep` 增抽象方法 `project(ProjectionMapper)`：**接口的外部实现者需要补实现**（仓库内唯一实现是 `core.builder.QueryBuilder`）；调用方不受影响，既有 `select(...)` 重载的签名与语义未变。
- 删掉 4 处**死类型参数**：`SelectStep.select(3 列及以上)` 的 `<A, B, C>`（返回的是按位置取值的 `Tuple`，三列类型不进返回类型），以及 `Conditions` 两个私有辅助方法 `operands(Collection)` / `checked(Collection)` 的 `<T>`（只在唯一那个参数里出现一次，不约束任何第二个参数）。判断标准：类型参数至少出现两次、或用进返回类型才有意义。它们是已被否决的 `Tuple3<A,B,C>` 方案的残留。擦除后方法描述符不变，**二进制兼容**、调用点零改动。

### Fixed

- `nextentity-spring` 补上 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：`@AutoConfiguration` 只是标记，Spring Boot 2.7+ 靠该文件发现配置类。此前 jar 里没有这个文件，消费方引入依赖后**拿不到任何 bean**（`docs/USAGE.md` §12 的「引入即自动配置」并不成立）。原用例用 `AutoConfigurations.of(...)` 显式注册，绕过了注册表，所以一直没暴露；新增 `AutoConfigurationImportsTest` 直接读 classpath 上那个固定路径，把「能否被发现」钉住。
- `Path.of(...).in(...)` / `.notIn(...)` 的变长参数改用 `Arrays.asList`：原先用 `List.of(values)`，遇到 `null` 元素抛 `NullPointerException`，与 `Membership` 声明的「元素允许为 `null`」冲突，也与 `where(ref).in(...)`（走 `Arrays.asList`）行为不一致。同一算子两条入口现在一致，`OperatorMappingTest.membershipVarargsAcceptNullElement` 覆盖。
- 修正失效的 Javadoc / `@see` 引用：`api.DistinctStep` 指向 `core.spec.EntitySelection`（`api` 不依赖 `core`，链接永远解析不到，且暴露实现细节）；`jpa.compile.JpaOperands` 指向 private 的 `JpaPredicates#comparison`。
- 删除死代码：`Conditions.Leaf` 从未被调用的三参构造器、`PathValues.StringRoot`（无静态工厂可达，`StringPath` 没有 `of(...)`）、`Conditions.arithmetic` 恒为 `null` 的 `leftSubject` 形参。`Leaf` 与 `SimpleEntityOperationsFactory.Operations` 改为 record（组件名与 `Op` 的读取方法一一对应，不再手写 getter）。
- `JpaEntityAttribute` 构造器对 `entityType` 做 `requireNonNull`：错误消息里 `entityType.getName()` 在入参为 `null` 时会先抛 NPE，把「找不到字段」的真实原因盖掉。
- `nextentity-tests-legacy` 清理未使用 import / 未使用变量 / 未使用 logger 与指向已删除符号的 `@see`（该模块仍按「移植语料」保持与 2.2.3 同排版，仅清理无意义的死符号）。

### Removed

- 相对 nextentity 2.x 不再提供：JDBC 第二引擎与方言、自研元模型、实体事件总线、CGLIB 仓储代理、`Tuple5`~`Tuple10`。
- `api` 包 44 个类型整体移除（完整清单与逐条理由见下方 Migration 第 1 节）。
- `core.expression.*`、`core.meta.impl.DefaultMetamodel`、`core.util.Iterators`、`core.EntityTemplateFactory`、`spring.EntityFactoryBuilder` 等 2.x 内部符号不再提供。
- `SelectStep.selectDistinct(PathRef)`：由 `distinct().select(ref)` 取代。**为什么必须破**：两者语义完全相同，留着就是两个入口；而补「多列 `selectDistinct` 重载」会让「投影形态 × 是否去重」变成笛卡尔积（4 × 2 = 8 个入口），并把 `SelectStep` 从 6 个方法推到 8 个、撞穿 `API_INTERFACES_STAY_NARROW` 的 ≤6。迁移是机械替换：`x.selectDistinct(f)` → `x.distinct().select(f)`，仓库内 5 处移植用例与 point-mall 2 个调用点（`DigitalCardRepository:85`、`OrderImportRecordRepository:71`）均已替换。
- `core.TransactionOperations`：零实现、零调用的空接缝已删除（事务边界由调用方 `@Transactional` 持有）。需要第二后端时随它一起引入，不提前摆空接口。

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
| `FetchStep` / `SubQueryBuilder` | `fetch(...)` / `toSubQuery()` 全库 0 调用点；`having(...)` 原先 0 调用点，第一梯队已按新契约重建为 `api.HavingStep`（见 Added），与 2.2.3 的"分组后步骤"同名不同义 |
| `UpdateSetStep` / `UpdateWhereStep` / `DeleteWhereStep` | `update().set().where()` / `delete().where()` 0 调用点；写入走仓储级 `insert/update/insertAll/updateAll` |
| `WhereStep` | 仅用于承载 8 个 `where` 重载的中间接口；3.x 由 `BaseWhereStep` + `Whereable` 承担 |
| `BooleanPath` / `PathRef.BooleanRef` | 0 引用；布尔属性走 `where(..).eqIfNotNull(b)` |
| `PathRef.BigDecimalRef`/`ByteRef`/`DoubleRef`/`FloatRef`/`IntegerRef`/`LongRef`/`ShortRef` / `PathRef.EntityPathRef` | 0 引用；数值统一由 `PathRef.NumberRef` 承载，关联统一走 `EntityPath` |
| `model.Tuple3`~`Tuple10` | 不再提供具名元组（`get2()`…`get9()`）；3 列及以上统一返回 `model.Tuple`（`get(int)`/`size()`），`Tuple` 因此按新契约重建而非删除 |
| `model.Order` / `SortOrder` | 0 引用；排序只保留 `orderBy(PathRef\|Path) → asc()/desc()` |

**2. 方法移除 · 收窄（同类合并）**

| 类型 | 移除/收窄 | 为什么必须破 |
|---|---|---|
| `Collector` | `exists(int)` / `list(int,int)` / `toSubQuery()` 移除；`slice(int,int)` 迁到 `SliceStep` | 前三个 0 调用点；`Collector` 已顶到 6 个方法上限 |
| `Collector` | `first()` / `list(int)` 由 default 变 abstract | 2.2.3 的 default 实现 import 了 `core.expression.SliceImpl` + `core.util.ImmutableList`（api→core 反向依赖） |
| `model.Slice` | `offset()` / `limit()` / `map(Fn)` 移除；`to(Fn)` default 变 abstract | 前三个 0 调用点（`offset(` 全是 `Pageable.offset()`）；`to()` 同理依赖 core 实现 |
| `model.Tuple2` | `get0()` / `get1()` default 变 abstract | 2.2.3 的 default 转发到已删除的 `Tuple.get(int)` |
| `SelectStep` | 44 个投影重载 → 5 个：`select(PathRef)`、`select(Expression)`、`select(Expression,Expression)`、`select(Expression,Expression,Expression,Expression...)`（→`Tuple`）、`project(ProjectionMapper)` | 其余均 0 调用点；3 列及以上与映射器是新能力（见 Added） |
| `Whereable` | 8 个 `where` → 5 个：移除 `where(NumberRef)`、`where(NumberPath)`、`where(EntityPathRef)` | 三个重载 0 调用点；保留的 5 个全部有调用点 |
| `GroupByStep` / `OrderByStep` | 变长与 `Collection` 重载全部移除 | 0 调用点（`groupBy` 6 处、`orderBy` 138 处全是单参形态） |
| `OrderOperator` | 移除 14 个终止符 default 转发 | 虚空防御：`OrderOperator` 只该给方向，终止符由 `BaseWhereStep` 继承 `Collector` 提供 |
| `NumberExpression` | 整类删除（原先只承载 `sum()` 与 `max()` 的收窄返回类型）；`sum()` 移到 `NumberPath`，聚合方法统一返回 `AggregateValue` | 它存在的唯一效果是让"聚合再接聚合"能编译通过、运行期抛异常；删掉后该写法在编译期即被拒绝 |
| `StringExpression` | 移除 `notLike/notContains/notStartsWith/notEndsWith`、`startsWith/endsWith`、`lower/upper/substring/trim/length` 及其 `*If*` 变体 | 0 调用点（point-mall 里这些全是 `java.lang.String` 的方法） |
| `Predicate` | 移除 `ofTrue()` / `ofFalse()` 与变长 `and`/`or`（`not()` 与 n 元 `and`/`or` 已按新契约重建为 `LogicalOps`，见 Added） | 0 调用点；短路由「不实现 `Op` 的 `Predicate`」表达，恒假由后端从 `IN` + 空参推导 |
| `EntityPath` / `Path` / `NumberPath` / `StringPath` / `Expression` | 移除 `of(String)`、`of(BooleanRef)`、`of(EntityPathRef)`、`apply(T)`、`get(NumberRef)` 等类型不安全或 0 调用的重载 | 0 调用点；`of(String)` 是 DESIGN §4 明令移除的类型不安全重载 |

**3. 形状变化（只改返回类型/修饰符，调用点可解析）**

| 类型 | 变化 | 为什么必须破 |
|---|---|---|
| `SelectStep` / `GroupByStep` | `select(...)` / `groupBy(...)` 返回 `BaseWhereStep` | `WhereStep` 删除、`HavingStep` 按新契约重建（`having` 入口）后，中间步骤只剩 `BaseWhereStep`；调用点只链 `where/orderBy/slice/list` |
| `OrderOperator` | `asc()` / `desc()` 返回 `BaseWhereStep` | 去掉「步骤顺序自由化」的中间接口，避免 `groupBy` 之后还能 `.where()` 的非法顺序 |
| `Whereable` | `where(...)` 返回顶层 `PathOperator` / `StringOperator` | 算子接口拆分后类型名随之变化 |
| `LockStep` | 只保留 `lock(api.LockMode)`；`lock(jakarta.persistence.LockModeType)` 重载删除 | `api` 零三方依赖；3 处存量调用点改用自研枚举，各 1 行 |
| `Path` / `NumberPath` / `StringPath` | 不再继承 `PathRef` / `PathRef.NumberRef` / `PathRef.StringRef` | 继承会让后端的 `where(PathRef)` 把 api 值对象当 lambda 解析（`writeReplace()` 抛 `NoSuchMethodException`） |

**4. japicmp 报告口径**

`-Papi-compat -Djapicmp.oldVersion=2.2.3` 使用 `breakBuildBasedOnSemanticVersioning=true`：主版本升级内的二进制破坏不使构建失败，但报告仍会生成（`nextentity-api/target/japicmp/japicmp.{md,xml}`，254 KB）。原始报告统计为「移除 44 类 / 新增 14 类 / 修改 18 类」；上表按**功能**归组，因此同一处破坏不会重复计数（原始 xml 里「返回类型所属接口被改名」会同时出现 `METHOD_REMOVED` + `METHOD_MODIFIED`，属工具口径）。

**5. 与 2.2.3 的有意差异（不影响存量调用点）**

| 差异 | 说明 |
|---|---|
| `fetch(...)` 无对应能力 | 只影响 SQL 形状/性能，不影响结果集；point-mall 0 调用点 |
| `@Version` 支持 `Integer` / `Long` | 2.2.3 只支持 `Integer`；point-mall 版本列全为 `integer` |
| 分组计数需投影非空列 | SQL 的 `count(列)` 不数 NULL，需要「组内实体行数」时应投影主键等非空列（point-mall 6 处分组查询均如此） |
| `distinct()` 的 `count/slice` | 单列已实现为 `count(distinct 投影列)`；多列抛 `UnsupportedOperationException`（跨方言形态不同）。去重后行数与实体行数是两个概念，勿混用 |

