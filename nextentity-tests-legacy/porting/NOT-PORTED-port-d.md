# NOT-PORTED — port-d（写入 / 锁 / 事务 / 杂项批次）

> 口径：[API-MAPPING.md](API-MAPPING.md)。编译验证：`.\tmp\scripts\legacy-javac.ps1 -Name port-d` → 日志 `tmp\legacy\javac-port-d.log`，**我这批 15 个文件 0 报错**（日志里剩余报错属其他 agent 的文件）。
> 判定：`移植` = 改调用形态后能跑；`删除` = 3.x 能力已删；`待裁决` = 已改完但怀疑 3.x 行为不同，留 B2 跑出来看。

## 一、整类删除（能力已删，6 个文件）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| ConditionalUpdateDeleteTest.java | 全类 20 个方法 | 删除 | 3.x 无条件更新/删除：`api` 无 `update(Class)`/`delete(Class)` 入口，`core/repo/EntityOperations.java` 只有 `insert/insertAll/update/updateAll` + `query()`；API-MAPPING「条件更新/删除」条 |
| ConditionalUpdateWithJoinTest.java | 全类 8 个方法 | 删除 | 同上（且用例主体是「按关联条件批量改/删」） |
| FetchOperationsIntegrationTest.java | 全类 19 个方法 | 删除 | 3.x 无 `fetch(...)`：`api/SelectStep.java` 只有 `select`/`selectDistinct`/两列 `select` 四个重载，无 `fetch` 族 |
| FetchStepMultipleParametersIntegrationTest.java | 全类 6 个方法 | 删除 | 同上（多参数 `fetch` 更无对位） |
| DeepFetchTwoLevelIntegrationTest.java | 全类 7 个方法 | 删除 | 同上（两级 fetch） |
| AutoIncrementInsertPerformanceVerificationTest.java | 全类 1 个方法 | 删除 | 依赖 3.x 不存在的 `config.ApplicationContexts`（Spring 容器入口）；且该类只打印耗时、无断言，并硬引用另一个用例的方法名，属性能探针不属能力用例 |

## 二、方法级删除（14 个方法）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| ConfigurationBehaviorIntegrationTest.java | `testInlineNumericLiteralsEnabled` / `testQueryTimeoutConfig` / `testFetchSizeConfig` / `testBatchEnabledByDefault` / `testBatchSizeConfig` / `testLoggingEnabled` / `testLoggingDisabled`（7 个）+ `@BeforeEach saveOriginalConfig` / `@AfterEach restoreConfig` 的配置存取部分 | 删除 | 3.x 无 `io.github.nextentity.jdbc.JdbcConfig`、无 `core.LoggingConfig`、无 `core.SqlLogger`（`grep -r "class JdbcConfig\|class SqlLogger\|class LoggingConfig" nextentity-*/src/main` = 0 命中），这些用例断言的就是这些配置对象 |
| LockModeIntegrationTest.java | `shouldLockWithOffset`（1 个） | 删除 | 3.x 锁与分页互斥：`api/LockStep.java:15` `lock(...)` 返回 `Collector`（终止态），`api/SliceStep.java` `slice(offset,limit)` 返回 `Slice`，无法在 `.lock(...)` 之后再分页；原用例 `.lock(...).slice(2,3).data()` 不可表达 |
| CollectorExtendedMethodsIntegrationTest.java | `shouldConvertToSubquery`（1 个） | 删除 | 3.x 无子查询：`api` 无 `toSubQuery`/`asSubQuery` |
| CollectorLockModeMethodsIntegrationTest.java | `shouldOffsetWithLockModeReturnResultsFromOffset` / `shouldOffsetWithPessimisticWriteLock` / `shouldFirstWithOffsetAndLockModeReturnValue` / `shouldOffsetWithLockModeAndWhereClause`（4 个） | 删除 | 同 `shouldLockWithOffset`：锁 + offset 分页在 3.x 不可表达 |
| EmbeddedCrudIntegrationTest.java | `shouldConditionalUpdateCrossLayerEmbedded`（1 个） | 删除 | 条件更新（`context.update(X.class).set(...).where(...)`）3.x 无对位 |

