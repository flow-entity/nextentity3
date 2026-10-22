# USAGE — 使用正文

本文只讲**怎么写**。规则与门禁见 [AGENTS.md](../AGENTS.md)，架构与决策见 [DESIGN.md](DESIGN.md)，版本变更见 [CHANGELOG.md](../CHANGELOG.md)。

> 所有示例都对真实产物做过 javac 编译验证（脚本随本次迁移归档：`tmp/scripts/probe/DocExamples.java`，不入库），示例里出现的每个成员在 `nextentity-api` / `core` / `spring` / `jpa` 中都存在。**本库不提供 JPA 静态元模型**（没有 `Order_`），字段一律用方法引用。

## 1. 引入

```xml
<dependency>
    <groupId>io.github.flow-entity</groupId>
    <artifactId>nextentity-spring</artifactId>
    <version>${nextentity.version}</version>
</dependency>
```

Spring Boot 应用引入 `nextentity-spring` 即自动配置。非 Spring 场景用 `nextentity-jpa`，自己装配：

```java
EntityOperationsFactory factory = new JpaEntityOperationsFactory(entityManager, entityManagerFactory);
EntityOperations<Order> operations = factory.operations(Order.class);
EntityQuery<Order> query = operations.query();
```

## 2. 实体与仓储

实体是标准 JPA 实体，本库不加私有注解：

```java
@Entity
@Table(name = "orders")
public class Order {
    @Id private Long id;
    private OrderStatus status;
    private BigDecimal amount;
    private Instant createdAt;
    @Version private int version;
    @ManyToOne @JoinColumn(name = "customer_id") private Customer customer;
}
```

Spring 下的仓储继承 `AbstractRepository`（构造器注入唯一的 `EntityOperationsFactory`）：

```java
@Repository
public class OrderRepository extends AbstractRepository<Order, Long> {

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
}
```

## 3. 条件

比较、集合、空值、字符串：

```java
query().where(Order::getStatus)
        .eq(OrderStatus.CREATED)
        .where(Order::getStatus)
        .ne(OrderStatus.CANCELLED)
        .where(Order::getAmount)
        .gt(BigDecimal.TEN)
        .where(Order::getAmount)
        .between(min, max)
        .where(Order::getCustomerId)
        .in(ids)
        .where(Order::getCustomerId)
        .notIn(ids)
        .where(Order::getShippedAt)
        .isNull()
        .where(Order::getShippedAt)
        .isNotNull()
        .where(Order::getOrderNo)
        .likeIfNotNull("%A%")
        .where(Order::getRecipient)
        .contains("张三")
        .list();
```

- `contains(v)` → `%v%`（通配符由本库加）。
- `likeIfNotNull(pattern)` → LIKE，**模式自带通配符**（`%` 由调用方掌握）；`pattern == null` 时整条条件跳过。
- 布尔字段请写 `.eq(true)`，不支持「把字段本身当条件」。

### 3.1 可选条件（参数为空时不参与查询）

```java
query().where(Order::getStatus)
        .eqIfNotNull(cond.status())
        .where(Order::getCreatedAt)
        .geIfNotNull(cond.start())
        .where(Order::getCustomerId)
        .inIfNotNull(cond.customerIds())
        .where(Order::getRecipient)
        .containsIfNotEmpty(cond.recipient())
        .list();
```

语义：`*IfNotNull(null)` 与 `containsIfNotEmpty("")` **不产生条件**（等价于不写这一条），不是 `= null`。

### 3.2 组合

AND 直接续写；OR 先组合成 `Predicate` 再交给 `where(...)`：

```java
// status = CREATED AND amount > 0
query().where(Order::getStatus)
        .eq(OrderStatus.CREATED)
        .where(Order::getAmount)
        .gt(BigDecimal.ZERO)
        .list();

// status = CREATED OR amount > 0（括号语义由 Logical 节点保证）
Predicate<Order> createdOrPositive =
        Path.of(Order::getStatus).eq(OrderStatus.CREATED).or(Path.of(Order::getAmount).gt(BigDecimal.ZERO));
query().where(createdOrPositive).list();
```

