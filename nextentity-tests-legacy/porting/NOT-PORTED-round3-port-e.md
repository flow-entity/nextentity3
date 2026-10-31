# NOT-PORTED — port-e 第三轮（条件改删 / 锁+分页 / 排序集合 / 子查询 / 配置）

> **本轮回填（2026-10-31，3.2 条件写 DSL 恢复之后）**：3.2 给 `EntityOperations` 加回了
> `update()` / `delete()` 两条条件写入口（`nextentity-core/src/main/java/io/github/nextentity/core/exec/PersistExecutor.java:48`、
> `:55`），于是前几轮因「3.x 无条件更新/删除」整类删掉的两类可以按上游整类重建：
> `ConditionalUpdateDeleteTest` 19 条 + `ConditionalUpdateWithJoinTest` 7 条（两个 `@AfterEach tearDown` 一并保留）。
> 另外回填：`HavingClauseIntegrationTest` 3 条（`having` 之后 `count()`/`exists()` 有了组语义，3.1.0 起）、
> 锁+offset 分页 5 条（`api/Collector.java:38-59` 的 `list(int offset, int limit)` 就是「带锁分页」的正位）、
> 嵌套路径投影 2 条（`distinct().select(EntityPath...)`）、`EntityQuery` 的 `select(Class)` 整实体投影 1 条、
> `EmbeddedCrud` 的跨层嵌入式条件更新 1 条；`OrderOperatorDefaultMethodsIntegrationTest.shouldSliceWithOffset`
> 由 port-c 的「待裁决」转正（`Slice.offset()` 已存在）。仍然缺：`orderBy(Collection)`、`OrderOperator` 的
> 默认委托方法、`toSubQuery()`、`JdbcConfig`/`SqlLogger`/`LoggingConfig` 配置面。总账见 [`REPORT.md`](REPORT.md)。

口径：[`NOT-PORTED-port-c.md`](NOT-PORTED-port-c.md) 同款。3.x api 事实来源：
`nextentity-api/src/main/java/io/github/nextentity/api/`（下文行号均指这里）。
编译验证：`bash tmp/scripts/compile-legacy.sh D:/source/java/nextentity3/tmp/legacy/out-e` → **EXIT=0**；
直跑：`bash tmp/scripts/run-legacy-tests.sh D:/source/java/nextentity3/tmp/legacy/out-e io.github.nextentity.integration.ConditionalUpdateDeleteTest io.github.nextentity.integration.ConditionalUpdateWithJoinTest io.github.nextentity.integration.HavingClauseIntegrationTest io.github.nextentity.integration.LockModeIntegrationTest io.github.nextentity.integration.CollectorLockModeMethodsIntegrationTest io.github.nextentity.integration.CollectorExtendedMethodsIntegrationTest io.github.nextentity.integration.OrderOperatorDefaultMethodsIntegrationTest io.github.nextentity.integration.OrderOperatorUncoveredMethodsIntegrationTest io.github.nextentity.integration.SubqueryIntegrationTest io.github.nextentity.integration.NestedPathIntegrationTest io.github.nextentity.integration.EntityQueryIntegrationTest io.github.nextentity.integration.EmbeddedCrudIntegrationTest io.github.nextentity.integration.CrudOperationsIntegrationTest io.github.nextentity.integration.ConfigurationBehaviorIntegrationTest`
→ `223 tests found / 223 successful / 0 failed`（H2，无容器；含本批 14 个类的原有用例回归）。
判定：`移植` = 只改调用形态、断言与期望值未动；`删除` = 3.x 能力已删；`待裁决` = 已改完能跑，但怀疑与 2.2.3 语义不等价。

结果：保留 **38** 条（含 2 个整类 19+7），删除 **20** 条，待裁决 **0** 条。
另保留 2 个 `@AfterEach tearDown`（见「生命周期方法」）。

## 汇总

