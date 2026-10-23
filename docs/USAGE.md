# USAGE — 使用正文

本文只讲**怎么写**。规则与门禁见 [AGENTS.md](../AGENTS.md)，架构与决策见 [DESIGN.md](DESIGN.md)，版本变更见 [CHANGELOG.md](../CHANGELOG.md)。

> 本文每段示例都对 `nextentity-api` / `core` / `spring` / `jpa` 的真实产物做过 `javac` 编译验证（验证脚本按 AGENTS「临时脚本用完即删」不随仓库交付）。**本库不提供 JPA 静态元模型**（没有 `Order_`），字段一律用方法引用。

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
- `like(pattern)` / `likeIfNotNull(pattern)` → LIKE，**模式自带通配符**（`%` 由调用方掌握）；`likeIfNotNull(null)` 整条条件跳过。
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

### 3.4 列对列比较

右侧可以是一条属性路径，比较发生在数据库侧（不会把列值取回内存再比）：

```java
// 两列相等：customerId = id
query().where(Order::getCustomerId).eq(Path.of(Order::getId)).list();

// 两列比较：amount 与另一个数额列（>, >=, <, <=, <>）
query().where(Order::getAmount).gt(Path.of(Order::getAmount)).list();
query().where(Order::getAmount).ge(Path.of(Order::getAmount)).list();
query().where(Order::getAmount).lt(Path.of(Order::getAmount)).list();
query().where(Order::getAmount).le(Path.of(Order::getAmount)).list();
query().where(Order::getAmount).ne(Path.of(Order::getAmount)).list();

// 区间两端都可以是表达式
query().where(Order::getAmount).between(Path.of(Order::getAmount), Path.of(Order::getAmount)).list();
```

比较按**数据库的排序规则**进行：字符串列的大小写敏感性、尾随空格处理由方言决定（H2 与 MySQL 可能不同），不要拿它做跨库一致的业务语义。`isNull()` / `in(...)` 仍只接受属性，不接受表达式主语。

### 3.5 表达式算术

算术算子只出现在数值路径（`Path.of(...)` 返回 `NumberPath`）与算术结果上：`add` / `sub` / `mul` / `div` / `mod`。

```java
// 取模：qty 对 3 取余等于 0
query().where(Path.of(Order::getQty).mod(3).eq(0)).list();

// 加减乘除后再比较：amount + 1 > v（sub / mul / div 同理）
query().where(Path.of(Order::getAmount).add(BigDecimal.ONE).gt(v)).list();
query().where(Path.of(Order::getAmount).sub(BigDecimal.ONE).gt(v)).list();
query().where(Path.of(Order::getAmount).mul(BigDecimal.TEN).gt(v)).list();
query().where(Path.of(Order::getAmount).div(BigDecimal.TEN).gt(v)).list();

// 链式算术：先对 3 取余，再 +1
query().where(Path.of(Order::getQty).mod(3).add(1).eq(0)).list();

// 算术结果也能进 select
List<BigDecimal> shifted = query().select(Path.of(Order::getAmount).add(BigDecimal.ONE)).list();
```

算术挂在 `Path.of(...)` 上，不是挂在 `where(...)` 上：`where(PathRef)` 返回的属性算子**故意不带**算术——`PathOperator<T, U, B>` 的 `U` 没有 `Number` 上界，若给它加上 `mod`，`where(Order::getOrderNo).mod("x")` 这类写法会在编译期合法、到渲染期才炸。所以先用 `Path.of(Order::getQty)` 拿到数值路径（`NumberPath`，与 `sum()` 同源），再写 `mod(3).eq(0)`（即上面第一行示例）。

另外：`div` / `mod` 的除数为 0 时行为由数据库决定（不抛 Java 异常）。

### 3.6 逻辑组合：`not()` 与 n 元 `and` / `or`

```java
// 取反
Predicate<Order> notShipped = Path.of(Order::getShippedAt).isNull().not();
query().where(notShipped).list();

// n 元：所有子条件一次交进去（保留分组，不拍平）
Predicate<Order> allOf = Path.of(Order::getStatus).eq(OrderStatus.PAID).and(others);
Predicate<Order> anyOf = Path.of(Order::getStatus).eq(OrderStatus.PAID).or(others);
query().where(allOf).list();
query().where(anyOf).list();
```

两条容易踩的语义：

- `and(空集合)` 与 `or(空集合)` 都等于**接收者本身**：`a ∧ 空 = a`、`a ∨ 空 = a`。空集合的意思是「没有附加条件」，不是「整条条件消失」——`where(租户边界).and(动态条件列表)` 在列表为空时若丢掉接收者，就是静默放行全表。
- `or(...)` 里混入被短路的条件（例如 `eqIfNotNull(null)`）会让**整条 `or` 恒真**——短路条件的含义就是「恒真」。拼可选 OR 条件时，先自己判空再决定要不要调用 `or`。

### 3.7 `notBetween` 与纯 `like`

