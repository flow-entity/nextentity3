# NOT-PORTED — port-c 第三轮（fetch 两段嵌套 / fetch 多参 / 嵌入投影）

> **本轮回填（2026-10-31，`fetch` 与 `project(ProjectionMapper)` 落地之后）**：前两轮按「3.x 无 fetch」
> 整类删掉的 `DeepFetchTwoLevelIntegrationTest`（7）、`FetchOperationsIntegrationTest`（18）、
> `FetchStepMultipleParametersIntegrationTest`（6）本轮全部按上游新建 —— 3.x 确实有 fetch：
> `api/FetchStep.java:37-91`（`fetch(Path)` / 两参 / 三参 / `fetch(PathRef)` / `fetch(PathRef,PathRef)` /
> `fetch(Collection<? extends Path>)`，返回 `BaseWhereStep`，可继续 where/orderBy/分页）。
> `EmbeddedProjectionIntegrationTest` 的上游用例靠 `@EntityPath` 注解把 DTO 属性指向嵌入列，3.x 无该注解
> （全仓 `@interface EntityPath` 0 命中，只有 `api/EntityPath.java` 那个路径接口），故 15 条里 12 条改写成
> `project(ProjectionMapper)` 并保留断言，3 条（接口投影 / 嵌套 DTO / 注解类型不匹配静默跳过）删除。
> 总账见 [`REPORT.md`](REPORT.md)。

> **后续更新（2026-10-31，`select(Class)` 补上 `@Embedded` 同名叶子回找之后）**：3.2.0 的 `select(Class)`
> 已支持按名回找嵌入叶子（实现 `nextentity-jpa/src/main/java/io/github/nextentity/jpa/meta/JpaEmbeddedPaths.java`
> + `nextentity-jpa/src/main/java/io/github/nextentity/jpa/compile/JpaPaths.java`），故上表「12 条改写为
> `project(ProjectionMapper)`」中的 **11 条已改回上游 `.select(Dto.class)` 形态**（断言逐行未动），只有
> `shouldProjectCrossLayerEmbeddedWithEntityPath` 仍用 `project(PERSON_CROSS_LAYER_BASIC)`——它的
> `zipCode`/`secondaryCode` 指向叶子 `code`，属「DTO 字段名 ≠ 叶子名」，按名回找表达不了。3 条删除不变。
> 本节表格保留落盘当时的事实，看当前写法以测试源文件为准。

口径：[`NOT-PORTED-port-c.md`](NOT-PORTED-port-c.md) 同款。3.x api 事实来源：
`nextentity-api/src/main/java/io/github/nextentity/api/`（下文行号均指这里）。
编译验证：`bash tmp/scripts/compile-legacy.sh D:/source/java/nextentity3/tmp/legacy/out-c` → **EXIT=0**；
直跑：`bash tmp/scripts/run-legacy-tests.sh D:/source/java/nextentity3/tmp/legacy/out-c io.github.nextentity.integration.DeepFetchTwoLevelIntegrationTest io.github.nextentity.integration.FetchOperationsIntegrationTest io.github.nextentity.integration.FetchStepMultipleParametersIntegrationTest io.github.nextentity.integration.EmbeddedProjectionIntegrationTest`
→ `43 tests found / 43 successful / 0 failed`（H2 MODE=MySQL，无容器）。
判定：`移植` = 只改调用形态、断言与期望值未动；`删除` = 3.x 能力已删；`待裁决` = 已改完能跑，但怀疑与 2.2.3 语义不等价。

结果：保留 **43** 条（4 个整类，均为本模块新文件），删除 **3** 条，待裁决 **0** 条。

## 汇总

| 文件 | 保留 | 删除 | 待裁决 |
|---|---|---|---|
| DeepFetchTwoLevelIntegrationTest.java | 7 | 0 | 0 |
| FetchOperationsIntegrationTest.java | 18 | 0 | 0 |
| FetchStepMultipleParametersIntegrationTest.java | 6 | 0 | 0 |
| EmbeddedProjectionIntegrationTest.java | 12 | 3 | 0 |

