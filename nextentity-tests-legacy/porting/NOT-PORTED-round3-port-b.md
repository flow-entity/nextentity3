# NOT-PORTED — port-b 第三轮（StringOperator / ExpressionBuilder / AndOrPredicate / WhereImpl / NumericOperations）

> **本轮回填（2026-10-31，`StringPatternOps*` 一族与 `StringFunctions` 落地之后）**：上一轮
> [`NOT-PORTED-port-c.md`](NOT-PORTED-port-c.md) 之后，`nextentity-api` 恢复了 `startsWith` / `endsWith` /
> `notStartsWith` / `notEndsWith` / `notContains` 及其 `IfNotNull` / `IfNotEmpty` 变体
> （`StringPatternOps.java` / `StringPatternOpsIfNotNull.java` / `StringPatternOpsIfNotEmpty.java`），
> 并补上 `StringFunctions.lower/upper/trim/substring/length`。本轮据此回填 35 条缺口
> （StringOperatorDefaultMethods 29、ExpressionBuilder 2、AndOrPredicate 3、WhereImpl 1），
> 删除 32 条。仍然缺：`likeIfNotEmpty`、算术的 `*IfNotNull` 变体、算术右值为表达式 / `literal`、
> `toSubQuery()` / `asSubQuery()`、`orderBy(Collection)`。总账见 [`REPORT.md`](REPORT.md)。

口径：行号均指 `nextentity-api/src/main/java/io/github/nextentity/api/`。
**移植** = 只改调用形态（`where(StringRef)` 链、`Path.of` / `EntityPath.of`、多列排序改累积链等），断言与期望值一行未动；
**删除** = 3.x api 已无对应能力，不造近似映射；**待裁决** = 已改完能跑但怀疑语义不等价。

编译验证：`bash tmp/scripts/compile-legacy.sh D:/source/java/nextentity3/tmp/legacy/out-b` → **EXIT=0**
（只有 `unchecked or unsafe operations` 提示，无错误）。

直跑（H2，`jdbc:h2:mem:nextentity;MODE=MySQL`）：
`bash tmp/scripts/run-legacy-tests.sh D:/source/java/nextentity3/tmp/legacy/out-b io.github.nextentity.integration.StringOperatorDefaultMethodsIntegrationTest io.github.nextentity.integration.ExpressionBuilderDefaultMethodsIntegrationTest io.github.nextentity.integration.AndOrPredicateIntegrationTest io.github.nextentity.integration.WhereImplIntegrationTest io.github.nextentity.integration.NumericOperationsIntegrationTest`
→ **EXIT=0**，`166 tests found / 166 started / 166 successful / 0 failed / 0 skipped`（34 + 12 + 57 + 20 + 43，
含本轮新增与原有已移植用例的回归）。

结果：保留 **166** 条用例，删除 **32** 条，待裁决 **0** 条。
（缺口清单 `tmp/legacy/missing-methods.txt` 本批实际列 67 条：StringOperatorDefaultMethods 29 / ExpressionBuilder 11 /
AndOrPredicate 5 / WhereImpl 4 / NumericOperations 18；其中移植 29 + 2 + 3 + 1 + 0 = 35，删除 0 + 9 + 2 + 3 + 18 = 32。）

## 汇总

| 文件 | 保留 | 删除 | 待裁决 |
|---|---:|---:|---:|
| StringOperatorDefaultMethodsIntegrationTest.java | 34 | 0 | 0 |
| ExpressionBuilderDefaultMethodsIntegrationTest.java | 12 | 9 | 0 |
| AndOrPredicateIntegrationTest.java | 57 | 2 | 0 |
| WhereImplIntegrationTest.java | 20 | 3 | 0 |
| NumericOperationsIntegrationTest.java | 43 | 18 | 0 |
| **合计** | **166** | **32** | **0** |

## 移植要点（只动调用形态，断言未动）

