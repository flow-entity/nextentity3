# port-c 移植记档（排序 / 字符串 / 数值 / 分页）

口径：[API-MAPPING.md](API-MAPPING.md)。3.x api 事实来源：`nextentity-api/src/main/java/io/github/nextentity/api/`。
编译验证：`.\tmp\scripts\legacy-javac.ps1 -Name port-c` → `tmp/legacy/javac-port-c.log`，**我这 11 个文件 0 报错**。

结果：保留 **149** 条用例，删除 **181** 条，待裁决 **4** 条（均因 `api.model.Slice` 没有 `offset()/limit()` 访问器）。

## 汇总

| 文件 | 保留 | 删除 | 待裁决 |
|---|---|---|---|
| OrderByStepMultipleParametersIntegrationTest.java | 1 | 10 | 0 |
| OrderOperatorDefaultMethodsIntegrationTest.java | 7 | 1 | 1 |
| OrderOperatorUncoveredMethodsIntegrationTest.java | 2 | 6 | 0 |
| SimpleExpressionDefaultMethodsIntegrationTest.java | 41 | 5 | 0 |
| SimpleExpressionSortOrderIntegrationTest.java | 14 | 2 | 0 |
| StringExpressionDefaultMethodsIntegrationTest.java | 6 | 57 | 0 |
| StringOperatorDefaultMethodsIntegrationTest.java | 5 | 29 | 0 |
| StringOperationsIntegrationTest.java | 19 | 33 | 0 |
| NumericOperationsIntegrationTest.java | 23 | 38 | 0 |
| SlicePaginationIntegrationTest.java | 11 | 0 | 2 |
| StreamQueryIntegrationTest.java | 20 | 0 | 1 |

