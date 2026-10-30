# nextentity-examples

2.2.3 `nextentity-examples` 的移植载体：**用项目自己的旅程代码，证明「0 调用点」不成立**。

3.0.0 删掉的条件更新、字符串算子、`fetch(...)`、DTO/多列投影、`groupBy + count()`、`slice(offset,limit)` 等 API，理由都是「point-mall 里 0 调用点」。但项目自己的 examples 恰恰用遍了它们——只是从未搬进 3.x，于是「0 调用点」成了循环论证；搬回来且调用形态可编译、断言通过，就是这些 API 真有调用方的证据。

`./mvnw -pl nextentity-examples test`（H2 内存库，无容器；16 主 + 9 测，断言照抄 2.2.3 一条未弱化）

## 跳过的 12 个文件（4 主 + 8 测）

依赖 3.x 已披露的设计差异，不是移植失败：

- 代理拦截器（无 `ConstructInterceptor` / `CglibProxyInterceptor` / `@Fetch`）：`ProxyInterceptorConfig`、`CglibProxyInterceptorUsageExamples`、`CglibProxyInterceptorIntegrationTest`、`LazyLoadingBehaviorTest`、`ProjectionTypeTest`。
- 通用仓储（无自动注册的 `GenericRepository` / `EntityRepository`）：`IExtendedEntityRepository`、`CustomizedEntityRepository`、`RepositoryBeanRegistrarTest`、`MultiRepositoryAutowiredTest`、`CustomerRepositoryAutowiredTest`、`RepositoryFactoryIntegrationTest`。
- 接口投影（`select(Class)` 不支持接口）：`EmployeeRepositoryTest`；保留的 `EmployeeInfoInterface` / `EmployeeModel` 在 javadoc 标注 3.x 抛 UOE。

## 改写形态（只改「从哪拿查询、算子叫什么」，断言未动）

- `AbstractRepository<T, ID>` → `<T>`；便捷 CRUD 用 `query()` / `core.EntityOperations` 就地补齐。
- `update().set().where()` → `operations.update()`；`delete().where()` → `operations.delete()`；实体删除 → 条件删除。
- `path(X::getY)` → `Path.of(X::getY)`；嵌套 → `EntityPath.of(...).get(...)`，比较运算整体进 `where`。
- `selectDistinct(x)` → `distinct().select(x)`；整实体去重退化为 `select(Entity.class)`（行按主键唯一）。
- 4/5/6 列 `select` → `project(ProjectionMappers.select(...))`；`@EntityPath` 嵌套 DTO → 手写 `ProjectionMapper`。
- 数值 `multiply/subtract/divide` → `mul/sub/div`，算术表达式整体进 `where`。
- `implements Entity` / `Persistable<Long>` → 删除（3.x api 无此类型）。

`lock(...)` 分页与 `length/substring/trim/upper` 上游本无调用点，由 `nextentity-tests-jpa` 兜；本模块是上游语料，不补新断言。

## 发布与门禁

- **不发布**：不在发布集（`api/core/jpa/jdbc/spring/bom`），pom 未配 deploy / install / gpg / javadoc。
- **跳过 spotless / checkstyle / PMD**：保持上游排版便于与 `D:\source\java\nextentity` 逐文件 diff；范围例外，新代码仍受全量门禁约束。