### 3.3 两个容易踩的语义

```java
// in(空集合) → 恒假条件 → 0 行（不是「忽略该条件」）
List<Order> none = query().where(Order::getId).in(List.of()).list();

// eqIfNotNull(null) → 跳过该条件 → 等价于全表
List<Order> all = query().where(Order::getStatus).eqIfNotNull(null).list();
```

## 4. 排序与分页

```java
// 多级排序：先 createdAt desc，再 id asc
query().where(Order::getStatus)
        .eq(OrderStatus.PAID)
        .orderBy(Order::getCreatedAt)
        .desc()
        .orderBy(Order::getId)
        .asc()
        .list();

// 分页：Slice 同时给出本页数据与总数
Slice<Order> slice = query().where(Order::getStatus)
        .eq(OrderStatus.PAID)
        .orderBy(Order::getId)
        .desc()
        .slice(pageable.offset(), pageable.size());

// 映射成消费方自己的分页类型
Page<Order> page = slice.to(s -> new Page<>(pageable, s.data(), s.total()));
```

`slice(offset, limit)` 会额外发一条 count；`total()` 是满足条件的总行数。

## 5. 终止符

```java
List<Order> list = q.list();     // 全部
List<Order> top  = q.list(50);   // 前 50 条
Order first      = q.first();    // 首条或 null
Order one        = q.single();   // 唯一一条或 null；命中多条抛 IllegalStateException
long total       = q.count();
boolean exists   = q.exists();
```

`single()` 命中多于一条时抛 `IllegalStateException("found more than one result, expected one")`，不做静默取首条。

## 6. 投影

```java
// 单列
List<Long> ids = query().select(Order::getId).list();

// 双列 → Tuple2（三列及以上的 DTO 投影不在 3.x 支持范围内）
List<Tuple2<Long, OrderStatus>> rows =
        query().select(Path.of(Order::getId), Path.of(Order::getStatus)).list();

// 去重单列
List<Long> uniq = query().selectDistinct(Order::getCustomerId).list();
```

双列投影的两个参数都必须是 `Expression`（`Path.of(...)` 或聚合结果）；裸方法引用不在契约内，只用于单列 `select(...)`。

## 7. 聚合与分组

```java
long n = query().count();
BigDecimal sum = query().select(Path.of(Order::getAmount).sum()).single();
BigDecimal max = query().select(Path.of(Order::getAmount).max()).single();
long distinctCustomers = query().select(Path.of(Order::getCustomerId).countDistinct()).single();

// 分组聚合：customerId → 订单数
List<Tuple2<Long, Long>> perCustomer = query().select(
                Path.of(Order::getCustomerId), Path.of(Order::getId).count())
        .where(Order::getCreatedAt)
        .ge(from)
        .groupBy(Order::getCustomerId)
        .list();
```

支持 `count` / `countDistinct` / `max` / `sum`；**不提供** `avg` / `min` / `having`（见 §14）。数值聚合要求属性是 `Number`：`sum()` 只在数值路径上可用，`max()` 在任意可比较属性上可用（例如 `Path.of(Order::getCreatedAt).max()`）。

## 8. 关联

关联用 `EntityPath` 逐段 `get`，条件落在关联字段上时会自动 join（`isNull()` 走 LEFT JOIN 语义）：

```java
EntityPath<Order, Customer> customer = EntityPath.of(Order::getCustomer);
StringPath<Order> orgName = customer.get(Customer::getOrg).get(Organization::getName);

query().where(customer.get(Customer::getOrgId))
        .eq(orgId)
        .where(orgName)
        .contains("科技")
        .list();

query().where(orgName).eq("总部").list();
```