| 文件 | 做法 | 说明 |
|---|---|---|
| StringOperatorDefaultMethodsIntegrationTest.java | 目标文件以上游同名文件为模板整体重写，仅保留原第 24 行 `/// @see io.github.nextentity.api.StringOperator`（上游写 `ExpressionBuilder.StringOperator`）；原有 5 条 `contains*` 用例与上游逐字相同，等价于原样保留 | 29 条缺口的调用形态与上游一致：`where(Employee::getName).startsWithIfNotNull(...)` / `endsWithIfNotNull` / `notStartsWithIfNotNull` / `notEndsWithIfNotNull` / `notContainsIfNotNull` 及对应 `*IfNotEmpty`；`count()` / `exists()` / `first()` 不变。方法全在 `StringPatternOpsIfNotNull.java` / `StringPatternOpsIfNotEmpty.java` |
| ExpressionBuilderDefaultMethodsIntegrationTest.java | 新增 2 条：`where(Employee::getName).substring(1).length().gt(2).list(5)`、`.substring(2).length().gt(1).list(5)`；同时改文件头注释（原注释写「substring / length 一类删除」，本轮已可移植） | `StringFunctions.substring(int)` 返回 `StringOperator<T, B>`，`length()` 返回 `NumberOperator<T, B>`，`gt(2)` 回到构建器步骤，可继续 `list(5)` |
| AndOrPredicateIntegrationTest.java | 3 条：`shouldCombineStringConditionsWithAnd`、`shouldChainAndStringRefReturningStringOperator`、`shouldChainAndStringRefWithStringFunctions`。上游 `and(a, b)` / `eq(true).and(StringRef)` 改为**顺序 `where()` 表达同一 AND**：`where(Employee::getName).startsWith("A").where(Employee::getActive).eq(true)` | 3.x `LogicalOps`（`LogicalOps.java:12`）只有 `and(Expression<Boolean>)`，没有 `and(PathRef)` 重载；顺序 `where` 在同一构建器上累加条件，语义仍是 AND，过滤结果与断言等价 |
| WhereImplIntegrationTest.java | 新增 `shouldSupportStartsWithOperation_WithStringPath`：`where(Employee::getName).startsWith("J").orderBy(Employee::getId).asc().list()` | 上游写 `StringPath<Employee> namePath = Path.of(Employee::getName)`（2.2.3 的 `Path.of(StringRef)` 返回 `StringPath`）；3.x `Path.java:13-22` 无静态 `StringPath` 工厂，改从 `where(StringRef)` 步骤直接取 `StringOperator` |
| NumericOperationsIntegrationTest.java | 本轮 0 移植（18 条缺口全删，见下） | 文件未改动；`add/sub/mul/div/mod` 的字面值形态已在上一轮移植（`shouldAddValueInSelect` 等），本轮缺口测的是「算术右值为表达式 / `literal`」与 `*IfNotNull` 短路，两者 3.x 都没有 |

