# NOT-PORTED — port-d 第三轮（ProjectionQuery / ProjectionJoin / SelectApi 投影类型）

> **本轮回填（2026-10-31，`select(Class)` 落地之后）**：3.x 的 `api/SelectStep.java:68-91` 把 DTO 投影定成
> 「record / JavaBean 扁平投影 + 整实体投影」三种可用形态，于是前两轮整类删掉的
> `ProjectionQueryIntegrationTest` 有 22 条能回来（JavaBean 6 + record 9 + distinct DTO 2 + 边界 5），
> `SelectApiIntegrationTest` 的 `select(Class)` 整实体投影 3 条也回来（2 条直接移植、1 条降级待裁决）。
> 仍然缺：接口/抽象类投影（运行期代理不提供）、`select(Collection)`/`select(List)`、七列以上投影、
> `@Join` 注解投影。总账见 [`REPORT.md`](REPORT.md)。

口径：[`NOT-PORTED-port-c.md`](NOT-PORTED-port-c.md) 同款。3.x api 事实来源：
`nextentity-api/src/main/java/io/github/nextentity/api/`（下文行号均指这里）。
编译验证：`bash tmp/scripts/compile-legacy.sh D:/source/java/nextentity3/tmp/legacy/out-d` → **EXIT=0**；
直跑：`bash tmp/scripts/run-legacy-tests.sh D:/source/java/nextentity3/tmp/legacy/out-d io.github.nextentity.integration.ProjectionQueryIntegrationTest io.github.nextentity.integration.SelectApiIntegrationTest`
→ `63 tests found / 63 successful / 0 failed`（H2，无容器）。
判定：`移植` = 只改调用形态、断言与期望值未动；`删除` = 3.x 能力已删；`待裁决` = 已改完能跑，但怀疑与 2.2.3 语义不等价。

结果：保留 **24** 条，删除 **43** 条（2 个整类 + 方法级），待裁决 **1** 条。

## 汇总

| 文件 | 保留 | 删除 | 待裁决 |
|---|---|---|---|
| ProjectionQueryIntegrationTest.java | 22 | 15 | 0 |
| ProjectionJoinAnnotationTest.java | 0 | 2 | 0 |
| fast/FastProjectionQueryIntegrationTest.java | 0 | 3 | 0 |
| SelectApiIntegrationTest.java | 2 | 23 | 1 |

`dto/` 未新增任何文件：接口投影一删，`EmployeeWithLazyDepartment` / `EmployeeWithLazyDepartmentNoId` /
`EmployeeWithLazyIDepartment` / `OrderWithCustomer` 就没有可移植用例用到；已有 DTO 一个没动。