| 文件 | 保留 | 删除 | 待裁决 |
|---|---|---|---|
| ConditionalUpdateDeleteTest.java | 19 | 0 | 0 |
| ConditionalUpdateWithJoinTest.java | 7 | 0 | 0 |
| HavingClauseIntegrationTest.java | 3 | 0 | 0 |
| LockModeIntegrationTest.java | 1 | 0 | 0 |
| CollectorLockModeMethodsIntegrationTest.java | 4 | 0 | 0 |
| OrderOperatorDefaultMethodsIntegrationTest.java | 0 | 1 | 0 |
| OrderOperatorUncoveredMethodsIntegrationTest.java | 0 | 6 | 0 |
| CollectorExtendedMethodsIntegrationTest.java | 0 | 1 | 0 |
| SubqueryIntegrationTest.java | 0 | 2 | 0 |
| NestedPathIntegrationTest.java | 2 | 0 | 0 |
| EntityQueryIntegrationTest.java | 1 | 0 | 0 |
| EmbeddedCrudIntegrationTest.java | 1 | 0 | 0 |
| CrudOperationsIntegrationTest.java | 0 | 1 | 0 |
| ConfigurationBehaviorIntegrationTest.java | 0 | 9 | 0 |

`dto/` 未新增任何文件：本批回填的 2 条整类与新用例都用既有实体（`Employee` / `Department` /
`PersonWithCrossLayerEmbedded`），没有新增 DTO 需求。

