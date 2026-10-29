# nextentity

JPA 之上的小型类型安全查询层。把「动态条件 + 排序 + 分页 + 聚合 + 投影 + 批量写」收敛成一套 DSL，其余交给 Hibernate。

- **小**：单一执行引擎、无 codegen、不复制 Hibernate 已有的能力。
- **编译期可查**：字段引用走方法引用（`Order::getStatus`），改字段名即编译失败；关联路径用 `EntityPath.of(...).get(...)` 逐段拼出，同样编译期可查。
- **后端可换**：`Spec`（不可变 AST）/ `Compiler`（渲染）/ `Executor`（执行）三段分离，增加后端不改核心。3.0.0 起有第二个后端 `nextentity-jdbc`（纯 JDBC，不经过 Hibernate），同一套 DSL 直接换工厂即可，见文末「换后端」。

非目标：实体生命周期管理、懒加载代理、实体事件总线、多方言 SQL 生成、注解处理器生成实体类、JPA 静态元模型（`Order_`）。

> 本文与 [docs/USAGE.md](docs/USAGE.md) 里的每段示例都对真实产物（`nextentity-api`/`core`/`jpa`/`jdbc`/`spring`）做过 javac 编译验证；新增或修改示例时按 [AGENTS.md](AGENTS.md) 的要求抄进 `tmp/scripts/` 下的临时脚本复验，用完即删（不入库）。

## 30 秒

```java
@Repository
public class OrderRepository extends AbstractRepository<Order> {

    public OrderRepository(EntityOperationsFactory factory) {
        super(factory);
    }

    public List<Order> pendingSince(Instant from) {
        return query().where(Order::getStatus)
                .eq(OrderStatus.CREATED)
                .where(Order::getCreatedAt)
                .ge(from)
                .orderBy(Order::getId)
                .desc()
                .list();
    }

    public Slice<Order> page(long orgId, Pageable pageable) {
        return query().where(EntityPath.of(Order::getCustomer).get(Customer::getOrgId))
                .eq(orgId)
                .orderBy(Order::getId)
                .desc()
                .slice(pageable.offset(), pageable.size());
    }
}
```

**换后端**：同一个仓储接口可以喂给纯 JDBC 后端（不经 Hibernate，只要一个 `DataSource`）：

```java
EntityOperationsFactory factory = new JdbcEntityOperationsFactory(dataSource);
EntityOperations<Order> orders = factory.operations(Order.class);
List<Order> pending = orders.query()
        .where(Order::getStatus)
        .eq(OrderStatus.CREATED)
        .list();
```

注意 `nextentity-spring` 的自动配置只注册 JPA 侧的 `EntityOperationsFactory` bean；用 JDBC 后端时自己 `new` 工厂（或自己注册 bean）。方言探测、连接与事务语义、支持范围见 [docs/USAGE.md](docs/USAGE.md)「JDBC 第二后端」。

## 模块

| 模块 | 职责 | 依赖 |
|---|---|---|
| `nextentity-api` | 公开 DSL：`Path`/`PathRef`/`EntityPath`/`Predicate`、步骤接口（含 `HavingStep`）、算子接口（`Comparison`/`Range`/`ColumnComparison`/`NumberArithmetic`/`LogicalOps`/`StringOps` 等）、`Slice`/`Tuple2`/`Tuple`、`AggregateValue`（`count`/`countDistinct`/`max`/`min`/`avg`/`sum`）、`NumberValue`、`LockMode`、`Op`/`Operation`（值→AST 的线格式） | 无（零 `dependency`，enforcer 的 `exclude *:*` 兜底） |
| `nextentity-core` | `meta`（属性解析接缝）、`spec`（不可变 AST）、`compile.Compiler` / `exec.Executor` / `exec.PersistExecutor`（后端接缝）、`builder`（把 api 值解释成 `Spec`）、`repo.EntityOperations` 与装配、`TypeCastUtil` | `api` |
| `nextentity-jpa` | Criteria 渲染（`JpaCompiler`/`JpaQuery`/`JpaPredicates`/`JpaProjections`/`JpaOperands`——后者是 `Operand → Criteria Expression` 的唯一渲染点）、JPA 执行（`JpaExecutor`/`JpaPersistExecutor`）、Hibernate 元模型（`JpaMeta`） | `core` + `jakarta.persistence-api` |
| `nextentity-jdbc` | 第二后端：SQL 渲染（`JdbcCompiler`/`SqlRenderer`/`SqlPredicates`/`SqlProjections`）、JDBC 执行（`JdbcExecutor`/`JdbcPersistExecutor`）、注解元数据（`JdbcMeta`/`JdbcEntityRules`）、方言接缝 `JdbcDialect`（内置 H2/MySQL/PostgreSQL/SQL Server） | `core` + `jakarta.persistence-api` |
| `nextentity-spring` | Spring Boot 自动配置（只注册一个 `EntityOperationsFactory` bean，开关 `nextentity.enabled`）+ `AbstractRepository` 仓储基类 | `core` + `jpa` |
| `nextentity-testing` | 一致性测试基类（`*ConsistencyTest`）与夹具 | `api` + `core` |
| `nextentity-arch` | 架构规则（ArchUnit）：依赖方向、无环、后端隔离、接口宽度；无生产代码 | 各模块（test 作用域）+ `archunit-junit5` |
| `nextentity-tests-jpa` | JPA 集成测试（H2、Testcontainers MySQL） | `jpa` + `testing` |
| `nextentity-tests-jdbc` | JDBC 后端集成测试（H2、Testcontainers MySQL / PostgreSQL / SQL Server） | `jdbc` + `testing` |
| `nextentity-tests-legacy` | 2.2.3 移植语料回归（H2、MySQL 容器）；模块跳过 spotless/checkstyle/PMD | `jpa` |
| `nextentity-bom` | 依赖版本对齐 | — |

## 文档

| 文件 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 规则与命令：红线、版本语义、验证门禁 |
| [docs/DESIGN.md](docs/DESIGN.md) | 架构正文：分层、兼容契约、测试策略、后端接缝、决策记录 |
| [docs/USAGE.md](docs/USAGE.md) | 使用正文：条件、排序、分页、聚合、投影、关联、锁、写入、Spring 接入 |
| [CHANGELOG.md](CHANGELOG.md) | 变更与迁移（2.2.3 → 3.x 的破坏性差异逐条列出） |

## 构建

```bash
./mvnw verify                       # 全量门禁：格式 + 静态检查 + 单测 + 覆盖率
./mvnw -q spotless:apply            # 一键格式化
./mvnw -pl nextentity-core,nextentity-arch -am test   # 最快回归 + 架构门禁（无需容器）
```

集成测试（`nextentity-tests-jpa` / `nextentity-tests-jdbc`）默认跑 H2；MySQL 方言层需要 Docker，JDBC 后端另有 PostgreSQL / SQL Server 方言 profile（同样需要 Docker）。