`dto/` 本轮新增 7 个文件（只新增、未动已有文件）：`PersonAddressBasic` / `PersonAddressRecord` /
`PersonAddressOnlyEmbedded` / `PersonAddressOverridden` / `PersonContactBasic` / `PersonCrossLayerBasic` /
`ProjectEmbeddedOnly` —— 字段、getter/setter、`toString` 与上游逐字一致，只删掉 `io.github.nextentity.core.annotation.EntityPath`
的 import 与字段注解（3.x 无此类）。上游的 `PersonAddressInfo`（接口投影目标）、`PersonAddressWrongType`
（注解类型不匹配用例的目标）不搬：对应用例已删。

## 新增文件（整类，含本模块适配）

| 文件 | 判定 | 依据 / 说明 |
|---|---|---|
| DeepFetchTwoLevelIntegrationTest.java | 移植 | 7 条：`fetch` 两段嵌套路径、一层+两层混取、按嵌套路径 where、orderBy、`EntityPath` 变量复用、根分类无父（`parentCategory` isNull）。上游 `@ArgumentsSource(FastIntegrationTestProvider.class)` 改 `IntegrationTestProvider`（本模块只有这一个装置） |
| FetchOperationsIntegrationTest.java | 移植 | 18 条：`fetch` 与 where/orderBy/分页/`list(limit)`/`count()`/`exists()`/`single()`/`first()` 组合、`fetch(Collection)`、`in`/`notIn`/`like`/`ge..le` 组合。上游 javadoc 关于「JDBC 后端 department 可能为 null」的断言保留（`department != null` 时才断言 `department.getName()`） |
| FetchStepMultipleParametersIntegrationTest.java | 移植 | 6 条：`fetch(PathRef,PathRef)`、`fetch(Path,Path,Path)`、`EntityPath` 与 `Path` 混参、与 where/`list(limit)` 组合 |
| EmbeddedProjectionIntegrationTest.java | 移植 + 删除 | 12 条保留（JavaBean / record / `@AttributeOverride` / 跨层 `address.zip.code` / 三层 `contactInfo.address.street` / 空嵌入 / orderBy / 分页 / 空结果 / distinct）；`tearDown` 省略 —— 3.x 装置每次交付上下文前 `reset()`（`IntegrationTestProvider`），与上游 `@AfterEach` 等价 |

## 调用形态适配（断言与期望值一行未动）

| 上游写法（2.2.3 api） | 3.x 写法 | 依据 |
|---|---|---|
| `Path.of(X::getA).get(Y::getB)` | `EntityPath.of(X::getA).get(Y::getB)` | `api/Path.java:13-22` 的 `Path` 没有 `get`；嵌套链在 `api/EntityPath.java`（`static of(PathRef)` / `get(PathRef)`） |
| `fetch(Employee::getDepartment)` | 原样（`fetch(PathRef)` default 重载） | `api/FetchStep.java:60-64` |
| `fetch(X::getA, Path.of(...).get(...))` | `fetch(Path.of(X::getA), EntityPath.of(X::getA).get(...))` | 两参重载是 `fetch(Path,Path)`（`:66-70`），路径链是值对象不是 `PathRef`，故首参用 `Path.of` 包装 |
| `fetch(a, b, c)`（三个裸方法引用） | `fetch(Path.of(a), Path.of(b), Path.of(c))` | 3.x **无** 3×`PathRef` 重载，只有 `fetch(Path,Path,Path)`（`:72-76`） |
| `List<PathRef<Employee, ?>> paths` + `fetch(paths)` | `List<Path<Employee, ?>> paths` + `paths.add(Path.of(...))` + `fetch(paths)` | 集合重载收 `Collection<? extends Path>`（`:52-56`），`PathRef` 不是 `Path` |
| `where(X::getParentCategory).get(Y::getName)` | `where(EntityPath.of(X::getParentCategory).get(Y::getName))` | 主语从「裸方法引用 + 链式 get」改成嵌套路径值对象（同上 `Path` 无 `get`） |
| `selectDistinct(Dto.class)` | `distinct().project(<mapper>)` | `api/DistinctStep.java`：`selectDistinct` 一律写作 `distinct().<投影>`，distinct 可修饰 `select(Class)` 与 `project`（`api/SelectStep.java:16` `SelectStep extends DistinctStep`） |
| `select(Dto.class)`（DTO 属性带 `@EntityPath("address.city")`） | `select(<Dto>.class)`（3.2.0 起；DTO 属性名与嵌入叶子同名时按名回找）；**改名**形态仍用 `project(<ProjectionMapper>)`：列 = `EntityPath.of(X::getAddress).get(Address::getCity)` 等 | `select(Class)` 先匹配实体直接属性，命不中再回找 `@Embedded` 同名叶子（可多级；实现 `nextentity-jpa/.../meta/JpaEmbeddedPaths.java` + `compile/JpaPaths.java`；歧义抛 IAE）；「DTO 字段名 ≠ 叶子名」仍表达不了（`core/builder/DtoProjection.java:114-116` 只产出单段 `AttributePath`）；`project` 见 `api/SelectStep.java:66`、`api/ProjectionMapper.java` |
| `context.getUpdateExecutor().insert(e, context.getEntityContext(X.class))` / `insertAll(list, ...)` | `context.operations(X.class).insert(e)` / `insertAll(list)` | 3.x 写入入口 `core/repo/EntityOperations extends PersistExecutor`（`PersistExecutor.insert(T)` / `insertAll(Iterable<T>)`），既有的 `EmbeddedCrudIntegrationTest` 同款写法 |
| `@AfterEach tearDown { getEntityManagerContext().reset(); }` | 省略 | 3.x 装置在交付上下文前自动 `reset()`（`config/IntegrationTestProvider`），生命周期方法无副作用则省略（brief §3） |