## 回填明细（判「移植」的依据）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| ConditionalUpdateDeleteTest.java | 整类 19 条：shouldUpdateSingleFieldWithWhereCondition / shouldUpdateMultipleFieldsWithSingleWhereCondition / shouldUpdateWithMultipleWhereConditions / shouldUpdateAllRecordsWhenNoWhereCondition / shouldUpdateWithNumericComparisonConditions / shouldUpdateEnumField / shouldUpdateBooleanField / shouldUpdateDepartmentFields / shouldReturnZeroWhenUpdateMatchesNoRecords / shouldDeleteWithSingleWhereCondition / shouldDeleteWithMultipleWhereConditions / shouldDeleteMultipleRecordsWithInCondition / shouldDeleteWithNumericComparisonCondition / shouldDeleteWithNullCondition / shouldDeleteWithNotNullCondition / shouldReturnZeroWhenDeleteMatchesNoRecords / shouldUpdateThenDeleteInTransaction / shouldHandleConditionalUpdateAfterInsert / shouldHandleConditionalDeleteAfterInsert | 移植 | `operations(X.class).update().set(...).where(...).execute()` → int、`delete().where(...).execute()` → int（`api/UpdateSetStep.java:28`、`:52`；`api/DeleteWhereStep.java:16`；`api/Executable.java:14`；`nextentity-core/src/main/java/io/github/nextentity/core/exec/PersistExecutor.java:48`、`:55`）。2.2.3 的 `context.update(context.getEntityContext(X.class))` / `context.delete(...)` 逐处替换成上面两条入口，断言未动 |
| ConditionalUpdateWithJoinTest.java | 整类 7 条：shouldUpdateEmployeesByDepartmentName / shouldUpdateEmployeesByDepartmentLocation / shouldUpdateEmployeesWithCombinedConditions / shouldUpdateEmployeesWithNestedPathInOperator / shouldDeleteEmployeesByDepartmentName / shouldHandleUpdateWithNoMatchingNestedPath / shouldHandleDeleteWithNoMatchingNestedPath | 移植 | 同上；嵌套条件 `where(A::getX).get(Y::getZ).eq(...)` → `where(EntityPath.of(A::getX).get(Y::getZ)).eq(...)`（`api/EntityPath.java:18`、`:27`），因为 3.x 的 `Path` 本身没有 `get`。`.like("Temp Employee%")` 保留原样（`api/StringOps.java:32` 有裸 `like(String)`）。上游 `shouldUpdateEmployeesByDepartmentName` 用 2.2.3 的 `FastIntegrationTestProvider`，本模块统一改 `IntegrationTestProvider`（与 port-c/port-d 同口径） |
| HavingClauseIntegrationTest.java | shouldCombineHavingWithOffset / shouldCountWithHavingClause / shouldCheckExistenceWithHaving（3 条，新增 §9/§10） | 移植 | 3.1.0 起 `having` 之后的 `count()` 数的是**组数**、`exists()` 回答「是否有任何组」，且 `having` 之后的窗口作用在组上（`api/Collector.java:13-21`、`:44-46`）。`shouldCombineHavingWithOffset` 的 `list(offset, size)` 按本模块统一对位写成 `slice(offset, size).data()`（`api/SliceStep.java`），断言未动 |
| LockModeIntegrationTest.java | shouldLockWithOffset（1 条，新增 §8） | 移植 | 3.1.0 给 `Collector` 加了 `list(int offset, int limit)`，javadoc 明说这条重载存在就是为了 `lock(...)` 之后还能分页（`api/Collector.java:38-59`；`api/LockStep.java:9`、`:15`）。调用形态 `.lock(LockMode.PESSIMISTIC_READ).list(2, 3)`，断言原样 |
| CollectorLockModeMethodsIntegrationTest.java | shouldFirstWithOffsetAndLockModeReturnValue / shouldOffsetWithLockModeReturnResultsFromOffset / shouldOffsetWithPessimisticWriteLock / shouldOffsetWithLockModeAndWhereClause（4 条） | 移植 | 同上（`api/Collector.java:38-59`）。四条的 `list(offset, limit)` 之前都带 `.lock(...)`，走 `LockStep → Collector` 那条链路 |
| NestedPathIntegrationTest.java | shouldSelectDepartmentName_FromEmployees / shouldSelectDepartmentLocation_FromEmployees（2 条，`@Nested NestedPathSelectTests`） | 移植 | `selectDistinct(EntityPath...)` 写成 `distinct().select(EntityPath...)`：`distinct()` 是 SQL 侧 `SELECT DISTINCT`，由紧随的投影消费（`api/DistinctStep.java:6-7`、`:28`）。旧记档（`NOT-PORTED-port-a.md:54`）以「`selectDistinct` 只收 `PathRef`」为由删除，该理由已被 3.2 的 `distinct()` 取代 |
| EntityQueryIntegrationTest.java | select_WithProjectionType_ShouldReturnProjectedResults（1 条） | 移植 | 目标类型恰为实体类型时 `select(Class)` 退化为整实体查询（`api/SelectStep.java:82-83`、`:91`），断言（非空 + `allMatch(getActive)`）原样 |
| EmbeddedCrudIntegrationTest.java | shouldConditionalUpdateCrossLayerEmbedded（1 条） | 移植 | `update().set(PersonWithCrossLayerEmbedded::getName, ...).where(...).execute()`（`api/UpdateSetStep.java:28`）+ `context.operations(...).insert(...)`；嵌入字段 `address.city` 的读回断言原样 |

