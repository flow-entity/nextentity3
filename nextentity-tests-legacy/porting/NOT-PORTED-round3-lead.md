# 第三轮移植：批次外裁决与总账

本轮（round 3）把上一轮按「能力缺口就删」处理掉的、以及从未建过的用例重新过了一遍：起因是
`b9cc50c` 之后 api 恢复了 2.2.3 的能力面（字符串算子/函数、`Slice` 窗口访问器、DTO 投影、`fetch`、
条件更新/删除、分组计数）。**前两轮任何一条「api 没有 X」的依据都有过期风险**，本轮逐条对 api 源码重核。

五个批次（a~e）的逐条依据各自在 `NOT-PORTED-round3-port-*.md`，本文件只记**批次外**的裁决与总账。

## 总账

| 项 | 2.2.3 | 第二轮 | 本轮 |
|---|---|---|---|
| `integration` 用例注解数 | 1,055 | 727 | **954** |
| 单环境执行数 | — | 727 | **954** |
| 文件数 | 114 | 85 | **99**+ |
| 未移植注解数 | — | 328 | **101** |

> 1,055 是上游 `integration` 目录（含 `fast/` 与 `config/DatabaseInitializerTest`）实际注解数；
> 旧文档写的 1,063 是历史口径（把已删的辅助类也算进去了），本轮以 1,055 为准。
> `+` 文件数含 `config/LegacyHarness.java`，它没有上游对应物。

未移植 101 条的构成（与本轮各 doc 一一对应）：

| 类别 | 条数 | 依据 |
|---|---|---|
| 接口/抽象类投影 | 15 | `api/SelectStep.java:75-77` 抛 `IllegalArgumentException` |
| 7~10 列投影、`select(Collection)`/`select(List)` | 23 | 无对应重载；`ProjectionMappers` 上界是 Tuple6 |
| `toSubQuery` / `asSubQuery` | 7 | `EntityOperations` 类注释明写仍不提供 |
| `orderBy(Collection)` / `OrderOperator` 默认方法 | 6 | `api/OrderByStep.java` 只有两个单列重载 |
| 配置面 `JdbcConfig`/`LoggingConfig`/`SqlLogger` | 7 | api 无这些类型 |
| 字符串谓词 `or` 组合 | 4 | `or(...)` 只在 `Predicate`/`LogicalOps`，`StringOperator` 不产 Predicate |
| `likeIfNotEmpty` | 2 | `api/StringOps.java` 只有 `likeIfNotNull`（不跳过空串，语义不等价） |
| 算术字面值/表达式右值/`*IfNotNull`/聚合套算术 | 25 | `NumberArithmetic` 只接受字面值右值；`NumberValue.java:8` 明写聚合套算术不提供 |
| 嵌套 DTO / 注解投影（`@EntityPath`、`@Join`） | 5 | 3.x 无注解投影；嵌套 DTO 无对位 |
| 前轮裁定永久删除 | 1 | `CrudOperationsIntegrationTest.shouldHandleDeleteNonExistent`（断言与注释自相矛盾，见 REPORT §裁决 1） |
| 整类不建（批次外） | 8 | 见下节 |

## 批次外：4 个整类不建（8 条）

| 上游文件 | 条数 | 判定 |
|---|---|---|
| `AutoIncrementInsertPerformanceVerificationTest` | 1 | 无断言的性能探针（计时打印），在 H2 / 容器上均不稳定，不构成语义证据。不移植，也不留空壳 |
| `ProjectionJoinAnnotationTest` | 2 | 被测对象是 2.2.3 私有注解 `@Join` 的投影语义；3.x 无注解投影（`@Join` 只存在于 `nextentity-jpa` 内部元数据），无对位 |
| `fast/FastProjectionQueryIntegrationTest` | 3 | 接口投影 + LAZY 关联，`select(Class)` 不支持接口；且继承自已不存在的 `FastIntegrationTestProvider` |
| `config/DatabaseInitializerTest` | 2 | 测 2.2.3 Spring 装置自身（`IntegrationTestApplication.DatabaseInitializer` 的建表/夹具/reset）；被测对象在本模块已由 `LegacyHarness` 取代，移植等于自己测自己的装置 |

## 本轮反转的四处旧结论

前两轮 doc 里的「api 没有 X / X 与 Y 互斥」有四处已被 `b9cc50c` 推翻，本轮按源码重核后**回填**：