3.x **没有显式 `fetch(...)`**：需要避免 N+1 时用 `@EntityGraph` / Hibernate 原生 API，或直接查询所需的投影列。

## 9. 锁

```java
// 自研枚举（core/spec 不接触 jakarta）
Order pessimistic = query().where(Order::getId).eq(id).lock(LockMode.PESSIMISTIC_WRITE).single();

// 2.2.3 兼容重载：直接传 jakarta 的 LockModeType
Order compatible = query().where(Order::getId).eq(id).lock(LockModeType.PESSIMISTIC_WRITE).single();
```

`lock(...)` 必须跟在产生条件的算子之后（例如 `.eq(id).lock(...)`），返回终止符。`LockMode` 的 5 个常量与 `jakarta.persistence.LockModeType` 同名。

## 10. 写入

写入走 `core.exec.PersistExecutor`（`EntityOperations` 即实现它），只有四个方法：

```java
EntityOperations<Order> operations = factory.operations(Order.class);
operations.insert(order);
operations.insertAll(orders);
operations.update(order);       // 含 @Version 校验，并把新版本号回写到入参对象
operations.updateAll(orders);
```

`@Version` 字段的实体走乐观锁：版本冲突抛 `OptimisticLockException`，由调用方决定重试策略。**不提供** `update().set().where()` / `delete().where()` 这类按条件写入（见 §14）。

## 11. 事务

本库不自己开事务，边界由调用方的 `@Transactional` 决定：

```java
@Transactional
public void cancel(long id) {
    Order order = repository.query().where(Order::getId).eq(id).single();
    order.setStatus(CANCELLED);
    operations.update(order);
}
```

`core.TransactionOperations` 是给后端模块用的接缝（`spring` 模块**不**注册它的 bean，事务管理器由 Spring Boot 提供）。

## 12. Spring 接入

```yaml
nextentity:
  enabled: true     # 唯一开关；不配或 false 时自动配置不生效
```

自动配置只注册一个 bean：`EntityOperationsFactory`。仓储自己继承 `AbstractRepository` 并用构造器注入它即可。

## 13. 何时不用本库

- 纯 CRUD、无动态条件 → 直接用 Spring Data 派生查询。
- 复杂报表 SQL（窗口函数、多表聚合、方言函数）→ 用 `@Query` 原生查询。
- 需要实体图/级联策略深度控制 → 用 Hibernate 原生 API。

## 14. 3.x 不提供的能力

下面这些在 2.2.3 存在但在 point-mall / id-generator 零调用点，3.x 一律不提供（逐条理由见 [CHANGELOG.md](../CHANGELOG.md)）：

| 能力 | 2.2.3 写法 | 3.x 替代 |
|---|---|---|
| 静态元模型 | `where(Order_.status)` | `where(Order::getStatus)` |
| 显式 fetch | `.fetch(Order::getCustomer)` | `@EntityGraph` / 原生 API / 投影 |
| DTO/record 投影 | `select(OrderRow.class)` | 双列用 `Tuple2`；三列及以上见下一行 |
| 三列及以上投影 | `select(p1, p2, p3)` | 拆成多次查询或 `@Query` |
| 按条件更新/删除 | `update().set(..).where(..)` / `delete().where(..)` | `@Modifying @Query` |
| 子查询 | `toSubQuery()` | `@Query` 原生/JPQL |
| `having` | `groupBy(..).having(..)` | 先聚合再在内存里过滤 |
| 均值/最小值 | `avg()` / `min()` | `sum()/count()` 自己算，或 `@Query` |
| `*IfPresent` 命名 | `eqIfPresent(..)` | `eqIfNotNull(..)`（语义相同） |
| `notLike` / `startsWith` / `endsWith` | 字符串算子 | `contains(...)` / `likeIfNotNull("%v%")` |
| `sliceWithoutCount` | 免 count 分页 | 用 `@Query` 取 `List` 自己算 |