```java
// 区间取反
query().where(Order::getAmount).notBetween(min, max).list();

// 纯 LIKE：模式自带 % / _，本方法不拼
query().where(Order::getOrderNo).like("A%").list();
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
List<Order> list = query().list();     // 全部
List<Order> top = query().list(50);    // 前 50 条
Order first = query().first();         // 首条或 null
Order one = query().single();          // 唯一一条或 null；命中多条抛 IllegalStateException
long total = query().count();
boolean exists = query().exists();
```

`single()` 命中多于一条时抛 `IllegalStateException("found more than one result, expected one")`，不做静默取首条。

聚合或分组规格上的 `count()` 会抛 `UnsupportedOperationException`（SQL 需子查询计数，3.x 未实现）；`exists()` 不受影响，语义是「有行」。

## 6. 投影

```java
// 单列
List<Long> ids = query().select(Order::getId).list();

// 双列 → Tuple2（具名 get0 / get1）
List<Tuple2<Long, OrderStatus>> rows =
        query().select(Path.of(Order::getId), Path.of(Order::getStatus)).list();

// 三列及以上 → Tuple（按列下标取值，列数 size()）
List<Tuple> rows3 = query().select(
                Path.of(Order::getId), Path.of(Order::getStatus), Path.of(Order::getAmount))
        .list();
Object id0 = rows3.getFirst().get(0);

// 去重单列
List<Long> uniq = query().distinct().select(Order::getCustomerId).list();
```

投影参数可以是 `Path.of(...)`、聚合/算术结果，也可以是裸方法引用（`PathRef` 本身就是 `Expression`）：`select(Order::getId)` 走 `select(PathRef)` 重载；把 `PathRef` 放进 `List<Expression<T, ?>>`（即 `expressions()` 的声明类型）后同样可用，两种形态由同一个转换点 `core.builder.ValueProjections.of(...)` 处理。`Tuple2` 也是 `Tuple`（`size()` 恒为 2），所以「2 列用 `Tuple2`、3 列起用 `Tuple`」是同一个体系；映射器路径是例外——它无论几列都给按位置取值的 `Tuple`，2 列也不保证是 `Tuple2`（见 §6.1）。

`distinct()` 对四种投影形态都适用（单列、`Tuple2`、3 列起 `Tuple`、映射器）。单列时 `count()` / `slice(...)` 可用：`count()` 数的是**不同的投影值**（数据库侧 `count(distinct 列)`），不是实体行数——两者在列有 `null` 或重复时会不同。**多列去重的 `count()` 抛 `UnsupportedOperationException`**：`COUNT(DISTINCT a, b)` 在 MySQL 与 PostgreSQL 形态不同，只数第一列会静默给出错数。

### 6.1 显式映射器：列集与结果类型由调用方决定

列在编译期定不下来时（报表字段随需求变、按请求参数组装列），用 `project(ProjectionMapper)`：一个对象同时声明「查哪些列」和「怎么造结果」，列与取值不可能被传错配。下面的例子按店铺汇总已支付订单的金额：

```java
final class ShopTotalMapper implements ProjectionMapper<Order, ShopTotal> {

    // 下标常量紧邻 expressions()：列序一改，这里就看得见
    private static final int IDX_SHOP_ID = 0;
    private static final int IDX_TOTAL = 1;

    @Override
    public List<Expression<Order, ?>> expressions() {
        return List.of(
                Path.of(Order::getShopId),              // 分组列
                Path.of(Order::getAmount).sum());       // 聚合列
    }

    @Override
    public ShopTotal map(Tuple row) {
        return new ShopTotal((Long) row.get(IDX_SHOP_ID), (BigDecimal) row.get(IDX_TOTAL));
    }
}

record ShopTotal(Long shopId, BigDecimal total) {}

List<ShopTotal> totals = query().project(new ShopTotalMapper())
        .where(Order::getStatus)
        .eq("PAID")
        .groupBy(Order::getShopId)
        .orderBy(Order::getShopId)
        .asc()
        .list();
```

投影列与 `groupBy` 必须自洽：非聚合列要么出现在 `GROUP BY` 里（如上例的 `shopId`），要么在不分组时只投影聚合；否则数据库会按 `ONLY_FULL_GROUP_BY` 一类规则拒绝——`javac` 看不出这类错误。

三条契约：

1. **单列也是 `Tuple`**：`map(Tuple)` 收到的行永远按位置装配，列数等于 `expressions().size()`；单列时是长度为 1 的 `Tuple`（`Tuple#size()` 的下限是 1，不是 2），不会直接给裸值。
2. **映射器必须无状态**：可以做成单例（如 Spring bean）跨查询复用，`map` 不得依赖上一次调用留下的状态。
3. **一次查询只有一份投影**：`expressions()` 每次 `project(...)` 求值一次；在同一个查询入口上再调 `select(...)` / `project(...)` 会整体替换掉映射器（反之亦然）——互相覆盖，不报错。

