# NOT-PORTED — port-b（投影 / select / 分组 / fast 包）

> **本轮回填（2026-10-24，`7b8a71aa` 之后）**：`Tuple3`~`Tuple6`、`ProjectionMappers`（四~六列）、多列
> `distinct()`、`min`/`avg` 落地后，本批删掉的用例有 34 条移植回来：`SelectApiIntegrationTest` 22 条
> （三~六列投影与去重、`min`/`avg`、多表达式投影）、`EntityQueryIntegrationTest` 12 条（`avg`/`min`、
> `Tuple3~5`、多列去重、`not()`）、`TuplesIntegrationTest` 1 条（`Tuple3`）、
> `GroupByStepMultipleParametersIntegrationTest` 整类 6 条（多列 `groupBy` + `Tuple3`/`Tuple4~6`）。
> 仍然缺：`select(Class)` DTO/record/接口投影、7 列及以上投影、`select(Collection)`、`fetch`。总账见
> [`REPORT.md`](REPORT.md)。

批次：port-b。编译验证：`.\tmp\scripts\legacy-javac.ps1 -Name port-b` → `tmp/legacy/javac-port-b.log`，
日志中**本批 11 个文件 0 报错**（其余报错全部属于其他 agent 的文件）。

口径：`tmp/legacy/API-MAPPING.md`。判定只有 `移植` / `删除` / `待裁决` 三种。

## 1. 文件级处置

| 文件 | 判定 | 依据 |
|---|---|---|
| `fast/FastApplicationContexts.java` | 删除 | 2.2.3 为省启动时间自建的 Spring 单例上下文；3.x `config/IntegrationTestProvider` 已内置「JVM 内一次启动 + 交付前 reset」，整包无对位（DESIGN §5 测试策略） |
| `fast/FastIntegrationTestProvider.java` | 删除 | 同上：它只是从 Spring 容器里捞 `IntegrationTestContext`；3.x provider 直接返回 harness 上下文 |
| `fast/FastProjectionQueryIntegrationTest.java` | 删除 | 3 个用例全是 `select(EmployeeWithLazy*Department.class)` DTO 投影（`api/SelectStep.java` 无 `select(Class)`）；且这 3 条在 `ProjectionQueryIntegrationTest` 末尾已有一模一样的副本 → **独有用例 0 条，无可挪** |
| `ProjectionQueryIntegrationTest.java` | 删除（整类 37 条） | 全类都是 `select(X.class)` 的 JavaBean / 接口 / record 投影；`api/SelectStep.java` 只有 4 个重载（`PathRef` / `Expression` / `Expression,Expression` / `selectDistinct(PathRef)`），无「投影成某个类」 |
| `EmbeddedProjectionIntegrationTest.java` | 删除（整类 15 条） | 同上：15 条全部依赖 `select(PersonAddressBasic.class)` 这类嵌入式 DTO/record/接口投影（含 `@EntityPath`）；3.x 无 DTO 投影，也就没有「嵌入字段拍平进 DTO」这条能力 |
| `GroupByStepMultipleParametersIntegrationTest.java` | 删除（整类 6 条） | 6 条全部用 `groupBy(a,b,c…)` 多参数重载 + `Tuple3~6` 多列投影（`api/GroupByStep.java` 只有单列 `groupBy(PathRef)`，多列需多次调用；投影 >2 列无对位），其中 1 条还用 `having(...)`（`api` 无 `having`） |
| `ProjectionJoinAnnotationTest.java` | 删除（整类 2 条） | 2 条都是 `select(OrderWithCustomer.class)` + `core.annotation.Join` 注解投影；`api` 无 `select(Class)`，`core.annotation.Join` 已不存在 |

