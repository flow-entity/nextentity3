# 2.2.3 用例移植报告

## 结果

| 项 | 2.2.3 | 第一轮移植 | 第二轮回填 | 第三轮回填（当前） |
|---|---|---|---|---|
| `integration` 用例注解数 | 1,055 | 603 | 727 | **954** |
| 单环境执行数 | — | 593 | 727 | **954** |
| 文件数 | 114（含夹具/环境/DTO） | 83 | 85 | **99** |
| 未移植注解数 | — | 452 | 328 | **101** |

环境覆盖：`h2`（默认，随全量构建跑）与 `mysql`（真 MySQL 8.0.18）**各 954 条全绿**，合计 1,908 次执行。

> 1,055 是上游 `integration` 目录（含 `fast/` 与 `config/DatabaseInitializerTest`）的实际注解数，
> 旧文档的 1,063 是历史口径（把已移除的辅助类也算进去了），本轮起以 1,055 为准。

> 第二轮的口径：`7b8a71aa` 之后 api 补齐了第一梯队 10 项能力（`not()`、`min`/`avg`、`having`、
> 多列 `groupBy`、`Tuple3~6` + `ProjectionMappers`、数值算术、列对列比较、`notBetween`、`like`、`distinct()`），
> 第一轮按「能力缺口就删」处理掉的用例里有一批重新可移植。回填 **+134 条**，逐条依据见各
> `NOT-PORTED-*.md` 顶部的「本轮回填」小节。

> 第三轮的口径：`b9cc50c` 恢复了 2.2.3 的能力面（字符串算子/函数、`Slice` 窗口访问器、DTO 投影、
> `fetch`、条件更新/删除、分组计数），前两轮据此判定的删除依据大面积过期。本轮把 1,055 条逐条重核，
> **回填 +227 条**（727 → 954），未新增删除；仍缺的 101 条逐条记档，批次内见
> `NOT-PORTED-round3-port-{a,b,c,d,e}.md`，批次外（4 个整类不建、四处旧结论反转、唯一形态差异）见
> [`NOT-PORTED-round3-lead.md`](NOT-PORTED-round3-lead.md)。

## 保留原则

**断言不动，只改调用形态。** 期望值、边界、异常类型一处未改；改的只有：怎么拿到查询入口
（`EntityTemplateFactory` → `EntityOperations`，再统一成 `context.queryXxx()`）、算子叫什么
（`like` → `likeIfNotNull`、`list(offset,limit)` → `slice(offset,limit).data()`）、锁模式枚举
（`LockModeType` → `LockMode`）、谓词组合（`Predicates.and(a,b)` → `a.and(b)`）、多参数 `orderBy` →
累积链 `orderBy(a).asc().orderBy(b).desc()`、`Path` → `EntityPath` 取嵌套列、`fetch(...)` 放到
`select(...)` 之前（3.x 的 `FetchStep` 只能在 select 前调用），以及条件改删在调用点包事务（见「裁决 5」）。

**能力缺口就删，不造映射。** 3.x 仍然没有的能力对应的用例一律删除：接口/抽象类投影；
7~10 列投影与 `select(Collection)`/`select(List)`；子查询（`toSubQuery`/`asSubQuery`）；
`orderBy(Collection)`；配置面（`JdbcConfig`/`LoggingConfig`/`SqlLogger`）；两条字符串谓词的 `or` 组合；
`likeIfNotEmpty`；算术的字面量/表达式右值、`*IfNotNull` 变体与「聚合套算术」；注解投影
（`@Join`/`@EntityPath`）；`EntityRoot.literal`。逐条依据见 `NOT-PORTED-round3-port-{a..e}.md` 与
`NOT-PORTED-round3-lead.md`，每条都指到 api 源码的 `文件:行`。

两处**刻意不采纳**的等价替换，避免把「等价前提」藏进用例：

- 7 列及以上 / `select(Collection)` 可用自定义 `ProjectionMapper` 透传表达，但结果类型会从 `TupleN`
  降为 `Tuple`、断言里的 `getN()` 要改成 `get(N)`，属改写断言形态；
- 接口投影可用 `project(...)` + 手工 `new` 实现类表达，但被测类型会从「接口」变成「具体类」，
  且每个投影方法都要手工接线，断言随之变质——比删除更容易给人「覆盖了」的错觉。

这条纪律的意义在于：移植语料**不能**反过来要求库把删掉的 API 加回来。若把剩下的用例都"想办法"凑绿，
等于用上一个版本的 API 形状锁死这一版的设计。

## 裁决（跑出来才发现的语义差异）

### 1. 删除不存在的实体 —— 用例移除

