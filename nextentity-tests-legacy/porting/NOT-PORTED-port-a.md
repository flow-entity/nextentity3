# NOT-PORTED — port-a（谓词 / 条件 / 聚合 / 关联）

批次：port-a（11 个文件）。编译验证：`.\tmp\scripts\legacy-javac.ps1 -Name port-a` → `tmp/legacy/javac-port-a.log`，
日志中**本批 11 个文件 0 报错**（其余报错全部属于其他 agent 的文件）。

口径：`tmp/legacy/API-MAPPING.md`。判定只有 `移植` / `删除` / `待裁决` 三种。
事实来源：`nextentity-api/src/main/java/io/github/nextentity/api/`。

> 脚本注意：模块整体编译通过时 `$raw` 为 `$null`，`legacy-javac.ps1:24` 的 `WriteAllLines` 会抛
> `Value cannot be null`，日志不会被刷新（看起来像「报错还在」）。本次最终结论用等价命令直接跑
> `javac -d tmp\legacy\out-port-a -cp <api/core/jpa target/classes;deps.txt> -proc:none -nowarn -Xmaxerrs 2000 @<全部测试源码>`
> 复核：**exit 0、0 行诊断**，且 `tmp/legacy/out-port-a/io/github/nextentity/integration/` 下本批 11 个类
> （含 `HavingClauseIntegrationTest` 之外的 10 个 + 嵌套类）全部产出 `.class`。

## 1. 文件级处置

| 文件 | 判定 | 依据 |
|---|---|---|
| `HavingClauseIntegrationTest.java` | 删除（整类 22 条） | 22 条用例**全部**调用 `.having(...)`；3.x `api` 无 `having`（`GroupByStep.java` 只有 `groupBy(PathRef)`，`BaseWhereStep` 上没有任何 having 入口），`Operation` 里也没有对应常量 → 整类都是已删能力 |
| `WhereImplIntegrationTest.java` | 移植（16 条）+ 删除（7 条） | 见 §2；`WhereImpl` / `ExpressionBuilder` 这套 2.2.3 内部实现类在 3.x 已不存在，改用 `api` 的 `PathOperator` / `StringOperator` / `orderBy(...).asc()` |
| `AndOrPredicateIntegrationTest.java` | 移植（43 条）+ 删除（16 条） | 见 §2；`core.util.Predicates` 静态工具类已删，`and/or` 全部折成 `Predicate` 实例链 |
| `NestedPathIntegrationTest.java` | 移植（16 条）+ 删除（2 条） | 见 §2；嵌套路径条件本身 3.x 支持（`api/EntityPath.get` + `where(Path)`），`fetch` 无对位 |
| `AggregateFunctionsIntegrationTest.java` | 移植（13 条）+ 删除（6 条） | 见 §2；`api` 只有 `count/countDistinct/max`（`Aggregates`）+ `sum`（`NumberPath`），无 `avg`/`min` |
| `SubqueryIntegrationTest.java` | 移植（8 条）+ 删除（6 条） | 见 §2；3.x 无 `toSubQuery`，且部分用例依赖 `avg`/`min` |
| `EdgeCasesIntegrationTest.java` | 移植（26 条，2 条改了调用/类型形态） | 见 §2；`NextEntityException` 已删（`core` 无任何 `*Exception` 类） |
| `ErrorHandlingIntegrationTest.java` | 移植（16 条）+ 删除（1 条） | 见 §2 |
| `ExceptionPropagationIntegrationTest.java` | 移植（10 条，1 条改了异常类型） | 见 §2 |
| `JoinOperationsIntegrationTest.java` | 移植（10 条，3 条删掉 `.fetch(...)`） | 见 §2；另删掉一行跨批次 import（见 §3 注） |
| `ComplexPredicateIntegrationTest.java` | 移植（15 条）+ 删除（3 条） | 见 §2；只删 `Predicate.not()` 系用例 |

## 2. 用例级处置

