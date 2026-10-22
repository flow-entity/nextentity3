# USAGE — 使用正文

示例使用 JPA 静态元模型写法（`Order_.status`）；方法引用写法（`Order::getStatus`）等价，二选一混用亦可。规则见 [AGENTS.md](../AGENTS.md)，设计动机见 [DESIGN.md](DESIGN.md)。

## 1. 引入

```xml
<dependency>
    <groupId>io.github.flow-entity</groupId>
    <artifactId>nextentity-spring</artifactId>
    <version>${nextentity.version}</version>
</dependency>
```

Spring Boot 应用引入 `nextentity-spring` 即自动配置；非 Spring 场景直接用 `nextentity-jpa` + `EntityManager` 手工装配。

## 2. 实体与仓储

实体是标准 JPA 实体，本项目不加私有注解：

```java
@Entity
@Table(name = "orders")
public class Order {
    @Id private Long id;
    private OrderStatus status;
    private Instant createdAt;
    @Version private int version;
    @ManyToOne @JoinColumn(name = "customer_id") private Customer customer;
}
```

仓储继承 `AppRepository`：

```java
public interface OrderRepository extends AppRepository<Order, Long> {

    default List<Order> pendingSince(Instant from) {
        return query()
                .where(Order_.status).eq(OrderStatus.CREATED)
                .where(Order_.createdAt).ge(from)
                .orderBy(Order_.id).desc()
                .list();
    }
}
```

## 3. 条件

比较、集合、空值、字符串：

```java
query().where(Order_.status).eq(CREATED)
       .where(Order_.status).ne(CANCELLED)
       .where(Order_.amount).gt(BigDecimal.TEN)
       .where(Order_.amount).between(min, max)
       .where(Order_.customerId).in(ids)
       .where(Order_.customerId).notIn(ids)
       .where(Order_.shippedAt).isNull()
       .where(Order_.shippedAt).isNotNull()
       .where(Order_.orderNo).like("A%")          // 语义为 LIKE，% 由调用方掌握
       .where(Order_.orderNo).startsWith("A")     // 自动加 %
       .where(Order_.orderNo).endsWith("Z")
       .where(Order_.recipient).contains("张三")
       .list();
```

可选条件（请求参数为空时不参与查询）：

```java
query().where(Order_.status).eqIfPresent(cond.status())
       .where(Order_.createdAt).geIfPresent(cond.start())
       .where(Order_.customerId).inIfPresent(cond.customerIds())
       .list();
```

组合：

```java
// OR 分组：status = CREATED 或 金额 > 0
query().where(Order_.status).eq(CREATED)
       .or(Order_.amount).gt(BigDecimal.ZERO);

// 复用谓词
Predicate<Order> visible = Path.of(Order_.visibility).eq(true)
        .or(Path.of(Order_.id).in(ids));
query().where(visible).list();
```

> `where(...)` 只接受谓词；把「字段本身」当条件（`where(Order_.enabled)` 隐式真值）不支持，布尔字段请写 `.eq(true)`。

## 4. 排序与分页

```java
query().where(...)
       .orderBy(Order_.createdAt).desc()
       .orderBy(Order_.id).asc()
       .list();

// 分页：Slice 同时给出数据与总数
Slice<Order> slice = query().where(...)
        .orderBy(Order_.id).desc()
        .slice(pageable.offset(), pageable.size());
Page<Order> page = slice.to(pageable::page);

// 只要数据不要总数（省一次 count）
Slice<Order> only = query().where(...)
        .orderBy(Order_.id).desc()
        .sliceWithoutCount(pageable.offset(), pageable.size());
```

## 5. 终止符

```java
List<Order> list = q.list();
Order first     = q.first();      // 首条或 null
Order one       = q.single();     // 期望恰好一条
long total      = q.count();
boolean exists  = q.exists();
List<Order> top = q.list(50);     // 等价 list(0, 50)
```

## 6. 投影

```java
// 单字段
List<Long> ids = query().select(Order_.id).list();

// 多字段（Tuple2~4；更多字段请用 DTO）
List<Tuple2<Long, OrderStatus>> rows = query()
        .select(Order_.id, Order_.status).list();

// DTO / record：按构造参数顺序映射
List<OrderRow> rows = query().select(OrderRow.class).list();

// 去重
List<Long> uniq = query().selectDistinct(Order_.customerId).list();
```

## 7. 聚合

```java
long n            = query().where(...).count();
BigDecimal sum    = query().select(Path.of(Order_.amount).sum()).single();
BigDecimal avg    = query().select(Path.of(Order_.amount).avg()).single();
BigDecimal max    = query().select(Path.of(Order_.amount).max()).single();
long distinctCust = query().select(Path.of(Order_.customerId).countDistinct()).single();

// 分组
List<Tuple2<Long, Long>> perCustomer = query()
        .select(Order_.customerId, Path.of(Order_.id).count())
        .where(Order_.createdAt).ge(from)
        .groupBy(Order_.customerId)
        .having(Path.of(Order_.id).count()).gt(10)
        .list();
```

## 8. 关联

嵌套路径会生成必要的 join：

```java
// 条件落在关联字段上
query().where(Order_.customer, Customer_.orgId).eq(orgId)
       .where(Order_.customer, Customer_.name).contains("科技")
       .list();

// 多级
query().where(Order_.customer, Customer_.org, Organization_.name).eq("总部");
```

显式预加载（避免 N+1）：

```java
List<Order> orders = query()
        .fetch(Order_.customer)          // 一次 join fetch
        .where(Order_.status).eq(CREATED)
        .list();
```

## 9. 写入

```java
repo.insert(order);                 // 单条
repo.insertAll(orders);             // 批量
repo.update(order);                 // 按 ID 全量更新（含 @Version 校验）
repo.updateAll(orders);
repo.delete(order);
repo.deleteAllById(ids);
repo.deleteById(id);
```

按条件更新 / 删除：

```java
int updated = repo.update()
        .set(Order_.status, CANCELLED)
        .where(Order_.createdAt).lt(cutoff)
        .execute();

int deleted = repo.delete()
        .where(Order_.status).eq(CANCELLED)
        .execute();
```

乐观锁：实体含 `@Version` 时，`update` 走版本校验；版本冲突抛 `OptimisticLockException`，由调用方重试。

## 10. 事务

```java
@Transactional
public void cancel(long id) {
    Order order = repo.getById(id);
    order.setStatus(CANCELLED);
    repo.update(order);
}
```

`repository` 内部操作不自己开事务；批量或读-改-写请在 Service 层用 `@Transactional` 划边界。

## 11. Spring 接入

```yaml
nextentity:
  enabled: true
  jpa:
    string-parameter-binding: true   # 字符串条件转参数绑定而非字面量
```

`AppRepository` 子接口由自动配置实现并注册为 Bean；`EntityManager` 由 Spring Boot 提供。

## 12. 何时不用本库

- 纯 CRUD、无动态条件 → 直接用 Spring Data 派生查询。
- 复杂报表 SQL（窗口函数、多表聚合、MySQL 方言函数）→ 用 `@Query` 原生查询，或 `Spec.raw(...)` 片段。
- 需要实体图/级联策略深度控制 → 用 Hibernate 原生 API。