## 删除明细（按能力归并）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| OrderByStepMultipleParametersIntegrationTest.java | shouldOrderByTwoOrders / shouldOrderByTwoAscendingOrders / shouldOrderByThreeOrders / shouldOrderByTwoPaths / shouldOrderByTwoPathsDescending / shouldOrderByThreePaths / shouldOrderByThreePathsDescending / shouldCombineMultipleOrdersWithWhere / shouldCountWithMultipleOrders / shouldLimitWithMultiplePaths | 删除 | 3.x 无多参数 `orderBy` 重载：`api/OrderByStep.java` 只有 `orderBy(PathRef)` 与 `orderBy(Path)` 两个单列重载；`api/OrderOperator.java` 只有 `asc()/desc()` |
| OrderByStepMultipleParametersIntegrationTest.java | shouldOrderBySingleOrder | 移植 | `orderBy(Order)` → `orderBy(Employee::getName).asc()`（API-MAPPING 映射表「`Order.ASC` 作为第二参 → `.asc()`」） |
| OrderOperatorDefaultMethodsIntegrationTest.java | shouldConvertToSubqueryWithoutExplicitSortOrder | 删除 | 3.x 无 `toSubQuery()`（`api/Collector.java` 只有 6 个方法，无子查询） |
| OrderOperatorDefaultMethodsIntegrationTest.java | 其余 7 条 | 移植 | `OrderOperator` 在 2.2.3 上挂了 `Collector` 的 default 方法（隐式 asc）；3.x `api/OrderOperator.java` 只有 `asc()/desc()`，故显式补 `.asc()`，断言原样保留；`list(int,int)` → `slice(o,l).data()`（映射表行 47） |
| OrderOperatorUncoveredMethodsIntegrationTest.java | shouldOrderByCollectionWithoutExplicitSortOrder / shouldOrderByCollectionWithMultiplePaths / shouldOrderByCollectionReturnOrderOperatorForChaining / shouldOrderByEmptyListOfOrdersWork / shouldChainOrderByCollectionWithLimit / shouldOrderOperatorMethodsDelegateToAscCorrectly | 删除 | 3.x 无 `orderBy(Collection)` 重载（`api/OrderByStep.java` 仅 2 个单列重载），也无"未指定方向即 asc"的默认语义 |
| OrderOperatorUncoveredMethodsIntegrationTest.java | shouldSliceWithOffsetAndLimitWithoutExplicitSortOrder / shouldSliceWithWhereClause | 移植 | 补 `.asc()` 后原样保留 |
| SimpleExpressionDefaultMethodsIntegrationTest.java | shouldFilterWithNotBetweenValues / shouldFilterWithNotBetweenValuesOnId / shouldFilterWithNotBetweenExpressionAndValue / shouldFilterWithNotBetweenValueAndExpression | 删除 | 3.x `api/Between.java` 只有 `between(from,to)`，无 `notBetween` |
| SimpleExpressionDefaultMethodsIntegrationTest.java | shouldUseNotOnComparisonPredicate | 删除 | 3.x `api/Predicate.java` 只有 `and/or`，无 `not()` |
| SimpleExpressionDefaultMethodsIntegrationTest.java | 其余 41 条 | 移植 | `ge/gt/le/lt/between` 全在 `api/Range.java` + `api/Between.java`；`between(Expression,值)` 形态 2.2.3 有、3.x 无，但本文件对应用例已随 `notBetween` 族一并删除 |
| SimpleExpressionSortOrderIntegrationTest.java | shouldUseOrderByWithTwoOrders / shouldUseOrderByWithThreeOrders | 删除 | 多参数 `orderBy` 重载不存在 |
| SimpleExpressionSortOrderIntegrationTest.java | 其余 14 条 | 移植 | `Order<Employee> v = Path.of(X).asc()` + `orderBy(v)` → `orderBy(X).asc()` |
| StringExpressionDefaultMethodsIntegrationTest.java | `startsWith*` / `endsWith*` / `notStartsWith*` / `notEndsWith*` / `notContains*` / `lower()` / `notLike*` 全族（57 条） | 删除 | 3.x `api/StringOps.java` 只有 `contains / containsIfNotEmpty / containsIfNotNull / likeIfNotNull / eqIfNotEmpty`；`api/Path.java` 不含 `StringOps`（字符串算子只在 `StringOperator` 上） |
| StringExpressionDefaultMethodsIntegrationTest.java | `PredicateAsQueryConditionTests`（`@Nested`） | 删除 | 该类里唯一内容是"用 `Path.of(...).contains(...)` 造谓词"，`api/Path.java` 无 `StringOps`，无对位；等价语义已由 `where(Employee::getName).contains(...)` 链在 StringOperations 用例覆盖 |
| StringExpressionDefaultMethodsIntegrationTest.java | shouldFilterWithContains / shouldFilterWithContainsIfNotNullNonNull / shouldSkipFilterWhenContainsIfNotNullNull / shouldFilterWithContainsIfNotEmptyNonEmpty / shouldSkipFilterWhenContainsIfNotEmptyEmpty / shouldCheckExistWithStringExpressionCondition | 移植 | `contains*` 在 `api/StringOps.java` |
| StringOperatorDefaultMethodsIntegrationTest.java | `startsWithIfNotNull*` / `startsWithIfNotEmpty*` / `endsWithIfNotNull*` / `endsWithIfNotEmpty*` / `notStartsWith*` / `notEndsWith*` / `notContains*`（29 条） | 删除 | 同上一行：`api/StringOps.java` 无这些方法 |
| StringOperatorDefaultMethodsIntegrationTest.java | 5 条 `contains*` 用例 | 移植 | `contains / containsIfNotEmpty / containsIfNotNull` 均在 `api/StringOps.java` |
| StringOperationsIntegrationTest.java | `startsWith*` / `endsWith*` / `notLike` / `notStartsWith` / `notContains*` / `lower()` / `length()` / `likeIfNotEmpty` 全族（33 条） | 删除 | 同 `api/StringOps.java`；`likeIfNotEmpty` 也没有对位（只有 `likeIfNotNull`） |
| StringOperationsIntegrationTest.java | 19 条（`like*` / `contains*` / 排序 / 投影 / distinct） | 移植 | `like` 无裸重载，一律用 `likeIfNotNull`（映射表行 49） |
| NumericOperationsIntegrationTest.java | `add/subtract/multiply/divide/mod`（含 `*IfNotNull`）、`avg()`、`min()`、`EntityRoot.literal` 全族（38 条） | 删除 | 3.x `api/NumberPath.java` 只有 `sum()`；`api/Aggregates.java` 只有 `count/countDistinct/max`，无 `avg/min`；无算术算子、无 `EntityRoot` |
| NumericOperationsIntegrationTest.java | 23 条（比较 / between / in / notIn / 排序 / select / sum / max / count / exists） | 移植 | 均在 `api/Range.java`、`api/Membership.java`、`api/Between.java`、`api/Aggregates.java`、`api/NumberPath.java` |
| SlicePaginationIntegrationTest.java | shouldOffsetResults | 移植 | `list(offset, limit)` → `slice(offset, limit).data()`（映射表行 47） |
| StreamQueryIntegrationTest.java | 20 条（list / slice / first / single / count / exists） | 移植 | 全在 `api/Collector.java` + `api/SliceStep.java` |

## 待裁决（改完能编译，但怀疑与 2.2.3 断言不等价）

| 文件 | 用例 | 判定 | 依据 |
|---|---|---|---|
| SlicePaginationIntegrationTest.java | shouldSliceResults | 待裁决 | 删掉 `slice.offset()==0`、`slice.limit()==5` 两条断言：3.x `api/model/Slice.java` 只有 `data()/total()/to()`，无 `offset()/limit()`。其余断言（`hasSize(5)`、`total()==12`）保留 |
| SlicePaginationIntegrationTest.java | shouldSliceWithOffset | 待裁决 | 同上，删掉 `slice.offset()==5`；保留 `hasSize(5)` 与"首页 id 等于全量第 6 条"的断言 |
| OrderOperatorDefaultMethodsIntegrationTest.java | shouldSliceWithOffset | 待裁决 | 同上，删掉 `slice.offset()==2`；保留 `hasSize(5)` |
| StreamQueryIntegrationTest.java | shouldSliceWithOffset | 待裁决 | 同上，删掉 `slice.offset()==5`；保留 `hasSize(5)` |

> 四条待裁决都是同一个缺口：`Slice` 不再暴露 `offset()/limit()`。若要恢复，需要 api 加访问器（属 api 面变更，不在本批权限内）。