### 2.1 删除

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| `AndOrPredicateIntegrationTest.java` | 类 `NotWithAndOrTests`（`shouldCombineNotWithAnd` / `shouldCombineNotWithOr` / `shouldHandleNotOfAndPredicate` / `shouldHandleNotOfOrPredicate` / `shouldHandleChainedNotOperations` / `shouldHandleComplexNotWithAndOr`，6 条） | 删除 | 3.x `api/Predicate.java` 只有 `and(Expression)` / `or(Expression)` / `or(PathRef)`，**没有 `not()`**；`core.util.Predicates` 静态工具类也随 `core.util` 一起删了（API-MAPPING 映射表：「`Predicates.not(p)` → 没有对位 → 删该用例」） |
| `AndOrPredicateIntegrationTest.java` | `shouldCombineStringConditionsWithAnd` / `shouldCombineStringConditionsWithOr` | 删除 | 两条都靠 `Path.of(Employee::getName).startsWith(...)`；`api/StringOps.java` 只有 `contains` / `containsIfNotEmpty` / `containsIfNotNull` / `likeIfNotNull` / `eqIfNotEmpty`，无 `startsWith` |
| `AndOrPredicateIntegrationTest.java` | `shouldHandleComplexPredicateWithAllOperators` | 删除 | 条件里含 `not(Path.of(...).in(1L,2L))`，同上无 `not()` |
| `AndOrPredicateIntegrationTest.java` | `shouldChainAndNumberRefWithArithmeticOperations` | 删除 | `.and(Employee::getSalary).add(10000.0)`：3.x 无算术算子（`api/Operation.java` 无 `ADD/SUBTRACT/...`，`Path`/`NumberPath` 无 `add`），`and(PathRef)` 也不存在 |
| `AndOrPredicateIntegrationTest.java` | `shouldChainAndStringRefReturningStringOperator` | 删除 | `.and(Employee::getName)` 无对位（`Predicate` 只有 `or(PathRef)`，没有 `and(PathRef)`），且断言依赖 `startsWith` |
| `AndOrPredicateIntegrationTest.java` | `shouldChainAndStringRefWithStringFunctions` | 删除 | 同上，另需 `lower()`（`StringOps` 无） |
| `AndOrPredicateIntegrationTest.java` | `shouldChainOrStringRefReturningStringOperator` | 删除 | `or(Employee::getName)` 会落到 `or(PathRef)` → 返回 `PathOperator`，它**不继承 `StringOps`**，`.contains(...)` 无处可取；「OR 里带字符串算子」在 3.x 无法表达（`StringOperator` 的 `B` 是 `BaseWhereStep`，只能继续 AND） |
| `AndOrPredicateIntegrationTest.java` | `shouldChainAfterPredicateNot` / `shouldChainOrAfterPredicateNot` / `shouldHandleComplexChainWithNotAndOr` | 删除 | 三条都链式调用 `.not()`，无对位 |
| `ComplexPredicateIntegrationTest.java` | `shouldFilterWithNot` / `shouldFilterWithNotAndOr` / `shouldFilterWithPredicateNegation` | 删除 | `isActive.not()` / `dept1Or2.not()` / `activeAndStatus.not()`：`api/Predicate.java` 无 `not()`。**不改成 `ne(...)`**——那是自己发明映射，且 `NOT(a OR b)` / `NOT(a AND b)` 本就没有对位 |
| `NestedPathIntegrationTest.java` | 类 `NestedPathSelectTests`（`shouldSelectDepartmentName_FromEmployees` / `shouldSelectDepartmentLocation_FromEmployees`） | 删除 | 两条都是 `selectDistinct(EntityPath.of(...).get(...))`；`api/SelectStep.java` 的 `selectDistinct` 只收 `PathRef`，无 `selectDistinct(Path)` 重载。改成 `select(Path)` + Java 侧 `distinct()` 属于把去重从 SQL 挪走，不能顶替 |
| `AggregateFunctionsIntegrationTest.java` | `shouldCalculateAverageSalary` / `shouldGroupByWithAvg` | 删除 | 3.x 无 `avg()`（`api/Aggregates.java` 只有 `count`/`countDistinct`/`max`，`NumberPath` 只多一个 `sum`） |
| `AggregateFunctionsIntegrationTest.java` | `shouldFindMinSalary` / `shouldFindMinId` | 删除 | 3.x 无 `min()`（同上） |
| `AggregateFunctionsIntegrationTest.java` | `shouldReturnMultipleAggregations` | 删除 | 用 `select(min(), max())` 取 `Tuple`：`min` 不存在，且 `api/model` 只有 `Tuple2`、没有无编号 `Tuple` |
| `AggregateFunctionsIntegrationTest.java` | `shouldGroupByMultipleColumns` | 删除 | 3 列 `select(...)` + `groupBy(a, b)` 多参重载：`api/SelectStep.java` 无 3 列投影，`api/GroupByStep.java` 只有单列 `groupBy(PathRef)` |
| `ErrorHandlingIntegrationTest.java` | `shouldHandleAggregationQueries` | 删除 | 断言 `avgSalary`（`select(Path.of(Employee::getSalary).avg())`），3.x 无 `avg()` |
| `SubqueryIntegrationTest.java` | `shouldCreateSubqueryFromQuery` / `shouldSliceSubqueryResults` | 删除 | `.toSubQuery()` 在 3.x 无对位（`api` 无 `toSubQuery` / `asSubQuery`，`Operation` 也无子查询常量） |
| `SubqueryIntegrationTest.java` | `shouldCompareWithMinExpression` / `shouldAggregateWithFilter` | 删除 | 依赖 `Path.of(Employee::getSalary).min()` |
| `SubqueryIntegrationTest.java` | `shouldCompareWithAverageExpression` / `shouldCombineMultipleAggregations` | 删除 | 依赖 `avg()`（后者还同时用 `min()`） |
| `WhereImplIntegrationTest.java` | `shouldSupportStartsWithOperation_WithStringPath` | 删除 | `.where(namePath).startsWith("J")`：`api/StringOps` 无 `startsWith` |
| `WhereImplIntegrationTest.java` | `shouldGroupBy_TypedExpressionWithAggregate` | 删除 | `select(activePath, Path.of(Employee::getSalary).avg())`，无 `avg()` |
| `WhereImplIntegrationTest.java` | 类 `GroupByExpressionListTests`（`shouldGroupBy_ListOfTypedExpressions` / `shouldGroupByList_WithHavingClause`） | 删除 | 两条都是 3 列 `select` + `groupBy(a, b)`；后者还用 `having(...)` |
| `WhereImplIntegrationTest.java` | 类 `SubQueryGetRootTests`（`shouldCreateSubquery_FromQuery` / `shouldCreateCountExpression_FromSubquery` / `shouldCreateSliceExpression_FromSubquery`） | 删除 | 三条都走 `.toSubQuery()` |
| `HavingClauseIntegrationTest.java` | 整类 22 条（`shouldFilterGroupsWithHavingCountGt` … `shouldUseHavingOnDepartmentQuery`） | 删除 | 见 §1：3.x 无 `having` |

