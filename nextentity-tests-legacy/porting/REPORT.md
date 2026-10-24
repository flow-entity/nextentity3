# 2.2.3 用例移植报告

## 结果

| 项 | 2.2.3 | 第一轮移植 | 第二轮回填（当前） |
|---|---|---|---|
| `integration` 用例注解数 | 1,063 | 603 | **727** |
| 单环境执行数 | — | 593 | **727** |
| 文件数 | 114（含夹具/环境/DTO） | 83 | 85 |
| 删除用例 | — | 460 条 + 5 个整类 | **336 条 + 3 个整类** |

环境覆盖：`h2`（默认，随全量构建跑）与 `mysql`（真 MySQL 8.0.18）**各 727 条全绿**，合计 1,454 次执行。

> 第二轮的口径：`7b8a71aa` 之后 api 补齐了第一梯队 10 项能力（`not()`、`min`/`avg`、`having`、
> 多列 `groupBy`、`Tuple3~6` + `ProjectionMappers`、数值算术、列对列比较、`notBetween`、`like`、`distinct()`），
> 第一轮按「能力缺口就删」处理掉的用例里有一批重新可移植。回填 **+134 条**，逐条依据见各
> `NOT-PORTED-*.md` 顶部的「本轮回填」小节。

## 保留原则

**断言不动，只改调用形态。** 期望值、边界、异常类型一处未改；改的只有：怎么拿到查询入口
（`EntityTemplateFactory` → `EntityOperations`）、算子叫什么（`like` → `likeIfNotNull`、
`list(offset,limit)` → `slice(offset,limit).data()`）、锁模式枚举（`LockModeType` → `LockMode`）、
谓词组合（`Predicates.and(a,b)` → `a.and(b)`）、多参数 `orderBy` → 累积链 `orderBy(a).asc().orderBy(b).desc()`。

**能力缺口就删，不造映射。** 3.x 仍然没有的能力（`fetch`、DTO/record/接口投影（`select(Class)`）、
条件更新/删除、子查询（`toSubQuery`）、`startsWith`/`endsWith`/`notLike`/`lower`/`length`/`substring`、
算术的 `*IfNotNull` 变体、`orderBy(Collection)`、`Slice.offset()/limit()`、锁 + 分页组合、
`EntityRoot.literal`、7 列及以上投影与 `select(Collection)`）对应的用例一律删除，
逐条依据见本目录四个 `NOT-PORTED-*.md`，每条都指到 api 源码的 `文件:行`。

两处**刻意不采纳**的等价替换，避免把「等价前提」藏进用例：

- `startsWith(v)` / `endsWith(v)` 在 2.2.3 的实现就是 `like(v + '%')`，用 `StringOps.like("A%")` 能跑通，
  但等价性依赖「实参不含 `%` / `_`」，且用例名会与被测算子不符（名字说 startsWith，测的是 like）；
- 7 列及以上 / `select(Collection)` 可用自定义 `ProjectionMapper` 透传表达，但结果类型会从 `TupleN`
  降为 `Tuple`、断言里的 `getN()` 要改成 `get(N)`，属改写断言形态。

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
18 条、`CollectorLockModeMethodsIntegrationTest` 20 条全绿，SQL 与行数一致，**映射成立**。

### 4. `fetch(...)` 删除后依赖会话内惰性加载的 18 条 —— 已通过

2.2.3 用 `fetch` 预取关联；3.x 没有 `fetch`，这些用例改为直接读关联属性，依赖同一持久化上下文内的
惰性加载。两个环境全绿，**说明读路径确实没有提前 clear**，与装置注释一致。

## 已知的断言减少（记录在案，未擅自补 API）

| 位置 | 减少内容 | 原因 |
|---|---|---|
| `SlicePaginationIntegrationTest`（3 条）、`OrderOperatorDefaultMethodsIntegrationTest`（1 条）、`StreamQueryIntegrationTest`（1 条） | 读 `slice.offset()` / `slice.limit()` 的断言（`SlicePagination` 断言数 23 → 20，其余断言全保留） | `api/model/Slice.java` 只有 `data()` / `total()` / `to()`，没有 `offset()` / `limit()` |
| `LockModeIntegrationTest` 等 5 个方法 | 锁 + offset 分页的用例 | `api/LockStep.java` 的 `lock(...)` 返回终止态 `Collector`，与 `slice()` 互斥，该组合在 3.x 不可表达 |

这两处都是**能力面缺口**，不是移植偷懒。当前 point-mall 对二者零调用点（锁只用于
`lock(PESSIMISTIC_WRITE).first()`，分页不要求加锁），因此按「无调用点不加 API」的规矩**不补**，
留档待将来真有消费方时再议。

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

## 复现

```bash
./mvnw -pl nextentity-tests-legacy test                    # H2
./mvnw -pl nextentity-tests-legacy test -Ddbc=h2,mysql     # H2 + MySQL
```