## 2. 用例级处置

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| `SelectApiIntegrationTest.java` | `shouldSelectSinglePathName` / `...Salary` / `...WithWhere` / `shouldSelectDistinctSinglePath` | 移植 | `select(PathRef)` / `selectDistinct(PathRef)` 原样可用 |
| `SelectApiIntegrationTest.java` | `shouldSelectTwoPaths` / `shouldSelectTwoPathsWithCondition` | 移植 | `select(Expression, Expression)` → `Tuple2`，实参包 `Path.of(...)` |
| `SelectApiIntegrationTest.java` | `shouldSelectWithTypedExpressionMax` / `...Count` / `...Sum` | 移植 | `Aggregates.max()/count()`、`NumberPath.sum()` 均在 `api` |
| `SelectApiIntegrationTest.java` | `shouldSelectWithWhereAndOrderBy` / `...Pagination` / `...Limit` / `shouldHandleSelectWithNullValues` / `shouldReturnEmptyListForNoMatches` / `shouldSelectSingleWithGetSingle` / `shouldSelectFirst` | 移植 | 只改调用形态，断言原样 |
| `SelectApiIntegrationTest.java` | `shouldSelectDistinctTwoPaths` | 删除 | 3.x `selectDistinct` 只有单列重载（`api/SelectStep.java`），无多列 distinct |
| `SelectApiIntegrationTest.java` | `shouldSelectThreePaths` … `shouldSelectTenPaths`、`shouldSelectDistinctThreePaths` … `shouldSelectDistinctTenPaths`（16 条） | 删除 | `api/model` 只有 `Tuple2`；`SelectStep` 无 3~10 列 `select` |
| `SelectApiIntegrationTest.java` | `shouldSelectWithCollectionOfPaths` / `shouldSelectDistinctWithCollectionOfPaths` | 删除 | `SelectStep` 无 `select(Collection)` / `selectDistinct(Collection)` 重载 |
| `SelectApiIntegrationTest.java` | `shouldSelectWithTypedExpressionMin` / `...Avg` | 删除 | `api/Aggregates.java` 只有 `count/countDistinct/max`，`NumberPath` 只有 `sum`；`min`/`avg` 明确不在 3.x |
| `SelectApiIntegrationTest.java` | `shouldSelectDistinctWithTypedExpression` | 删除 | `selectDistinct` 只接受 `PathRef`，不接受聚合 `Expression` |
| `SelectApiIntegrationTest.java` | `shouldSelectWithTwoTypedExpressions` … `shouldSelectWithTenTypedExpressions`（9 条） | 删除 | 多列表达式投影 >2 列无对位；其中首条还依赖已删的 `min()` |
| `SelectApiIntegrationTest.java` | `shouldSelectDistinctWithListOfTypedExpressions`、`shouldSelectWithListOfTypedExpressions`、`...Aggregate`、`shouldSelectDistinctWithListOfTypedExpressionsAggregate`、`shouldSelectDistinctWithListOfPathsAsTypedExpressions` | 删除 | 全部是 `select(List<Expression>)` / `selectDistinct(List)` 形态，`SelectStep` 无此重载 |
| `SelectApiIntegrationTest.java` | `shouldSelectDistinctWithTwoTypedExpressionsVarargs` … `...TenTypedExpressionsVarargs`（9 条） | 删除 | 多列 distinct 无对位 |
| `SelectApiIntegrationTest.java` | `shouldSelectWithProjectionTypeSameEntity` / `...SameEntityReturnsSame` / `shouldSelectDistinctWithProjectionType` | 删除 | `select(Class)` / `selectDistinct(Class)` 不存在；查实体本身就是 `query().list()` |
| `EntityQueryIntegrationTest.java` | 26 条（实体查询、`in`/`between`/`likeIfNotNull`/`isNotNull`、单列与 `Tuple2` 投影、`selectDistinct`、`count`/`sum`/`max`、`Predicate` 组合） | 移植 | 仅两处调用形态改动：`.list(offset,limit)` → `.slice(offset,limit).data()`；`Predicate.and(PathRef)` → `Predicate.and(Path.of(...).eq(...))`（`api/Predicate.java` 只有 `and(Expression)` / `or(Expression)` / `or(PathRef)`）。断言原样 |
| `EntityQueryIntegrationTest.java` | `select_WithAvg_ShouldReturnAverage` / `select_WithMin_ShouldReturnMin` | 删除 | 无 `avg`/`min` |
| `EntityQueryIntegrationTest.java` | `select_WithProjectionType_ShouldReturnProjectedResults` | 删除 | 无 `select(Class)` |
| `EntityQueryIntegrationTest.java` | `shouldSelectThreeFieldsIntoTuple3` / `...FourFieldsIntoTuple4` / `...FiveFieldsIntoTuple5` | 删除 | 无 `Tuple3+` |
| `EntityQueryIntegrationTest.java` | `shouldSelectDistinctTwoFields` / `...ThreeFields` | 删除 | 无多列 `selectDistinct` |
| `EntityQueryIntegrationTest.java` | `shouldSelectWithMultipleExpressions` | 删除 | 三列聚合投影（`avg`/`max`/`min`）既超两列又含已删聚合 |
| `EntityQueryIntegrationTest.java` | `shouldSelectWithArithmeticExpression` | 删除 | 3.x 路径无算术算子（`Path`/`NumberPath` 上没有 `add/subtract/...`） |
| `EntityQueryIntegrationTest.java` | `shouldUseNotOnPredicate` | 删除 | `api/Predicate.java` 无 `not()`；映射表已注明「`Predicates.not(p)` 没有对位 → 删」 |
| `TuplesIntegrationTest.java` | `shouldCreateTuple3FromQueryProjection` | 删除 | 无 `Tuple3` |
| `ExpressionBuilderDefaultMethodsIntegrationTest.java` | `shouldExecuteLikeIfNotNullWithNonEmptyValue` / `shouldSkipLikeIfNotNullWithNullValue` | 移植 | `StringOps.likeIfNotNull`（调用方自带通配符、`null` 短路）与 2.2.3 `likeIfNotEmpty` 在「非空值」与「null」两种输入下语义一致，断言原样 |
| `ExpressionBuilderDefaultMethodsIntegrationTest.java` | `shouldSkipLikeIfNotEmptyWithEmptyValue` | 删除 | 3.x 没有 `likeIfNotEmpty`；`likeIfNotNull("")` 会执行 `LIKE ''` 而不是短路，语义不同，不能顶替 |
| `ExpressionBuilderDefaultMethodsIntegrationTest.java` | `shouldUseSubstringWithOffsetForFiltering` / `shouldHandleSubstringWithLike` | 删除 | `StringOps` 无 `substring` / `length` |
| `ExpressionBuilderDefaultMethodsIntegrationTest.java` | `shouldExecute/skip{Add,Subtract,Multiply,Divide,Mod}IfNotNullWith*Value`（10 条）、`shouldChainMultipleNumericOperations`、`shouldHandleNullValuesInChainedOperations` | 删除 | 3.x `Path`/`NumberPath` 无 `addIfNotNull` 等算术算子（`api` 里没有 `NumberExpression`） |
| `ExpressionBuilderDefaultMethodsIntegrationTest.java` | `shouldCombineNumericOperationsWithLikePredicate` / `shouldUseAllNumericIfNotNullVariants` | 删除 | 同上，依赖已删算术算子 |