### 2.2 移植（改了调用形态，断言原样）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| `AndOrPredicateIntegrationTest.java` | `and(...)` / `or(...)` 静态工具 → `Predicate` 实例链，共 30 条：`shouldCombineWithAndUsingPredicates`、`shouldCombineMultipleWithAnd`、`shouldCombineNumberRangesWithAnd`、`shouldCombineDepartmentConditionsWithAnd`、`shouldHandleDeepAndNesting`、`shouldCombineWithOrUsingPredicates`、`shouldCombineMultipleWithOr`、`shouldCombineDifferentFieldsWithOr`、`shouldCombineNumberRangesWithOr`、`shouldCombineDepartmentConditionsWithOr`、`shouldHandleDeepOrNesting`、`shouldHandleOrNestedInAnd`、`shouldHandleAndNestedInOr`、`shouldHandleComplexMixedAndOrPattern`、`shouldHandleTripleNestedCombination`、`shouldHandleComplexBusinessRulePattern`、`shouldCombineWhereWithAndOrPredicates`、`shouldHandleFullComplexPatternLikeTestAndOr2`、`shouldHandleAndWithNullCheck`、`shouldHandleOrWithNullCheck`、`shouldHandleAndWithBetween`、`shouldHandleOrWithInClause`、`shouldChainAndPathReturningPathOperator`、`shouldChainMultipleAndPathCalls`、`shouldChainAndNumberRefReturningNumberOperator`、`shouldHandleMixedConjunctionAndDisjunctionChains`、`shouldHandleChainedAndOrPattern`、`shouldHandleChainedOrAndPattern`、`shouldConvertConjunctionToPredicate`、`shouldConvertDisjunctionToPredicate` | 移植 | `api/Predicate.java` 有实例方法 `and(Expression)` / `or(Expression)` / `or(PathRef)`；`n` 元 `and(a,b,c)` 折成 `a.and(b).and(c)`（API-MAPPING 映射表「`Predicates.and(p1,p2)` → `p1.and(p2)`」）。`.toPredicate()` 直接去掉——3.x `and/or` 返回的就是 `Predicate`。`.and(Employee::getX)` 改写为 `.and(Path.of(Employee::getX).op(...))`（`Predicate` 没有 `and(PathRef)`）。断言（条数/字段值）逐字未动 |
| `AndOrPredicateIntegrationTest.java` | `shouldHandleAndWithLike` | 移植 | `Path.of(Employee::getEmail).likeIfNotNull(...)` 无对位（`StringOps` 只在 `StringOperator`/`StringPath` 上），改成 `where(Employee::getEmail).likeIfNotNull("%@example.com").where(Employee::getActive).eq(true)`——3.x 多次 `where` 之间是 AND（`api/Whereable.java`），与原 `and(...)` 等价 |
| `AndOrPredicateIntegrationTest.java` | `shouldCombinePredicatesWithIterable` / `shouldCombinePredicatesWithIterableForOr` / `shouldChainAndIterableForFurtherOperations` / `shouldChainOrIterableForFurtherOperations` | 移植 | `and(Iterable)` / `or(Iterable)` 重载不存在；按映射表「折叠成实例链」改成 `for` 循环折叠（`combined = combined.and(next)`），断言原样 |
| `AndOrPredicateIntegrationTest.java` | `shouldCombineWithVarargsArrayUsingPredicateAnd` / `shouldCombineWithVarargsArrayUsingPredicateOr` | 移植 | `and(Expression...)` / `or(Expression...)` 变长重载不存在，同样折叠成循环 |
| `AndOrPredicateIntegrationTest.java` | `shouldCombineUsingPredicateAndMethod`、`shouldCombineUsingPredicateOrMethod`、`shouldChainOrPathReturningPathOperator`、`shouldChainMultipleOrPathCalls`、`shouldChainOrNumberRefReturningNumberOperator`、`shouldHandleComplexFluentChainLikeTestPredicateAssembler` | 移植 | 原本就用实例 `and/or`（或 `or(PathRef)`），一行未改即可编译 |
| `ComplexPredicateIntegrationTest.java` | 其余 15 条（`shouldFilterWithSimpleAnd` … `shouldFilterWithTripleAnd`） | 移植 | 全部一行未改，直接编译通过（`where(PathRef)` / `where(Predicate)` / `likeIfNotNull` 都在 `api`） |
| `NestedPathIntegrationTest.java` | 16 条：`shouldFilterEmployees_ByDepartmentName{Engineering,Marketing,Sales,HR,Finance}`、`shouldReturnEmpty_ForNonExistentDepartment`、`shouldFilterEmployees_ByDepartmentLocation{BuildingA,BuildingB}`、`shouldFilterEmployees_ByDepartmentBudget{GreaterThan,Between}`、`shouldFilterEmployees_By{Active,Inactive}Department`、`shouldCombineNestedPath_WithEmployeeActive`、`shouldCombine_MultipleNestedPathConditions`、`shouldCombineNestedPath_WithSalaryCondition`、`shouldOrderBySalary_AfterNestedPathFilter` | 移植 | `where(Employee::getDepartment).get(...)` 在 3.x 只能写成 `where(EntityPath.of(Employee::getDepartment).get(...))`：`api/EntityPath.java` 有 `get(PathRef)` / `get(StringRef)`，`api/Whereable.java` 有 `where(Path)` / `where(StringPath)`。同时删掉 `.fetch(Employee::getDepartment)`（3.x 无 `fetch` 全族）。断言原样 |
| `NestedPathIntegrationTest.java` | 3 条 Boolean 嵌套路径（`shouldFilterEmployees_ByActiveDepartment`、`shouldFilterEmployees_ByInactiveDepartment`、`shouldCombine_MultipleNestedPathConditions` 的 active 那半句） | 移植 | `where(Path<Employee,Boolean>)` 与 `where(Expression<Employee,Boolean>)` 两个重载在 `Path<*,Boolean>` 实参上**二义**（javac 报「对 where 的引用不明确」），因此把算子挂在路径上再交给 `where`：`where(EntityPath.of(...).get(Department::getActive).eq(true))`——`Path` 继承 `Comparison`，`eq` 返回 `Predicate<T>`，语义与「先 where 路径再 eq」相同 |
| `JoinOperationsIntegrationTest.java` | `shouldFetchEmployeeWithDepartment` / `shouldFilterEmployeesByDepartmentName` / `shouldFetchWithMultipleConditions` | 移植 | 只删掉 `.fetch(Employee::getDepartment)` 一行，`where` / `orderBy` / 断言逐字未动（见 §3 待裁决） |
| `JoinOperationsIntegrationTest.java` | 其余 7 条（`shouldQueryDepartments`、`shouldCountEmployeesPerDepartment`、`shouldCalculateSalaryStatsPerDepartment`、`shouldOrderByDepartmentAndSalary`、`shouldGetDistinctDepartmentIds`、`shouldFilterByStatusAndDepartment`、`shouldQueryDepartmentBudget`） | 移植 | 一行未改，直接编译通过（`selectDistinct(PathRef)` / `max()` / 累积 `orderBy` 都在 `api`） |
| `AggregateFunctionsIntegrationTest.java` | 13 条（`shouldCountEmployees`、`shouldCountWithExpression`、`shouldCountDistinct`、`shouldSumSalaries`、`shouldFindMaxSalary`、`shouldGroupByDepartmentId`、`shouldGroupByWithCount`、`shouldGroupByWithSum`、`shouldAggregateWithWhereCondition`、`shouldFindMaxId`、`shouldCountActiveEmployees`、`shouldSumSalariesForDepartment`、`shouldGroupByStatus`） | 移植 | 一行未改：`count()` / `countDistinct()` / `max()` / `sum()` / `groupBy(PathRef)` / `select(Expression,Expression)→Tuple2` 全在 `api`。类注释里删掉已不存在的 AVG/MIN 两项 |
| `SubqueryIntegrationTest.java` | 8 条（`shouldCountUsingSubquery`、`shouldCompareWithMaxExpression`、`shouldHandleTwoStepQuery`、`shouldUsePrecomputedValuesInQuery`、`shouldFilterWithTwoStepForStatus`、`shouldFilterWithTwoStepForSalary`、`shouldFilterWithNotInPrecomputed`、`shouldQueryByIdsWithOrder`） | 移植 | 一行未改，直接编译通过（这些「子查询」用例其实是两步 `in(list)` 写法） |
| `WhereImplIntegrationTest.java` | `shouldReturnPathOperator_WithPathExpression` / `shouldReturnNumberOperator_WithNumberPath` | 移植 | 局部变量类型 `ExpressionBuilder.PathOperator` / `.NumberOperator` → `api.PathOperator<Employee, X, ?>`（`ExpressionBuilder` 已删） |
| `WhereImplIntegrationTest.java` | `shouldReturnStringOperator_WithStringPath` / `shouldSupportLikeOperation_WithStringPath` / `shouldSupportContainsOperation_WithStringPath` | 移植 | 3.x 没有独立的 `StringPath` 工厂（`Path.of` 返回 `Path`），`StringPath` 只能由 `EntityPath.get(StringRef)` 产生；因此去掉中间变量，直接 `where(Employee::getName)` → `api.StringOperator`（`where(PathRef.StringRef)` 重载），`likeIfNotNull` / `contains` 都在 `StringOps` |
| `WhereImplIntegrationTest.java` | `shouldGroupBy_SingleTypedExpression` | 移植 | `groupByExpr(Path)` → `groupBy(Employee::getDepartmentId)`（`api/GroupByStep.java` 只有 `groupBy(PathRef)`） |
| `WhereImplIntegrationTest.java` | 类 `OrderByOrderListTests` 4 条（`shouldOrderBy_ListOfOrderExpressions`、`shouldOrderBy_SingleOrderInList`、`shouldCombineOrderByList_WithWhereCondition`、`shouldOrderBy_MultipleOrderExpressions`） | 移植 | `Order` 枚举 / `OrderImpl` / `SortOrder` / `orderBy(List<Order>)` 全删；改用累积 `orderBy(PathRef).asc()/desc()`（`api/OrderByStep.java` + `api/OrderOperator.java`），断言与排序校验逻辑逐字未动 |
| `EdgeCasesIntegrationTest.java` | `shouldThrowExceptionForGetSingleWithMultipleResults` | 移植 | `NextEntityException` 在 3.x 不存在（`core` 里没有任何自定义异常类），`single()` 的契约是 `IllegalStateException`（`core/builder/QueryBuilder.java:186`，`api/Collector.java` 的 Javadoc 同样写明），故断言类型改成 `IllegalStateException`，**仍然断言"抛异常"这一事实** |
| `ErrorHandlingIntegrationTest.java` | `shouldThrowExceptionWhenGetSingleFindsMultiple` | 移植 | 同上换成 `IllegalStateException`；`.hasMessageContaining("more than one")` 原样保留（3.x 消息 `found more than one result, expected one` 命中） |
| `ExceptionPropagationIntegrationTest.java` | `shouldThrowExceptionForGetSingleWithMultipleResults` | 移植 | 同上换成 `IllegalStateException` |
| `EdgeCasesIntegrationTest.java` | 其余 25 条 | 移植 | 一行未改（`insert` / `delete` / `in(emptyList)` / `isNull` / `slice(...).data()` 等都在 `api`） |

