# nextentity

JPA 之上的小型类型安全查询层。把「动态条件 + 排序 + 分页 + 聚合 + 投影 + 批量写」收敛成一套 DSL，其余交给 Hibernate。

- **小**：目标体量在 nextentity 2.x 的 1/8 以内。单一执行引擎、无 codegen、不复制 Hibernate 已有的能力。
- **编译期可查**：字段引用优先走 JPA 静态元模型（`Order_.status`），改字段名即编译失败；方法引用写法保留，便于存量代码迁移。
- **后端可换**：`Spec`（不可变 AST）/ `Compiler`（渲染）/ `Executor`（执行）三段分离，增加后端不改核心。

非目标：实体生命周期管理、懒加载代理、实体事件总线、多方言 SQL 生成、注解处理器生成实体类。

## 30 秒

```java
public interface OrderRepository extends AppRepository<Order, Long> {

    default List<Order> pendingSince(Instant from) {
        return query()
                .where(Order_.status).eq(OrderStatus.CREATED)
                .where(Order_.createdAt).ge(from)
                .orderBy(Order_.id).desc()
                .list();
    }

    default Slice<Order> page(long orgId, Pageable pageable) {
        return query()
                .where(Order_.customer, Customer_.orgId).eq(orgId)
                .orderBy(Order_.id).desc()
                .slice(pageable.offset(), pageable.size());
    }
}
```

## 模块

| 模块 | 职责 | 依赖 |
|---|---|---|
| `nextentity-api` | 公开 DSL：`Path`/`PathRef`/`Predicate`、步骤接口、`Slice`、`Tuple`、`LockMode` | 无 |
| `nextentity-core` | `Meta`/`Spec`/`Compiler`/`Executor`/`Repository` 的接口与实现骨架 | `api` |
| `nextentity-jpa` | Criteria 渲染与 JPA 执行、Hibernate 元模型解析 | `core` + `jakarta.persistence-api` |
| `nextentity-spring` | Spring Boot 自动配置与事务桥接 | `core` + `jpa` |
| `nextentity-testing` | 一致性测试基类、SQL 快照断言工具 | `api` + `core` |
| `nextentity-tests-jpa` | JPA 集成测试（H2、Testcontainers MySQL） | `jpa` + `testing` |
| `nextentity-bom` | 依赖版本对齐 | — |

## 文档

| 文件 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 规则与命令：红线、版本语义、验证门禁 |
| [docs/DESIGN.md](docs/DESIGN.md) | 架构正文：分层、兼容契约、测试策略、后端接缝、决策记录 |
| [docs/USAGE.md](docs/USAGE.md) | 使用正文：条件、排序、分页、聚合、投影、关联、写入、Spring 接入 |
| [CHANGELOG.md](CHANGELOG.md) | 变更与迁移 |

## 构建

```bash
mvn verify              # 全量门禁：格式 + 静态检查 + 单测 + 覆盖率
mvn -q spotless:apply   # 一键格式化
mvn -pl nextentity-core -am test    # 最快回归（无需容器）
```

集成测试（`nextentity-tests-jpa`）默认跑 H2；MySQL 方言层需要 Docker。