`CrudOperationsIntegrationTest.shouldHandleDeleteNonExistent`。

2.2.3 断言 `delete(entity, descriptor)` 对不存在的实参抛 `RuntimeException`，**而它自己的注释写的是
"should not throw exception"**——断言与注释自相矛盾。3.x 的写入接缝按调用点裁掉了 `delete`
（删除由消费方的 Spring Data 承担），这里没有被测对象；把断言指向测试装置里的 `delete` 辅助方法，
证明的只是辅助方法自己。**结论：删除该用例**，不留空壳。

### 2. 负 offset —— 改为断言 3.x 契约

`EdgeCasesIntegrationTest.shouldHandleNegativeOffset`。

2.2.3 只断言「不抛异常、返回非 null」，等于把未定义行为固化成期望（注释自己都写 "may treat as 0 or return all"）。
3.x 在 `api/SliceStep.java` 明确 `@throws IllegalArgumentException offset 或 limit 为负数`——负偏移被静默
当成 0 时，调用方会拿到一页错位的数据却毫无察觉。**结论：断言改为 `IllegalArgumentException`**，
这比原断言更强。

### 3. 锁模式枚举的等价映射 —— 真库已确认

2.2.3 的 `LockMode.READ` / `WRITE` / `NONE` 在 3.x 无同名常量，按 JPA 2.0 的等价关系映射为
`OPTIMISTIC` / `OPTIMISTIC_FORCE_INCREMENT` / 不加锁。H2 与 MySQL 上 `LockModeIntegrationTest`
19 条、`CollectorLockModeMethodsIntegrationTest` 24 条全绿，SQL 与行数一致，**映射成立**。

### 4. `fetch(...)` —— 第三轮已恢复能力，三类用例全部移植

第二轮写的是「3.x 没有 `fetch`，18 条改为直接读关联、依赖同一持久化上下文内的惰性加载」。这个替代
在断言上与上游等价，但它证明的是「Hibernate 会话没提前 clear」，不是「查询按需预取」——`b9cc50c`
恢复 `FetchStep` 后，这几条按原形态移植回来：`FetchOperationsIntegrationTest`（18 条）、
`FetchStepMultipleParametersIntegrationTest`（6 条）、`DeepFetchTwoLevelIntegrationTest`（7 条），
形态是 `query().fetch(Path/PathRef…).where(…)`，**`fetch` 必须在 `select` 之前**（`api/FetchStep.java:12-13`）。
上一轮那条「会话内惰性加载」的证据随之作废：它测的对象不是 `fetch`。

### 5. 条件改删 —— 事务包在调用点，不进装置

3.x 的条件更新/删除入口（`UpdateSetStep`/`UpdateWhereStep`/`DeleteWhereStep`）在 `execute()` 时
执行的是 `executeUpdate`，没有事务就会 `TransactionRequiredException`（`jpa/exec/ConditionalWrite.java:76`）。
2.2.3 由 `TransactionUpdateExecutor` 在**执行器层**兜事务；本模块的等价做法是在**调用点**包
`context.doInTransaction(() -> operations(X.class).update()…execute())`，29 条条件改删用例统一这个写法。

为什么不塞进 `LegacyHarness` 自动包：`UpdateWhereStep extends Whereable<T, UpdateWhereStep<T>>`，
而 `Whereable.where(PathRef)` / `where(StringPath)` 返回 `PathOperator` / `StringOperator`，其算子
返回 core 自己的步骤实例、**会绕过任何装饰器**——自动包就得把约 50 个算子方法全部包装一遍；
而整方法级事务会与 `insert` 自身的 `inTransaction` 二次 begin 冲突。这是本模块**唯一一处**
「调用形态需要额外包一层」的差异，装置注释（`LegacyHarness.java` 条件改删一段）同步记了这段理由。

## 已知的断言减少 —— 第三轮已清零

第二轮记录过两处断言缺口（都被判为「能力面缺失」）：

| 位置 | 当时减少的内容 | 当时的依据 |
|---|---|---|
| `SlicePaginationIntegrationTest`（3 条）、`OrderOperatorDefaultMethodsIntegrationTest`（1 条）、`StreamQueryIntegrationTest`（1 条） | 读 `slice.offset()` / `slice.limit()` 的断言 | `api/model/Slice.java` 只有 `data()` / `total()` / `to()` |
| `LockModeIntegrationTest` 等 5 个方法 | 锁 + offset 分页的用例 | `lock(...)` 返回终止态 `Collector`，与 `slice()` 互斥 |

这两条依据在第三轮都被 api 源码推翻，**断言已全部回填**：