⚠️ 聚合表达式没配 `groupBy` 时，SQL 把整表当一个分组：**恒返回 1 行**（空表也一样：`count` 给 `0`、其余给 `null`）。漏写 `groupBy` 不会报错，只会拿到一行总数：

```java
final class OrderTotalMapper implements ProjectionMapper<Order, BigDecimal> {

    @Override
    public List<Expression<Order, ?>> expressions() {
        return List.of(Path.of(Order::getAmount).sum());
    }

    @Override
    public BigDecimal map(Tuple row) {
        return (BigDecimal) row.get(0);
    }
}

// 整表合计：1 行，而不是「每个分组一行」
BigDecimal total = query().project(new OrderTotalMapper()).single();
```

本库不做反射/注解自动映射（`select(OrderRow.class)` 那类，见 §14）：映射代码由调用方手写，编译期可查、IDE 可跳转。

## 7. 聚合与分组

```java
long n = query().count();
BigDecimal sum = query().select(Path.of(Order::getAmount).sum()).single();
BigDecimal max = query().select(Path.of(Order::getAmount).max()).single();
BigDecimal min = query().select(Path.of(Order::getAmount).min()).single();
Double avg = query().select(Path.of(Order::getAmount).avg()).single();
long distinctCustomers =
        query().select(Path.of(Order::getCustomerId).countDistinct()).single();
```

| 聚合 | 结果类型 | 说明 |
|---|---|---|
| `count()` | `Long` | 无行时为 `0`；数的是投影列非空的行 |
| `countDistinct()` | `Long` | `null` 不计入，可能小于 `count()` |
| `max()` / `min()` | 属性类型 | 任意可比较属性（含 `Path.of(Order::getCreatedAt).max()`） |
| `avg()` | `Double` | 与各方言 `AVG` 的返回类型一致；要 `BigDecimal` 自行转换 |
| `sum()` | 属性类型 | 仅数值路径（`NumberPath`） |

聚合结果是 `AggregateValue`：可比较、可区间，但**不能再接聚合**——`max().max()`、`count().count()` 编译期就写不出来。

### 7.1 分组 + HAVING

```java
// 每个客户的订单数，只看超过 5 单的
List<Tuple2<Long, Long>> busyCustomers = query().select(
                Path.of(Order::getCustomerId), Path.of(Order::getId).count())
        .where(Order::getCreatedAt)
        .ge(from)
        .groupBy(Order::getCustomerId)
        .having(Path.of(Order::getId).count().gt(5L))
        .list();
```

`having(...)` 接在 `groupBy(...)` 之后。它**不强制**要求先分组：SQL 允许不带 `GROUP BY` 的 `HAVING`（全表聚合），此时它过滤的是聚合结果：

```java
// 全表最大金额，且只在它 > 10 时才返回一行
List<BigDecimal> bigMax = query().select(Path.of(Order::getAmount).max())
        .having(Path.of(Order::getAmount).max().gt(BigDecimal.TEN))
        .list();
```

### 7.2 多列分组

```java
List<Tuple> perStatusAndCustomer = query().select(
                Path.of(Order::getStatus),
                Path.of(Order::getCustomerId),
                Path.of(Order::getId).count())
        .groupBy(Order::getStatus, Order::getCustomerId)
        .list();
```

多次 `groupBy(...)` 与一次传多列等价（追加，不是替换）。

### 7.3 边界

- 聚合 / 分组规格上的 `count()` 抛 `UnsupportedOperationException`（见 §5）；`exists()` 仍可用，语义是「有行」。
- 需要「组内实体行数」时请投影主键等非空列：`count(列)` 不数 `NULL`（与 2.2.3 相同）。
- `avg()` 是 `Double`；跨方言的精度/舍入差异由数据库决定，不要拿它做金额结算。

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
// 只用自研枚举：api 不依赖任何持久化类型
Order pessimistic = query().where(Order::getId).eq(id).lock(LockMode.PESSIMISTIC_WRITE).single();
```

`lock(...)` 必须跟在产生条件的算子之后（例如 `.eq(id).lock(...)`），返回终止符。`LockMode` 的 5 个常量与 `jakarta.persistence.LockModeType` 同名（同名只为可读，`api` 并不依赖 jakarta），由 `nextentity-jpa` 映射成后端的锁模式。

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
| DTO/record 投影 | `select(OrderRow.class)` | `project(mapper)` 手写映射（§6.1）；或双列用 `Tuple2`、三列及以上用 `Tuple` 自己映射（§6） |
| 按条件更新/删除 | `update().set(..).where(..)` / `delete().where(..)` | `@Modifying @Query` |
| 子查询 | `toSubQuery()` | `@Query` 原生/JPQL |
| `*IfPresent` 命名 | `eqIfPresent(..)` | `eqIfNotNull(..)`（语义相同） |
| `notLike` / `startsWith` / `endsWith` | 字符串算子 | `contains(...)` / `like("v%")` / `like("%v")` |
| `sliceWithoutCount` | 免 count 分页 | 用 `@Query` 取 `List` 自己算 |