## 删除明细（按能力归并）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| StringOperatorDefaultMethodsIntegrationTest.java | 无 | — | 29 条缺口全部回填，`likeIfNotEmpty` 不在本文件缺口内 |
| ExpressionBuilderDefaultMethodsIntegrationTest.java | `shouldExecuteLikeIfNotEmptyWithNonEmptyValue` / `shouldSkipLikeIfNotEmptyWithEmptyValue` / `shouldSkipLikeIfNotEmptyWithNullValue` | 删除 | `StringOps.java:6` 只有 `contains / containsIfNotEmpty / containsIfNotNull / like / likeIfNotNull / eqIfNotEmpty`，**无 `likeIfNotEmpty`**；`StringPatternOpsIfNotEmpty.java:31` 只补了 `notLikeIfNotEmpty`，没有正向 `likeIfNotEmpty`。不能用 `likeIfNotNull` 顶替：空串 `like ""` 只命中空串，不会像 IfNotEmpty 那样跳过条件，语义不等价 |
| ExpressionBuilderDefaultMethodsIntegrationTest.java | `shouldSkipAddIfNotNullWithNullValue` / `shouldSkipSubtractIfNotNullWithNullValue` / `shouldSkipMultiplyIfNotNullWithNullValue` / `shouldSkipDivideIfNotNullWithNullValue` / `shouldSkipModIfNotNullWithNullValue` / `shouldHandleNullValuesInChainedOperations` | 删除 | `NumberArithmetic.java:10-42` 只有 `add(U) / sub(U) / mul(U) / div(U) / mod(U)`，右值是字面值，**没有 `*IfNotNull` 变体**，也没有「null 即跳过该算子、退化为原值」的语义；上游 `where(Employee::getSalary).addIfNotNull(null).gt(50000.0)` 与链式 `.addIfNotNull(null).addIfNotNull(10000.0)` 无法表达 |
| AndOrPredicateIntegrationTest.java | `shouldCombineStringConditionsWithOr` | 删除 | 上游要 `or(Path.of(Employee::getName).startsWith("A"), Path.of(Employee::getName).startsWith("B"))`。3.x `Path.java:13-22` 不继承 `StringOps`（无 `startsWith`），字符串算子只能出现在 `where(StringRef)` 的构建器步骤上；`LogicalOps.java:12` 的 `and/or` 只接受 `Expression<T, Boolean>`；`Predicate.java:15` 唯一的便捷重载是 `<N> PathOperator<T, N, Predicate<T>> or(PathRef<T, N>)`，而 `PathOperator.java:8` 不含 `StringOps`。即**拿不到可参与 OR 组合的字符串谓词**，顺序 `where` 只能表达 AND，「A 或 B 开头」无法表达 |
| AndOrPredicateIntegrationTest.java | `shouldChainOrStringRefReturningStringOperator` | 删除 | 上游 `Path.of(Employee::getDepartmentId).eq(1L).or(Employee::getName).contains("Brown")`：`or(PathRef)` 返回 `PathOperator<T, String, Predicate<T>>`（`Predicate.java:15-24`），而 `PathOperator.java:8` 只 extends Comparison / ColumnComparison / Range / RangeIfNotNull / Nullness / Membership / Between，**没有 `contains`**；`StringPath.java:9` 的 `StringPath` 无静态工厂（实例只来自 `EntityPath.get(StringRef)`），`Conditions` 是包级私有，无法自建字符串谓词 |
| WhereImplIntegrationTest.java | `@Nested SubQueryGetRootTests` 全类 3 条：`shouldCreateSubquery_FromQuery` / `shouldCreateCountExpression_FromSubquery` / `shouldCreateSliceExpression_FromSubquery` | 删除 | 3.x 无 `toSubQuery()` / `asSubQuery()`（`nextentity-api` 53 个文件里 grep 不到这两个名字；`Collector.java` 只有 `list/list(int)/list(int,int)/first/single/count/exists`，`Whereable.java` 的 `where` 只返回构建器步骤，均不产出可继续链式的子查询表达式） |
| NumericOperationsIntegrationTest.java | 5 条 `*LiteralValueInSelect`（`shouldAdd/Subtract/Multiply/Divide/ModuloLiteralValueInSelect`）+ `shouldPerformArithmeticWithExpressionInSelect` + 5 条 `*WithLiteralInWhereClause`（`Arithmetic/Subtraction/Multiplication/Division/Modulo`） | 删除 | 上游先用 `EntityRoot.<Employee>of().literal(5000.0)` 造右值表达式再 `.add(bonus)`；3.x `NumberArithmetic.java:10-42` 右值只能是字面值 `U`，无 `literal(...)`（api 里 grep 不到），**表达式右值不可表达**。把 `literal` 折成字面值后与已移植的 `shouldAddValueInSelect` / `shouldUseArithmeticInWhereClause` 等逐行重复，故不重复移植 |
| NumericOperationsIntegrationTest.java | `shouldUseArithmeticWithAggregation`、`shouldUseArithmeticWithGroupBy` | 删除 | 上游 `select(Path.of(Employee::getSalary).multiply(1.1).sum())` / `select(Path.of(Employee::getDepartmentId), Path.of(Employee::getSalary).multiply(1.1).avg())`；3.x `NumberValue.java:12-17` 只 extends Expression / Comparison / Range / Between / NumberArithmetic，且 `NumberValue.java:8` 明写「不接聚合：`sum(salary * 12)` 这种『聚合套算术』本次不提供」——`NumberValue` 上没有 `sum()` / `avg()` |
| NumericOperationsIntegrationTest.java | 5 条 `shouldHandleConditional{Add,Subtract,Multiply,Divide,Mod}WithNull` | 删除 | 上游用 `addIfNotNull / subtractIfNotNull / multiplyIfNotNull / divideIfNotNull / modIfNotNull`（`*WithNull` 即 null 短路）；3.x 无 `*IfNotNull` 变体，同本表第 3 行依据 |

## 待裁决

无。

## 影响范围

只改 `StringOperatorDefaultMethodsIntegrationTest.java`、`ExpressionBuilderDefaultMethodsIntegrationTest.java`、
`AndOrPredicateIntegrationTest.java`、`WhereImplIntegrationTest.java` 四个文件（`NumericOperationsIntegrationTest.java`
零改动）并新增本记档；未动生产代码、`config/`、`entity/`、其他批次文件与 `README.md` / `REPORT.md`。