- `Slice` 有了 `default int offset()` / `limit()`（`api/model/Slice.java:30-39`，实现是
  `core/builder/SliceResult.java:19` 的 record 组件）；4 条断言按上游原样写回。
- 锁 + offset 分页由 `api/Collector.java:38-59` 的 `default List<T> list(int offset, int limit)` 承担
  （javadoc 明说这条重载就是为 `LockStep#lock` 返回 `Collector` 的场景加的），5 条用例回填。

机械核对（`assertThat…;` 序列与上游逐条差集）现在只剩一处差异，是第三轮**唯一**的加强：
`EdgeCasesIntegrationTest.shouldHandleNegativeOffset` 由「断言非 null」（上一轮）改为断言
`IllegalArgumentException`（见裁决 2）。

## 第二轮回填的用例（+134 条）

| 能力 | 文件 | 条数 |
|---|---|---|
| `having()` | `HavingClauseIntegrationTest`（新，整类） | 19 |
| 多列 `groupBy` + `Tuple3`/`ProjectionMappers` | `GroupByStepMultipleParametersIntegrationTest`（新，整类） | 6 |
| `min()` / `avg()` / 多列聚合 | `AggregateFunctionsIntegrationTest`、`SubqueryIntegrationTest`、`ErrorHandlingIntegrationTest` | 11 |
| `Tuple3~6` + 多列 `distinct()` | `SelectApiIntegrationTest`、`EntityQueryIntegrationTest`、`TuplesIntegrationTest` | 35 |
| 数值算术（`add`/`sub`/`mul`/`div`/`mod`） | `NumericOperationsIntegrationTest`、`ExpressionBuilderDefaultMethodsIntegrationTest` | 28 |
| `not()` 与链式取反 | `AndOrPredicateIntegrationTest`、`ComplexPredicateIntegrationTest` | 14 |
| `notBetween()` / 多参数 `orderBy` | `SimpleExpressionDefaultMethodsIntegrationTest`、`OrderByStepMultipleParametersIntegrationTest`、`SimpleExpressionSortOrderIntegrationTest` | 18 |
| 其余（`having` 分组表达式、嵌套路径 `distinct` 投影） | `WhereImplIntegrationTest`、`NestedPathIntegrationTest` | 5 |

## 第三轮回填的用例（+227 条）

起于 `b9cc50c` 恢复能力面；口径是「把 1,055 条逐条重核」，不是「把删掉的都加回来」——每条仍按
「上有断言 + 下有对位能力」判，缺对位的照样删并记档（101 条）。

| 批次 | 能力 | 文件 | 条数 |
|---|---|---|---|
| a | 字符串算子全族（`startsWith`/`endsWith`/`contains`/`not*` 及 `IfNotNull`/`IfNotEmpty` 变体） | `StringExpressionDefaultMethodsIntegrationTest` +55、`StringOperationsIntegrationTest` +31 | 86 |
| b | 字符串算子默认方法、`substring`/`length`、And/Or 谓词、`where(StringPath)` 链 | `StringOperatorDefaultMethodsIntegrationTest` +29、`ExpressionBuilderDefaultMethodsIntegrationTest` +2、`AndOrPredicateIntegrationTest` +3、`WhereImplIntegrationTest` +1 | 35 |
| c | `fetch`（单列/多列/两跳）与嵌入扁平投影 | 4 个新整类：`FetchOperationsIntegrationTest` 18、`DeepFetchTwoLevelIntegrationTest` 7、`FetchStepMultipleParametersIntegrationTest` 6、`EmbeddedProjectionIntegrationTest` 12 | 43 |
| d | DTO 投影（JavaBean/record、`distinct`、边界）与整实体投影 | `ProjectionQueryIntegrationTest` 22（新整类）、`SelectApiIntegrationTest` 3 | 25 |
| e | 条件更新/删除、`having` 后接终止符、锁 + offset 分页、嵌套路径 `distinct` 投影 | `ConditionalUpdateDeleteTest` 19、`ConditionalUpdateWithJoinTest` 7（均新整类）、`HavingClauseIntegrationTest` 3、`LockModeIntegrationTest` 1、`CollectorLockModeMethodsIntegrationTest` 4、`NestedPathIntegrationTest` 2、`EntityQueryIntegrationTest` 1、`EmbeddedCrudIntegrationTest` 1 | 38 |

合计 +227（727 → 954），与注解总账一致；本轮**没有从已有文件里再删任何用例**——剩下的 101 条缺口
都是前两轮已删项，本轮逐条复核并给出「仍然缺」的当前依据。