## 3. 待裁决

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| `JoinOperationsIntegrationTest.java`（**非本批文件**） | 第 8 行 `import io.github.nextentity.integration.fast.FastIntegrationTestProvider;` | 待裁决 | 我按指令删了 `fast/` 整包，这个 import 现在指向不存在的类（javac 报 `程序包 io.github.nextentity.integration.fast 不存在`）。该文件属其他批次，请 owner 把 import 与对应 `@ArgumentsSource` 换成 `IntegrationTestProvider` |

## 4. 统计

- 移植：**46 条**（`SelectApiIntegrationTest` 16 + `EntityQueryIntegrationTest` 26 + `TuplesIntegrationTest` 2 + `ExpressionBuilderDefaultMethodsIntegrationTest` 2）
- 删除：**138 条用例 + 7 个文件**
  - 用例：SelectApi 48、ProjectionQuery 37、EmbeddedProjection 15、ExpressionBuilder 15、EntityQuery 11、GroupByStep 6、Fast 3、ProjectionJoin 2、Tuples 1
  - 文件：`ProjectionQueryIntegrationTest`、`EmbeddedProjectionIntegrationTest`、`GroupByStepMultipleParametersIntegrationTest`、`ProjectionJoinAnnotationTest` 四个整类，加 `fast/` 下 3 个文件
- 从 `fast/` 挪进 `ProjectionQueryIntegrationTest` 的独有用例：**0 条**（3 条 lazy 投影用例与 `ProjectionQueryIntegrationTest` 末尾完全重复，且都因 DTO 投影整体删除）
- 待裁决：1 条（跨批次 import，见 §3）
