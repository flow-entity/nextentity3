# 2.2.3 用例移植报告

## 结果

| 项 | 2.2.3 | 移植后 |
|---|---|---|
| `integration` 用例注解数 | 1,063 | **603** |
| 单环境执行数 | — | **593**（`@Nested` 与参数化展开后） |
| 文件数 | 114（含夹具/环境/DTO） | 83 |
| 删除用例 | — | **460** 条 + 5 个整类 |

环境覆盖：`h2`（默认，随全量构建跑）与 `mysql`（真 MySQL 8.0.18）**各 593 条全绿**，合计 1,186 次执行。

## 保留原则

**断言不动，只改调用形态。** 期望值、边界、异常类型一处未改；改的只有：怎么拿到查询入口
（`EntityTemplateFactory` → `EntityOperations`）、算子叫什么（`like` → `likeIfNotNull`、
`list(offset,limit)` → `slice(offset,limit).data()`）、锁模式枚举（`LockModeType` → `LockMode`）、
谓词组合（`Predicates.and(a,b)` → `a.and(b)`）。

**能力缺口就删，不造映射。** 3.x 没有的能力（`fetch`、`Tuple3+`、DTO/record 投影、`avg`/`min`、
`having`、条件更新/删除、子查询、`startsWith`/`endsWith`/`notLike`、多参数 `orderBy`/`groupBy`、
数值算术、`core.util.Predicates`、`jdbc` 引擎、`Order` 枚举、`stream()`）对应的用例一律删除，
逐条依据见本目录四个 `NOT-PORTED-*.md`，每条都指到 api 源码的 `文件:行`。

这条纪律的意义在于：移植语料**不能**反过来要求库把删掉的 API 加回来。若把 460 条都"想办法"凑绿，
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

## 复现

```bash
./mvnw -pl nextentity-tests-legacy test                    # H2
./mvnw -pl nextentity-tests-legacy test -Ddbc=h2,mysql     # H2 + MySQL
```