## 3. 待裁决

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| `EdgeCasesIntegrationTest.java` | `shouldHandleNegativeOffset` | 待裁决 | 按映射表 `.list(-1, 5)` → `.slice(-1, 5).data()`，断言仍是 `isNotNull()`。但 3.x 对负 offset 是**明确拒绝**：`api/SliceStep.java` 的 `@throws IllegalArgumentException offset 或 limit 为负数`，`core/spec/Window.java:18` 直接 `throw new IllegalArgumentException("offset 不能为负数")`；2.2.3 是「容忍（按 0 或返回全部）」。预期 B2 跑出来会红，届时应按 3.x 契约改为断言抛 `IllegalArgumentException` 或删除该用例——**这里没有替它改期望值** |
| `NestedPathIntegrationTest.java` | 上述 16 条（含 Boolean 嵌套路径的 3 条） | 待裁决 | `.fetch(Employee::getDepartment)` 删掉后，断言里的 `emp.getDepartment()` 依赖「查询后实体仍在同一持久化上下文里」才能惰性加载：`LegacyHarness` 只持有一个长生命周期 `EntityManager`，读路径（`JpaExecutor.list`）不开事务也不 `clear()`，理论上可用；但这是 3.x 行为差异点，留给 B2 实跑确认 |
| `JoinOperationsIntegrationTest.java` | `shouldFetchEmployeeWithDepartment` / `shouldFilterEmployeesByDepartmentName` | 待裁决 | 同上的惰性加载前提（`assertNotNull(emp.getDepartment())` 等断言原样保留） |