## 删除明细（按能力归并）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| OrderOperatorUncoveredMethodsIntegrationTest.java | shouldOrderByCollectionWithoutExplicitSortOrder / shouldOrderByCollectionWithMultiplePaths / shouldOrderByCollectionReturnOrderOperatorForChaining / shouldOrderByEmptyListOfOrdersWork / shouldChainOrderByCollectionWithLimit（5 条） | 删除 | 3.x 无 `orderBy(Collection)` 重载：`api/OrderByStep.java` 只有 `orderBy(PathRef)`（`:16`）与 `orderBy(Path)`（`:22`）两个单列重载；多列排序靠 `asc()`/`desc()` 累积链（`api/OrderOperator.java:12`、`:17`） |
| OrderOperatorUncoveredMethodsIntegrationTest.java | shouldOrderOperatorMethodsDelegateToAscCorrectly（1 条） | 删除 | 该用例比较 `orderBy(id).count()` 与 `orderBy(id).asc().count()`，依赖 2.2.3 `OrderOperator` 上继承 `Collector` 的默认方法；3.x `api/OrderOperator.java:7-18` 只有 `asc()`/`desc()`，两侧会退化成同一表达式，用例失去区分力 |
| OrderOperatorDefaultMethodsIntegrationTest.java | shouldConvertToSubqueryWithoutExplicitSortOrder（1 条） | 删除 | 3.x 无 `toSubQuery()`：`api/Collector.java` 的方法集只有 `list()`/`list(int)`/`list(int,int)`/`first()`/`single()`/`count()`/`exists()`，`api/OrderOperator.java` 也没有子查询入口 |
| CollectorExtendedMethodsIntegrationTest.java | shouldConvertToSubquery（1 条） | 删除 | 同上：`select(...).where(...).toSubQuery()` 在 `api/Collector.java` / `api/SelectStep.java` 均无对位 |
| SubqueryIntegrationTest.java | shouldCreateSubqueryFromQuery / shouldSliceSubqueryResults（2 条） | 删除 | 同上；子查询要作为条件/数据源嵌入，3.x 既无 `toSubQuery()` 也无 `asSubQuery()`，`where(...)` 只接 `Expression` 与属性路径 |
| ConfigurationBehaviorIntegrationTest.java | saveOriginalConfig / restoreConfig（2 个生命周期方法） | 删除 | 这两个方法用 2.2.3 的 `JdbcConfig` / `LoggingConfig` 存取配置快照；3.x 无配置类可读可写（`grep -rn "class JdbcConfig\|class LoggingConfig\|interface JdbcConfig\|interface LoggingConfig" nextentity-*/src/main` = 0 命中） |
| ConfigurationBehaviorIntegrationTest.java | testInlineNumericLiteralsEnabled / testQueryTimeoutConfig / testFetchSizeConfig / testBatchEnabledByDefault / testBatchSizeConfig / testLoggingEnabled / testLoggingDisabled（7 条） | 删除 | 全部围绕 `JdbcConfig` / `LoggingConfig` / `SqlLogger` 的可配置项（`nextentity-api`/`core`/`jpa`/`jdbc` 的 `src/main` 里三个类型均不存在，`grep` 0 命中），3.x 没有这些配置面 |
| CrudOperationsIntegrationTest.java | shouldHandleDeleteNonExistent（1 条） | 删除 | 上游断言「删除不存在的实体抛 `RuntimeException`」，形态是 2.2.3 的 `context.getUpdateExecutor().delete(nonExistent, entityContext)`；3.x 条件删除返回受影响行数 `int`（`api/Executable.java:14`），没有「按实体删除并抛异常」的入口。**前轮已裁定永久删除**，本轮只记档、不写代码 |

## 修订（前轮结论被本批推翻）

| 文件 | 用例 | 判定 | 依据 |
|---|---|---|---|
| OrderOperatorDefaultMethodsIntegrationTest.java | shouldSliceWithOffset | 移植（port-c 的「待裁决」转正） | port-c 曾删掉 `slice.offset()==2` 断言（当时记 `api/model/Slice.java` 无访问器）；现在 `Slice.java:30-39` 有 `default int offset()` / `default int limit()`，`SliceResult` 已实现，断言按上游补回 |
| LockModeIntegrationTest.java + CollectorLockModeMethodsIntegrationTest.java | 锁+offset 共 5 条（含上表 1+4） | 移植（原记「删除：锁+offset 互斥」） | brief §5 的「锁+offset 互斥」是照第二轮旧 api 写的，已过期：3.1.0 起 `lock()` 返回 `Collector`（`api/LockStep.java:15`），而 `Collector.list(int offset, int limit)`（`api/Collector.java:57-59`）就是带锁分页的正位，内置实现在 `nextentity-core/src/main/java/io/github/nextentity/core/builder/QueryBuilder.java:275`、`nextentity-jpa/src/main/java/io/github/nextentity/jpa/exec/JpaExecutor.java:45-59`（窗口与锁一起渲染） |
| NestedPathIntegrationTest.java | 2 条（见回填明细） | 移植（原记「删除：`selectDistinct` 只收 `PathRef`」） | `api/DistinctStep.java:28` 的 `distinct()` 由紧随投影消费，仍是 SQL 侧 `SELECT DISTINCT` |
| EmbeddedCrudIntegrationTest.java | shouldConditionalUpdateCrossLayerEmbedded | 移植（原记「删除：3.x 无条件更新」） | 见回填明细 |