## 删除明细（按能力归并）

| 文件 | 用例/类 | 判定 | 依据 |
|---|---|---|---|
| EmbeddedProjectionIntegrationTest.java | shouldProjectEmbeddedToInterfaceWithEntityPath | 删除 | 目标是接口投影（`PersonAddressInfo`），3.x 接口投影不提供：`api/SelectStep.java:75` 明写「接口与抽象类不支持：接口投影要靠运行期代理，本轮不提供」，`:88-89` 对接口/抽象类抛 `IllegalArgumentException`；上游该用例还依赖 `@EntityPath` 注解，3.x 无此类 |
| EmbeddedProjectionIntegrationTest.java | shouldProjectNestedEmbeddedDto | 删除 | 目标是**嵌套 DTO**（`PersonWithNestedAddressEmbedded` 里嵌 `AddressDto`），`select(Class)` 只按名装配扁平字段、不构造嵌套对象（嵌入回找只解决「属性名对应哪个叶子」，不解决「DTO 里有对象字段」）。若用 `project` 手工 new 出嵌套 DTO 再 `assertThat(dto.getAddress().getStreet())`，断言的只是映射器自己装的对象（自证循环），属弱化断言，故删 |
| EmbeddedProjectionIntegrationTest.java | shouldSkipTypeMismatchInEntityPath | 删除 | 该用例唯一被测语义是「`@EntityPath` 注解声明的属性类型与实体列类型不匹配时静默跳过」（上游注释 `matchProjectionBasicAttribute returns false`）；3.x 没有注解式声明，映射器列类型编译期固定，无对位能力 |

## 待裁决（改完能跑，但怀疑与 2.2.3 断言不等价）

无。

## 备注

- fetch 前两轮被 `NOT-PORTED-port-d.md` 整类删除，本轮按 `api/FetchStep.java` 事实恢复，避免继续沿用旧结论。
- `EmbeddedProjectionIntegrationTest` 的 12 条保留用例中，`select(Dto)` 的统一替换点**当时**是 `.project(<常量映射器>)`；3.2.0 补了 `@Embedded` 同名叶子回找后，其中 11 条已改回上游 `.select(<Dto>.class)`，只剩跨层改名那条（`zipCode`/`secondaryCode` → 叶子 `code`）仍用 `project(PERSON_CROSS_LAYER_BASIC)`。where / orderBy / `in(...)` / `list(2)` / `getFirst()` / `extracting(...).containsExactlyInAnyOrder(...)` 始终逐行保持上游原样（含 `ProjectEmbeddedOnly` 只投影 `address.city`，使 DISTINCT 真去重这一前提）。
- 三轮记档里 `filter by embedded field` 一条的上游写法是 `where(X::getAddress).get(Address::getCity).eq(...)`，迁移后是 `where(EntityPath.of(X::getAddress).get(Address::getCity)).eq(...)`；条件语义（嵌入列等值）与既有 `EmbeddedCrudIntegrationTest.shouldQueryByEmbeddedCity` 同款。
