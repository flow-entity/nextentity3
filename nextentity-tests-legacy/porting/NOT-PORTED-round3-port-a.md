# NOT-PORTED — port-a 第三轮（StringExpression / StringOperations）

> **本轮回填（2026-10-31，`like(String)` 与 `StringPatternOps*` 一族落地之后）**：上一轮
> [`NOT-PORTED-port-c.md`](NOT-PORTED-port-c.md) 之后，`nextentity-api` 恢复了 `like(String)` 与
> `StringPatternOps` / `StringPatternOpsIfNotNull` / `StringPatternOpsIfNotEmpty` 一族
> （startsWith / endsWith / notLike / notStartsWith / notEndsWith / notContains 及其 IfNotNull / IfNotEmpty 变体），
> 本轮据此回填两个文件的缺口。总账见 [`REPORT.md`](REPORT.md)。

口径：行号均指 `nextentity-api/src/main/java/io/github/nextentity/api/`。
**移植** = 只改调用形态（`where(属性).算子(...)` 链、多条件用多个 `where` 即 AND、`Path.of`/`EntityPath.of` 等），断言与期望值一行未动；
**删除** = 3.x api 已无对应能力，不造近似映射；**待裁决** = 已改完能跑但怀疑语义不等价。

补充说明（第二轮复核后修正）：上游 `@Nested PredicateAsQueryConditionTests` 的 25 条里，**23 条已回填**——
它们只测「一条字符串条件进 where 之后的过滤语义」，3.x 用 `where(PathRef.StringRef)` 重载
（`Whereable.java:29` 返回 `StringOperator<T, ? extends SELF>`）即可等价表达：
`query.where(Path.of(Employee::getName).startsWith("A"))` → `query.where(Employee::getName).startsWith("A")`；
多条 AND 即链式多 `where(...)`；`not()` 用同族 `not*` 算子（`not()` 只存在于 `Predicate`/`LogicalOps`，见 `LogicalOps.java:58`）。
只有需要把**两条字符串算子 `or` 起来**的 2 条无法表达（见删除明细）。

编译验证：`bash tmp/scripts/compile-legacy.sh D:/source/java/nextentity3/tmp/legacy/out-a` → **EXIT=0**。
直跑（H2，`MODE=MySQL`）：
`bash tmp/scripts/run-legacy-tests.sh D:/source/java/nextentity3/tmp/legacy/out-a io.github.nextentity.integration.StringExpressionDefaultMethodsIntegrationTest io.github.nextentity.integration.StringOperationsIntegrationTest`
→ **EXIT=0**，`111 tests found / 0 skipped / 111 successful / 0 failed`（61 + 50）。

结果：保留 **111** 条，删除 **4** 条，待裁决 **0** 条。
（缺口清单 `tmp/legacy/missing-methods.txt` 实际列 StringExpression 57 条 / StringOperations 33 条；
本轮 StringExpression 新移植 55 条（32 条非 nested + 23 条 PredicateAsQueryCondition 改写成 where 链回填）、
StringOperations 新移植 31 条；删除 2 + 2 条，逐条对应。）

## 汇总

| 文件 | 保留 | 删除 | 待裁决 |
|---|---:|---:|---:|
| StringExpressionDefaultMethodsIntegrationTest.java | 61 | 2 | 0 |
| StringOperationsIntegrationTest.java | 50 | 2 | 0 |
| **合计** | **111** | **4** | **0** |

## 删除明细（按能力归并）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| StringExpressionDefaultMethodsIntegrationTest.java | `shouldCombineMultipleStringPredicatesWithOr`、`shouldCreateComplexPredicateWithStringExpressions` | 删除 | 两条的被测对象都是「**两条字符串算子用 `or` 组合**」（后者再叠一层 `and`）。3.x 的 `or(...)` 只在 `Predicate`/`LogicalOps` 上（`LogicalOps.java:24`），而字符串算子返回的是 `StringOperator`（`StringOperator.java:9`）——它不产出 `Predicate`，两条字符串算子之间没有 `or` 可用（`Path.java:13-22` 也不继承 StringOps）。拆成两次查询再合并会改变断言里的 `allMatch`/`isNotEmpty` 与排序语义，故不造映射；单条件、AND、NOT 三类同族用例均已回填 |
| StringOperationsIntegrationTest.java | `shouldFilterWithLikeIfNotEmpty`、`shouldReturnAllWhenLikeIfNotEmptyIsEmpty` | 删除 | `StringOps.java:12-44` 只有 contains / containsIfNotEmpty / containsIfNotNull / like / likeIfNotNull / eqIfNotEmpty，**无 `likeIfNotEmpty`**；`StringPatternOpsIfNotEmpty.java:31` 只补了 `notLikeIfNotEmpty`，没有正向 `likeIfNotEmpty`。不能拿 `likeIfNotNull` 顶替：空串 `like ""` 只命中空串，不会像 IfNotEmpty 那样跳过条件，语义不等价 |

## 待裁决

无。

## 影响范围

只改 `StringExpressionDefaultMethodsIntegrationTest.java`、`StringOperationsIntegrationTest.java` 两个文件并新增本记档；
未动生产代码、`config/`、`entity/`、其他批次文件与 `README.md` / `REPORT.md`。