> §3 注（非待裁决，已处理）：`JoinOperationsIntegrationTest.java` 第 8 行原本 `import io.github.nextentity.integration.fast.FastIntegrationTestProvider;`，该包被 port-b 整包删除，且本文件里这行 import 未被使用 → 直接删掉该 import（不改任何断言）。

## 4. 统计

- 移植：**173 条**
  - `AndOrPredicateIntegrationTest` 43、`EdgeCasesIntegrationTest` 26、`NestedPathIntegrationTest` 16、`ErrorHandlingIntegrationTest` 16、`WhereImplIntegrationTest` 16、`ComplexPredicateIntegrationTest` 15、`AggregateFunctionsIntegrationTest` 13、`JoinOperationsIntegrationTest` 10、`ExceptionPropagationIntegrationTest` 10、`SubqueryIntegrationTest` 8
- 删除：**63 条用例 + 1 个文件**
  - 用例：`AndOrPredicateIntegrationTest` 16、`HavingClauseIntegrationTest` 22、`WhereImplIntegrationTest` 7、`AggregateFunctionsIntegrationTest` 6、`SubqueryIntegrationTest` 6、`ComplexPredicateIntegrationTest` 3、`NestedPathIntegrationTest` 2、`ErrorHandlingIntegrationTest` 1
  - 文件：`HavingClauseIntegrationTest.java`（整类 22 条全是 `having`）
- 待裁决：**3 组 / 19 条**（负 offset 1 条；`fetch` 删除后依赖会话内惰性加载 16+2 条）
- 未改动文件：`config/`、`entity/`、`dto/`、`config/fixtures/`、`pom.xml` 一律没碰（`pom.xml` 里 `nextentity-tests-legacy` 那行 module 是本次迁移开工前就有的）