> **后续（第三轮之后，`select(Class)` 补上 `@Embedded` 同名叶子回找）**：`EmbeddedProjectionIntegrationTest`
> 的 12 条保留用例里 11 条已改回上游 `.select(<Dto>.class)` 形态（断言逐行未动），只剩跨层「DTO 字段名 ≠
> 叶子名」那条继续用 `project(PERSON_CROSS_LAYER_BASIC)`；用例总数不变（仍 954）。知识面更新见
> [`NOT-PORTED-round3-port-c.md`](NOT-PORTED-round3-port-c.md) 顶部「后续更新」。

## 这批语料抓到的生产代码缺陷（4 条）

移植用例的价值就在这里：2.2.3 的期望值 + 3.x 的实现，跑红的地方就是缺陷。以下四条都已在
`core` 修复并由 `nextentity-testing` 的一致性基类钉住（H2 与真 MySQL 双通道）。

| # | 缺陷 | 症状 | 修复 |
|---|---|---|---|
| 1 | **双重否定只生效一次** | `not().not()` 结果等于 `not()`（`shouldHandleChainedNotOperations` 红，H2 与 MySQL 同错）。纯 JPA 对照确认是 Hibernate `Predicate.not()` 对「最内层是比较谓词」的嵌套取反幂等，而 core 把 `NOT(NOT(x))` 原样下推 | `OpInterpreter#negate` 消去双重否定（`NOT(NOT(x))` → `x`，三重 → 一层 `NOT`）；`NegationConsistencyTest` 钉住 |
| 2 | **去重投影 + 分页在 MySQL 上必炸** | `distinct().select(col).list(n)/first()/slice()` 生成 `SELECT DISTINCT col ... ORDER BY 主键`，MySQL 报 `Expression #1 of ORDER BY clause is not in SELECT list`；H2 容忍 → H2 单通道全绿 | `QueryBuilder#spec(Window)` 在去重投影上不再自动补主键排序；`DistinctConsistencyTest#distinctSurvivesPaginationWithoutExplicitOrder` 钉住 |
| 3 | **`slice()` 遇到 NULL 投影值 NPE** | `select(可空列).slice(0,n)` 直接抛 `NullPointerException`（`SliceResult` 用 `List.copyOf`，而 `List.copyOf` 拒绝 null 元素）。2.2.3 的 `SliceImpl` 是纯 record，允许 null | `SliceResult` 改用 `Stream.toList()`（不可变且允许 null）；`SliceResultTest` 与一致性用例钉住 |
| 4 | **去重 + 可空列的 `total()` 比实际行数少 1** | `COUNT(DISTINCT 可空列)` 不计 `NULL` 组，而 `SELECT DISTINCT` 把 `NULL` 算一组；分页元数据因此少一行 | 不修语义（要修得加第二条查询），在 `Slice#total` 与一致性用例里写明口径 |

另有三条**已确认但不修**的形态问题，记录备查：

- `EntityQuery.groupBy(...)` / `having(...)` 编译合法、运行必然 SQL 错误（实体投影 + 分组）；接口形状
  （`EntityQuery extends BaseWhereStep<T,T>`）留了这个洞，要堵得在 builder 里显式拒绝。
- 算术右值走 `builder.literal` 内联进 SQL，`salary * 1.1` 在 H2（DECIMAL）与 MySQL（DOUBLE）上
  结果差 1 ulp（`93500.0` vs `93500.00000000001`），且无法复用执行计划；与条件走参数绑定的口径不对称。
- `like` / `contains` 的大小写语义由方言决定：H2（`MODE=MySQL`）区分大小写、真 MySQL 默认排序规则不区分。
  H2 通道因此**发现不了**大小写相关的缺陷，跨方言语义只能靠 MySQL 通道兜。

第三轮（954 条 × H2 + MySQL 双通道全绿）**没有抓到新的生产缺陷**，但留了一条待裁决：

- `SelectApiIntegrationTest.shouldSelectDistinctWithProjectionType`：2.2.3 的 `selectDistinct(Department.class)`
  （整实体去重投影）在 3.x 抛 `UnsupportedOperationException`（实体投影禁止 distinct），用例降级为
  `select(Department.class)`，`hasSize(5)` 等断言原样保留、两个环境都能过，但「distinct 语义弱化」。
  这是「能跑通」与「测的是同一件事」之间的空隙，记在 `NOT-PORTED-round3-port-d.md` 待裁决，
  若将来给实体投影开 distinct（或给出拒绝理由）再回填原形态。

## 复现

```bash
./mvnw -pl nextentity-tests-legacy test                    # H2
./mvnw -pl nextentity-tests-legacy test -Ddbc=h2,mysql     # H2 + MySQL
```