1. **`Slice.offset()` / `limit()` 存在**（`api/model/Slice.java:30-39` default 方法；
   `core/builder/SliceResult.java:19` 是 `record SliceResult(data, total, offset, limit)`）。
   回填 4 条断言：`SlicePaginationIntegrationTest.shouldSliceResults`（offset=0/limit=5）、
   `shouldSliceWithOffset`（offset=5）、`StreamQueryIntegrationTest.shouldSliceWithOffset`（offset=5）、
   `OrderOperatorDefaultMethodsIntegrationTest.shouldSliceWithOffset`（offset=2，port-e 执行）。
   前两轮「`SlicePagination` 断言 23 → 20」的缺口已补齐，本轮断言数与上游一致。
2. **锁 + offset 分页可表达**：`api/Collector.java:38-59` 的 `default List<T> list(offset, limit)`
   就是为 `LockStep#lock`（返回 `Collector`）的带锁分页加的；实现见
   `core/builder/QueryBuilder.java:275` 与 `nextentity-jpa/.../JpaExecutor.java:45-59`
   （同时 `setFirstResult` / `setMaxResults` / `setLockMode`）。回填 5 条
   （`LockModeIntegrationTest.shouldLockWithOffset` + `CollectorLockModeMethods` 4 条，形态
   `.lock(LockMode.X).list(offset, limit)`），port-e 执行。
3. **负 offset**：`core/spec/Window.java` 在构造期对负数抛 `IllegalArgumentException`，
   `api/Collector#list(int,int)` javadoc 对此有 `@throws`。用例保持上游调用形态 `.list(-1, 5)`
   并断言 `IllegalArgumentException`（上一轮写成 `.slice(-1,5)`，断言相同但形态偏离上游）。
4. **条件更新 / 条件删除**：`UpdateSetStep`/`UpdateWhereStep`/`DeleteWhereStep` 已具备
   （`core/repo/SimpleEntityOperationsFactory.java:103` / `:108`）。两条 `ConditionalUpdate*` 整类
   重新建立，共 26 条。

## 唯一一处额外形态差异：条件改删在调用点包事务

`LegacyHarness.TransactionalEntityOperations` 只给 `insert/insertAll/update(entity)/updateAll` 自动包事务；
`update()`/`delete()`（条件改删入口）**不包**，用例按
`context.doInTransaction(() -> operations(X.class).update()…execute())` 书写，语义等价于 2.2.3
`TransactionUpdateExecutor` 的「执行器兜住事务」。

不把它塞进装置的理由（`LegacyHarness.java` 注释同步记录了这段）：`UpdateWhereStep extends
Whereable<T, UpdateWhereStep<T>>`，而 `Whereable.where(PathRef)` / `where(StringPath)` 返回的是
`PathOperator` / `StringOperator`，其算子返回 core 自己的步骤实例、会绕过任何装饰器；要在装置里自动包事务
就得包装约 50 个算子方法（几百行样板只为省一层 lambda），而整方法级事务又会与 `insert` 自身的
`inTransaction` 二次 begin 冲突。这是本轮唯一一处「断言之外的形态差异」，全部 29 条条件改删用例同一写法。

## 复核证据

- **断言未弱化的机械核对**：`tmp/scripts/check-assertions.py` 逐方法比对上游与本模块的 `assertThat…;`
  语句（归一化后做差集）。全量 1,055 条语句中，唯一差异是 `EdgeCasesIntegrationTest.shouldHandleNegativeOffset`
  从 `assertThat(employees).isNotNull()`（空洞断言）改为 `assertThatThrownBy(...).isInstanceOf(IllegalArgumentException.class)`
  ——这是本轮唯一一处**加强**，理由见 REPORT §裁决 2。
- **方法覆盖**：上游 1,024 个测试方法（去 4 个生命周期方法）对本模块做方法名集合差，剩余 98 条 / 16 文件，
  加上不属 `*IntegrationTest.java` 命名口径的 `AutoIncrement…VerificationTest`(1) 与
  `config/DatabaseInitializerTest`(2)，合计 101，与注解差一致。
- **逐文件 diff**：`git diff` 与上游同文件逐段比对，改动只落在方法内调用形态（`context.queryXxx()`、
  算子名、`Path` → `EntityPath`、`list(o,l)` → `slice(o,l).data()` 的反向回填等），无断言删减、无新增期望值。
- **门禁**：见 REPORT 顶部「环境覆盖」（H2 与真 MySQL 两个通道，串行执行）。