## 生命周期方法

- `ConditionalUpdateDeleteTest.tearDown`、`ConditionalUpdateWithJoinTest.tearDown`：按上游原样保留（`IntegrationTestProvider.getEntityManagerContext()` + `context.reset()`），与 `UpdateApiIntegrationTest` 同款。`reset()` 本身幂等，重复复位无害，因此不属「有副作用的必须删」。
- `ConfigurationBehaviorIntegrationTest.saveOriginalConfig` / `restoreConfig`：**删除**，因为它们读写的是 2.2.3 的配置对象（`JdbcConfig` / `LoggingConfig`），3.x 没有对位可写，属能力缺失而非纯复位，见删除明细。

## 与上游的唯一形态差异：条件改删显式包事务

上游 2.2.3 的 `context.update(...)` / `context.delete(...)` 背后是 `TransactionUpdateExecutor`，**每次调用自己开事务**，用例里不写事务边界。本模块装置（`nextentity-tests-legacy/src/test/java/io/github/nextentity/integration/config/LegacyHarness.java:213-271`）
的 `TransactionalEntityOperations` 只给 `insert`/`insertAll`/`update(entity)`/`updateAll` 包事务，`:259-270` 的
`update()` / `delete()` 是形状委托、不包事务（注释按第二轮「条件改删未移植」写的，本轮已过期）。

本轮口径（Lead 裁决）：**在调用点显式包事务**，即
`int updated = context.doInTransaction(() -> context.operations(X.class).update().set(...).where(...).execute());`，
复现 2.2.3 `TransactionUpdateExecutor` 的自动事务语义（`LegacyHarness.java:110-113` 注释即此意），断言一行未改。
不在装置里自动包的原因是：`UpdateWhereStep extends Whereable<T, UpdateWhereStep<T>>`（`api/UpdateWhereStep.java:9`），
而 `where(PathRef)` 返回的是 `PathOperator` 之类的算子链实例，算子上的 `eq()`/`startsWith()` 返回 core 自己的步骤实现，
会绕过任何包装器——要自动包就得把约 50 个算子方法全部包一遍，且整方法级别的事务会和 `insert` 自身的
`inTransaction` 二次 `begin()` 冲突。若后续装置改了这条路径，本批的 `doInTransaction` 嵌套 join 无害，可直接保留。

## 遗留能力缺口（供后续轮次）

- `orderBy(Collection)` / `OrderOperator` 的默认委托方法：`api/OrderByStep.java` 只有两个单列重载，`api/OrderOperator.java` 只有 `asc()`/`desc()`。
- 子查询：`toSubQuery()` / `asSubQuery()` 不存在（`api/Collector.java`、`api/SelectStep.java` 均无）。
- 配置面：`JdbcConfig` / `LoggingConfig` / `SqlLogger` 不存在（`nextentity-api`/`core`/`jpa`/`jdbc` 的 `src/main` grep 0 命中）。
- 按实体删除并抛异常：3.x 条件删除返回行数，无 2.2.3 `PersistExecutor.delete(entity, context)` 的抛错形态。