## 删除明细（按能力归并）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| ProjectionQueryIntegrationTest.java | shouldProjectToInterfaceEmployeeInfo / shouldProjectToInterfaceNameAndSalary / shouldProjectToInterfaceDepartmentInfo / shouldProjectToInterfaceWithDefaultMethod / shouldProjectToInterfaceWithCondition / shouldProjectToInterfaceWithPagination / shouldGetSingleForInterface / shouldReturnEmptyFirstForInterfaceNoMatch / shouldSelectDistinctInterface / shouldHandleNullInInterface / shouldProjectToInterfaceWithLimit / shouldProjectInterfaceWithComplexCondition（12 条） | 删除 | 3.x 接口投影不提供：`api/SelectStep.java:75` 明写「接口与抽象类不支持：接口投影要靠运行期代理，本轮不提供」，`:88-89` 对接口/抽象类抛 `IllegalArgumentException` |
| ProjectionQueryIntegrationTest.java | shouldProjectWithLazyDepartment / shouldProjectWithLazyIDepartment / shouldProjectWithLazyDepartmentNoId（3 条） | 删除 | 目标是接口/嵌套接口投影（`EmployeeWithLazy*`、`DepartmentInfoLazy`），同 `api/SelectStep.java:75-77`；且 `select(Class)` 只覆盖实体**单层**属性，`:77` 明写 `department.name` 这类嵌套不属本形态。另外这 3 条的 provider 是 2.2.3 的 `FastIntegrationTestProvider`，本模块只有 `IntegrationTestProvider` |
| fast/FastProjectionQueryIntegrationTest.java | 全类 3 条（与上一行同名、同目标） | 删除 | 同上：接口投影 + LAZY 关联属性 + 2.2.3 `FastIntegrationTestProvider`，整类不建文件 |
| ProjectionJoinAnnotationTest.java | shouldQueryOrderWithCustomerViaJoinProjection / shouldFilterOrdersByCustomerIdViaJoinProjection（2 条，整类不移植） | 删除 | 3.x 无 `@Join` 注解：`grep -rn "@interface Join" nextentity-api/src/main nextentity-core/src/main nextentity-jpa/src/main` = 0 命中；`OrderWithCustomer` 的关联关系只能靠该注解声明，`select(Class)` 的 DTO 属性名只按实体单层属性匹配（`api/SelectStep.java:77`） |
| SelectApiIntegrationTest.java | shouldSelectSevenPaths / shouldSelectEightPaths / shouldSelectNinePaths / shouldSelectTenPaths / shouldSelectDistinctSevenPaths / shouldSelectDistinctEightPaths / shouldSelectDistinctNinePaths / shouldSelectDistinctTenPaths（8 条）+ shouldSelectWithSevenTypedExpressions / shouldSelectWithEightTypedExpressions / shouldSelectWithNineTypedExpressions / shouldSelectWithTenTypedExpressions / shouldSelectDistinctWithSevenTypedExpressionsVarargs / shouldSelectDistinctWithEightTypedExpressionsVarargs / shouldSelectDistinctWithNineTypedExpressionsVarargs / shouldSelectDistinctWithTenTypedExpressionsVarargs（8 条） | 删除 | 3.x 投影最多三列：`api/SelectStep.java:23-53` 只有单列、两列 `Tuple2`、三列 `Tuple3` 三个 `select` 重载；`api/model/` 只有 `Tuple2`~`Tuple6`，无 `Tuple7`~`Tuple10`；`ProjectionMappers` 也只到六列 |
| SelectApiIntegrationTest.java | shouldSelectWithCollectionOfPaths / shouldSelectDistinctWithCollectionOfPaths（2 条）+ shouldSelectWithListOfTypedExpressions / shouldSelectDistinctWithListOfTypedExpressions / shouldSelectWithListOfTypedExpressionsAggregate / shouldSelectDistinctWithListOfTypedExpressionsAggregate / shouldSelectDistinctWithListOfPathsAsTypedExpressions（5 条） | 删除 | `api/SelectStep.java` 没有 `select(Collection)` / `select(List)` 重载（全部重载见 `:23`、`:30`、`:39`、`:52-53`、`:66`、`:91`）；变量列数只能走 `project(ProjectionMapper)` 手写映射器，那会改掉用例的断言形态（拿不到 `Tuple.get(i)`） |
| ProjectionQueryIntegrationTest.java | 22 条（JavaBean 6 + record 9 + distinct DTO 2 + 边界 5） | 移植 | `select(Class)` 原样可用（`api/SelectStep.java:68-91`）：JavaBean 目标需无参构造器 + `setXxx`（`:72-73`），record 目标按规范构造器参数名（`:72`）；`selectDistinct(EmployeeSummary.class)` / `selectDistinct(EmployeeNameSalary.class)` 写成 `distinct().select(...)`（`api/DistinctStep.java:5-7`：`selectDistinct` 就写作 `distinct().select(Dto.class)`） |
| SelectApiIntegrationTest.java | shouldSelectWithProjectionTypeSameEntity / shouldSelectWithProjectionTypeSameEntityReturnsSame（2 条，新增 §12） | 移植 | 目标类型恰是实体类型时 `select(Class)` 退化为整实体查询（`api/SelectStep.java:82-83`），断言原样 |

## 待裁决（改完能跑，但怀疑与 2.2.3 断言不等价）

| 文件 | 用例 | 判定 | 依据 |
|---|---|---|---|
| SelectApiIntegrationTest.java | shouldSelectDistinctWithProjectionType | 待裁决 | 2.2.3 写 `selectDistinct(Department.class)`，3.x 实体投影**禁止** `distinct()`（`api/DistinctStep.java` 末段与 `api/SelectStep.java:82-83`：整实体列集不是显式列表，运行期抛 `UnsupportedOperationException`），故降级为 `select(Department.class)`，断言 `hasSize(5)` 原样。fixture 里 5 个部门本就唯一，去重前后行数应一致（2026-10-31 H2 实测通过），但「distinct 语义被弱化」这一点留 B2 定夺 |