## 三、移植（改调用形态，断言与期望值一律未动，9 个文件）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| InstantLiteralTimezoneIntegrationTest.java | `shouldMatchInstantConditionUnderNonUtcDefaultZone` | 移植 | `new EntityQueryImpl<>(getEntityContext(X.class))` → `context.operations(X.class).query()`；清场用「查出来再 `context.delete`」替代条件删除，断言不变 |
| AttributeOverrideCrudIntegrationTest.java | `shouldFilterByOverriddenFieldViaPathChain` / `shouldFilterByNestedOverriddenField` | 移植 | 嵌套路径 `where(X::getA).get(B::getC)` → `where(EntityPath.of(X::getA).get(B::getC))`（`api/EntityPath.java` 的 `get` 是路径链入口） |
| EmbeddedFieldCrudIntegrationTest.java | 4 处嵌套路径过滤 | 移植 | 同上 |
| EmbeddedCrudIntegrationTest.java | 11 处嵌套路径过滤（含跨层 embedded 三级链） | 移植 | 同上 |
| LockModeIntegrationTest.java | `shouldQueryWithReadLock` / `shouldQueryWithWriteLock` / `shouldQueryWithNoLock` | 待裁决 | 3.x `api/LockMode` 无 `READ`/`WRITE`/`NONE`。按 JPA 2.0 起的等价关系映射：`READ → OPTIMISTIC`、`WRITE → OPTIMISTIC_FORCE_INCREMENT`、`NONE → 去掉 lock(...)`（语义即「不加锁」）。断言未改，等价性留 B2 在真库上确认 |
| CollectorExtendedMethodsIntegrationTest.java | 3 个 offset 用例 + `shouldGetSingleThrowExceptionWhenMultipleResults` | 移植 | `.list(offset, limit)` → `.slice(offset, limit).data()`；`NextEntityException` → `IllegalStateException`（3.x `api/Collector.java:37` 的 `@throws` 契约，消息固定 `found more than one result, expected one`） |
| CollectorLockModeMethodsIntegrationTest.java | 4 个 offset 用例 + `shouldGetSingleWithLockModeThrowWhenMultipleResults` + 注释里的 `LockModeType` 标签 | 移植 | 同上（`.list(offset,limit)` → `.slice().data()`、异常类型换 3.x 契约）；`LockModeType` 字样改为 `LockMode`，因为该类型在 3.x api 中不存在 |
| ConfigurationBehaviorIntegrationTest.java | `CrudOperationsTests`（`testInsert`/`testUpdate`/`testDelete`/`testQuery`） | 移植 | 只保留与配置无关的 4 个 CRUD 用例，断言原样；类注释写明删掉的 7 个配置用例与理由 |
| JpaSpecificFeaturesIntegrationTest.java | 全类 19 个方法 | 移植（无需改动） | 已全部使用 3.x 形态（`context.queryLockableEntities()` + `lock(LockMode.X)` + `doInTransaction`），编译 0 报错，无 `getEntityContext`/`EntityQueryImpl`/`LockModeType` 残留 |

## 四、待裁决汇总（2 项）

| 项 | 现状 | 留给 B2 确认什么 |
|---|---|---|
| 2.2.3 `LockMode.READ`/`WRITE` → 3.x `OPTIMISTIC`/`OPTIMISTIC_FORCE_INCREMENT` | 已按 JPA 2.0 等价关系映射，断言未改 | 真库上两种模式的实际 SQL（是否出现 `version` 条件）与返回行是否一致 |
| 锁 + offset 分页互斥（5 个方法删除） | 3.x `lock(...)` 返回终止态 `Collector`，无法再 `slice` | 若 B2 认为这是必须保留的能力面，需要 api 侧新增入口（不是本批次能改的） |

