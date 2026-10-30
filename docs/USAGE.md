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
- **值里的 `%` / `_` 不转义**：`contains("50%")` 实际下发 `%50%%`，`%` 仍按 LIKE 通配符解释；要匹配字面 `%` / `_`，请改用 `like(...)` 并自己写转义（`like("%50\\%%")`）。`contains` 只做拼接，不做转义。
- `like(pattern)` / `likeIfNotNull(pattern)` → LIKE，**模式自带通配符**（`%` 由调用方掌握）；`likeIfNotNull(null)` 整条条件跳过。
- 布尔字段请写 `.eq(true)`，不支持「把字段本身当条件」。两种写法的失败方式不同，别混：`where(Order::getActive)` 命中**算子步骤**重载，本身**不产生任何条件**（等价于没写这条 `where`，不加过滤）；把布尔路径当 `Expression<Boolean>` 传进 `where(...)` / `having(...)` / `and` / `or`，则**当场抛 `IllegalArgumentException`**。

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

### 3.8 前缀/后缀与取反匹配

3.1.0 起恢复 `startsWith` / `endsWith` / `notLike` 与取反族（2.2.3 的 `EmployeeRepository.java` 在用：`.startsWith(prefix)`、`.endsWith(suffix)`、`.notLike(pattern)`、`.notStartsWith(...)`、`.notEndsWith(...)`、`.notContains(...)`）。

**入口只有 `where(...)`**：这些方法挂在 `where(...)` 返回的 `StringOperator` 上，`Path.of(...)` / `EntityPath...get(...)` 得到的 `StringPath` 没有它们（保持原有 `StringOps`）。所以写 `query().where(Employee::getName).startsWith(prefix)`，不要写 `Path.of(Employee::getName).startsWith(...)`——后者编译不过，属于**不提供**（理由与代价见 §15.2）。

通配符由 api 默认方法拼（`startsWith(v)` 等价 `like(v + "%")`，`notContains(v)` 等价 `notLike("%" + v + "%")`），`notLike` 编译成 `NOT(LIKE)`，后端只认 `LIKE` / `NOT LIKE`，与上一节的纯 `like` 共用渲染路径：

```java
// 前缀 / 后缀 / 取反：替你拼通配符
query().where(Order::getOrderNo).startsWith("A").list();
query().where(Order::getOrderNo).endsWith("9").list();
query().where(Order::getOrderNo).notContains("X").list();

// 纯 NOT LIKE：模式自带 % / _
query().where(Order::getOrderNo).notLike("_A%").list();
```

NULL 语义照 SQL：`NOT LIKE` 对 `null` 列求值为 `UNKNOWN`，所以 `notContains(v)` **不匹配**该列为 `null` 的行；要连 `null` 行一起算，写 `or(notContains(v), isNull())`。`*IfNotNull` / `*IfNotEmpty` 变体在参数为 `null`（或空串）时短路成「不加这条条件」，**不是**匹配 `null` 行——`startsWithIfNotEmpty("")` 必须短路，不能退化成 `LIKE '%'`（否则静默放宽查询范围）。

### 3.9 字符串函数

3.1.0 起恢复 `lower` / `upper` / `trim` / `substring` / `length`（2.2.3 的 `EmployeeRepository.java` 里有 `.lower().contains(...)`；2.2.3 用例 `StringExpressionImplTest` 覆盖全部五函数）。函数是算子主语的一部分，可以继续链式比较：

```java
// 转小写后包含
query().where(Order::getOrderNo).lower().contains("a").list();

// 截取后相等：substring(offset, length)，offset 从 0 起算
query().where(Order::getOrderNo).substring(0, 3).eq("A01").list();

// 长度比较：length() 返回数字算子，可继续 gt / between 等
query().where(Order::getOrderNo).length().gt(5).list();
```

每层函数各是一个 spec 节点，谓词主语由内到外组装（`lower().trim().contains("ann")` 的主语是 `TRIM(LOWER(name))`）；`substring(int)` 省略长度时上界取 `StringFunctions.MAX_SUBSTRING_LENGTH`（沿用 2.2.3 `StringExpression` 的取值）。两条边界：函数结果**不能**再接 `in` / `notIn` / `isNull` / `isNotNull`（`in`/`isNull` 的语义针对属性，对函数结果没有确定的 SQL 对应物，命中抛 `UnsupportedOperationException`）；`length()` 返回 `NumberOperator`，只能继续比较/区间，**不能**作为 `select(...)` 的投影列——要投影长度先自己查出来再在内存计算。

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

// 取本次请求的参数，分页组件不必另存一份
int offset = slice.offset();
int limit = slice.limit();

// 映射成消费方自己的分页类型
Page<Order> page = slice.to(s -> new Page<>(pageable, s.data(), s.total()));
```

`slice(offset, limit)` 会额外发一条 count；`total()` 是满足条件的总行数。`Slice.offset()` / `limit()` 返回**本次入参的原值**——包括 `offset >= total` 的短路路径（那里不再执行列表查询，但参数照样保留）；3.0.0 没有这两个访问器，3.1.0 恢复（2.2.3 的调用点：`PaginationOrderingIntegrationTest.java:273-274`、`:296-297`）。二者是 `Slice` 上的 `default` 方法：自己实现 `Slice` 时若不覆盖，`offset()` 取 `0`、`limit()` 取 `data().size()`；内置分页返回的实现一律覆盖成入参原值。

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

**聚合投影**（`select(sum).count()` 这类，SQL 需要子查询计数）的 `count()` 仍抛 `UnsupportedOperationException`；**分组规格**上的 `count()` / `exists()` / `list(offset, limit)` 已在 3.1.0 恢复（见 §7.4），两者口径不同，不要混用。

## 6. 投影

```java
// 单列
List<Long> ids = query().select(Order::getId).list();

// 双列 → Tuple2（具名 get0 / get1）
List<Tuple2<Long, OrderStatus>> rows =
        query().select(Path.of(Order::getId), Path.of(Order::getStatus)).list();

// 三列 → Tuple3（具名 get0~get2，列序写反是编译错误）
List<Tuple3<Long, OrderStatus, BigDecimal>> rows3 = query().select(
                Path.of(Order::getId), Path.of(Order::getStatus), Path.of(Order::getAmount))
        .list();
BigDecimal amount0 = rows3.getFirst().get2();

// 去重单列
List<Long> uniq = query().distinct().select(Order::getCustomerId).list();
```

投影参数可以是 `Path.of(...)`、聚合/算术结果，也可以是裸方法引用（`PathRef` 本身就是 `Expression`）：`select(Order::getId)` 走 `select(PathRef)` 重载；把 `PathRef` 放进 `List<Expression<T, ?>>`（即 `expressions()` 的声明类型）后同样可用，两种形态由同一个转换点 `core.builder.ValueProjections.of(...)` 处理。`Tuple2`/`Tuple3`~`Tuple6` 都是 `Tuple` 的 record 实现（值语义 + 具名访问器 + 按位置 `get(int)`），所以「双列 `Tuple2`、三列 `Tuple3`、四~六列 `ProjectionMappers`」是同一个体系（见 §6.2）；自定义映射器路径是例外——它无论几列都给按位置取值的通用 `Tuple`，不要强转具名元组（见 §6.1）。

`distinct()` 对所有投影形态都适用（单列、`Tuple2`、`Tuple3`、`ProjectionMappers` 的元组、自定义映射器）。单列时 `count()` / `slice(...)` 可用：`count()` 数的是**不同的投影值**（数据库侧 `count(distinct 列)`），不是实体行数——两者在列有 `null` 或重复时会不同。**多列去重的 `count()` 抛 `UnsupportedOperationException`**：`COUNT(DISTINCT a, b)` 在 MySQL 与 PostgreSQL 形态不同，只数第一列会静默给出错数。

### 6.2 四列及以上：`ProjectionMappers` 与 `andThen`

四列起没有 `select(...)` 重载（`SelectStep` 的方法数有上限），改用 `ProjectionMappers.select(...)` 产出的映射器；结果仍是**具名且带类型参数**的元组，`get0()` 直接是列的类型，调用方零转型：

```java
List<Tuple4<Long, String, Integer, ItemStatus>> rows = query().project(ProjectionMappers.select(
                Path.of(Item::getId), Path.of(Item::getName),
                Path.of(Item::getScore), Path.of(Item::getStatus)))
        .list();
Long id = rows.getFirst().get0();
```

想去掉元组、直接要自己的类型，接一个 `andThen`——列集不变，只换结果类型：

```java
List<Row> rows = query().project(ProjectionMappers
                .select(Path.of(Item::getId), Path.of(Item::getName),
                        Path.of(Item::getScore), Path.of(Item::getStatus))
                .andThen(tuple -> new Row(tuple.get0(), tuple.get1())))
        .list();
```

**封顶六列是硬约束**：工厂方法的参数个数就是元组宽度，而项目规则 `ExcessiveParameterListSix` 把参数上限定在 6。七列以上只能用自定义 `ProjectionMapper`，在 `map(Tuple)` 里自己转型——那是唯一没有静态检查的路径。
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

本库不做注解自动装配列集（列随 DAO 需求变时用 `project(ProjectionMapper)`，见 §6.1）；列集写死在 DTO 上时用 §6.3 的 `select(OrderRow.class)`，那是 3.1.0 起提供的显式类型投影。

`expressions()` 里可以放**嵌套路径**（`EntityPath.of(Order::getShop).get(Shop::getName)`）——它就是 2.2.3 `@EntityPath("department.name")` 那种「列来自关联表」的等价形态（`core.builder.ValueProjections` 接受任意 `Path`，渲染时由后端把嵌套路径解析成 join）。所以 `select(Class)` 不覆盖嵌套/改名只是**入口形状不同**，能力仍可达。

### 6.3 按类型投影：`select(OrderRow.class)`

列集在编译期就固定、并且想要自己的结果类型时，用 `select(Class)`（3.1.0 恢复；2.2.3 的 `EmployeeRepository.java` 在用：`select(EmployeeSummary.class)`、`select(EmployeeWithDept.class)`、`selectDistinct(EmployeeInfo.class)`）：

```java
// record：属性名取规范构造器参数名，列序即参数序
record OrderRow(Long id, OrderStatus status, BigDecimal amount) {}

List<OrderRow> rows = query().select(OrderRow.class)
        .where(Order::getStatus).eq(OrderStatus.PAID)
        .orderBy(Order::getId).asc()
        .list();

// 去重投影：distinct() 在前、select(Class) 在后（3.1.0 没有单独的 selectDistinct(Class) 重载）
List<OrderRow> uniq = query().distinct().select(OrderRow.class).list();
```

装配契约（以 3.1.0 实现为准）：

- **record**：属性名取 record component 名，按名匹配实体的单层属性；构造器用规范构造器，列序即参数序；
- **普通类**：属性名由公开的 `setXxx` 按 JavaBeans 规则推导（`setName` → `name`，`setURL` → `URL`），需要无参构造器，**列序按属性名排序**（反射方法序不保证稳定）；
- **只做扁平映射**：属性名必须是实体的**单层**属性（`name` 可以，`department.name` 不行）。2.2.3 的 `@EntityPath("department.name")` 这类嵌套/改名声明在 3.1.0 的 `select(Class)` 里**不提供**——嵌套、改名、计算列改用 `project(ProjectionMapper)` 手写映射器；
- 接口与抽象类不支持（接口投影要靠运行期代理）：抛 `IllegalArgumentException` 并提示改用映射器；DTO 无可映射属性、普通类缺无参构造器同样在 `select(...)` 阶段抛 `IllegalArgumentException`；
- 属性名写错**无法在编译期校验**（实体属性清单不在编译期可见），会在后端渲染/装配时失败，不会静默跳过；
- 目标类型恰好是实体类型本身时退化为整实体查询；此时 `distinct()` 不可用——`SELECT DISTINCT 实体` 表达不出来，运行期抛 `UnsupportedOperationException`，不让旗标静默失效。

> 边界：`select(Class)` 只负责「列 → 对象」的装配，不负责懒加载——它查的是列，不是实体图；要预取关联仍用 §8.1 的 `fetch(...)`。

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
List<Tuple3<OrderStatus, Long, Long>> perStatusAndCustomer = query().select(
                Path.of(Order::getStatus),
                Path.of(Order::getCustomerId),
                Path.of(Order::getId).count())
        .groupBy(Order::getStatus, Order::getCustomerId)
        .list();
```

多次 `groupBy(...)` 与一次传多列等价（追加，不是替换）。

### 7.3 边界

- **聚合投影**上的 `count()` 抛 `UnsupportedOperationException`（见 §5），因为它数的是「总行数」而查询问的是「聚合值」；**分组规格**上的 `count()` / `exists()` / `list(offset, limit)` 见 §7.4。
- 需要「组内实体行数」时请投影主键等非空列：`count(列)` 不数 `NULL`（与 2.2.3 相同）。
- `avg()` 是 `Double`；跨方言的精度/舍入差异由数据库决定，不要拿它做金额结算。

### 7.4 分组之后的 `count()` / `exists()` / 分页

3.1.0 起，`groupBy(...)`（及 `having(...)`）之后的终止符按**分组结果**语义执行（2.2.3 的 `HavingClauseIntegrationTest` 整类 22 条都是这种写法）：

```java
// 组数：满足 having 的分组个数，不是「总行数」
long busyGroups = query().select(Path.of(Order::getCustomerId), Path.of(Order::getId).count())
        .groupBy(Order::getCustomerId)
        .having(Path.of(Order::getId).count().gt(5L))
        .count();

// 是否存在任何满足 having 的分组
boolean any = query().select(Path.of(Order::getCustomerId), Path.of(Order::getId).count())
        .groupBy(Order::getCustomerId)
        .having(Path.of(Order::getId).count().gt(5L))
        .exists();

// 对分组结果分页：offset/limit 作用在「组」上
List<Tuple2<Long, Long>> page = query().select(
                Path.of(Order::getCustomerId), Path.of(Order::getId).count())
        .groupBy(Order::getCustomerId)
        .having(Path.of(Order::getId).count().gt(5L))
        .orderBy(Path.of(Order::getId).count()).desc()
        .list(10, 10);
```

`count()` / `exists()` 的语义是**分组结果的行数 / 是否存在任何分组**（即「组数」「有没有组」），不是「组内行数」，也不是「总行数」；两者都必须把 `groupBy` 与 `having` 一起渲染（只渲染 `where` 会把总行数当组数、或把被 `having` 滤空的组答成「有行」，结论正好相反）——这是 3.0.0 拒绝它们的理由，也是 3.1.0 恢复时必须有 SQL 快照钉住的原因。分组后 `list(offset, limit)` 与 `slice(...)` 都可用：窗口作用在「组」上，`slice(...)` 的 `total` 就是组数（与 `count()` 同一口径，因此同样依赖上面那条 JPA 组计数实现）。**没有 `groupBy` 的聚合形态**（只有聚合投影或只有 `having`）仍由后端拒绝 `count()` / `exists()`：这种查询恒为一行，计数/存在性给不出稳定答案——这是 JDBC 与 JPA 共同的后端契约，与 `list` / `slice` 的取行路径无关（渲染顺序把窗口放在 `groupBy`/`having` 之后，见 `nextentity-jdbc` 的 `SqlRenderer#render()`；JPA 侧是 `setFirstResult`/`setMaxResults`）。

> **后端差异（JPA）**：标准 JPA Criteria 表达不了「`FROM` 子查询」，JPA 侧的分组 `count()` 选定为反射 Hibernate 的 `JpaCriteriaQuery#createCountQuery()`，在 **Hibernate 6+ 下单条 SQL** 实现。换成其它 JPA provider 会 **fail-fast 抛 `UnsupportedOperationException`（文案指名 provider）**，不会静默数错；此时用 `exists()`（不涉及计数）或改走 JDBC 后端。JDBC 侧是派生表原生表达，不受这条限制。

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

**集合关联与反侧关联不在查询路径上提供**：`@OneToMany`/`@ManyToMany`/`@ElementCollection` 以及 `@OneToOne(mappedBy=...)` 反侧，写入列集里会被跳过（见 DESIGN §7.1 CUT-18）；查询路径穿过它们（例如 `EntityPath.of(Order::getItems).get(Item::getName)`）由**两个后端都显式拒绝**——JPA 在 `JpaPaths`、JDBC 在 `SqlPaths` 抛 `UnsupportedOperationException`（集合 join 出来是子行，`list()` 会重复、分页与 `total` 也会失去意义）。这不是「静默忽略」：静默只发生在写入列集上，查询路径是 fail-fast 的。

### 8.1 显式 `fetch(...)`：把 N+1 消掉

3.0.0 没有显式预取，注释里写「用 `@EntityGraph` 或自己查投影列」；3.1.0 恢复 `fetch(...)`（2.2.3 的 `EmployeeRepository.java` 在用：`fetch(Employee::getDepartment)` 等，`ProductRepository.java` 的 `fetch(Product::getCategory)` 同形）。签名已定稿（`FetchStep` 共 6 个方法）：

```java
// 单个关联（方法引用形态走 PathRef 重载）
List<Order> orders = query()
        .fetch(Order::getCustomer)
        .where(Order::getStatus).eq(OrderStatus.PAID)
        .list();

// 多个关联一次传（便捷重载最多 3 条 Path；4 条以上用 fetch(Collection<Path>)）；嵌套关联用 Path 逐段 get
List<Order> loaded = query()
        .fetch(Path.of(Order::getCustomer),
                Path.of(Order::getCustomer).get(Customer::getOrg),
                Path.of(Order::getShop))
        .where(Order::getStatus).eq(OrderStatus.PAID)
        .list();
```

与 2.2.3 的四个形状差异（照抄 2.2.3 示例会编译不过）：

- `FetchStep` 共 6 个方法：抽象 `fetch(Collection<? extends Path<T, ?>>)` + 1/2/3 条 `Path` 与 1/2 条 `PathRef` 的 `default` 重载。3.x 刻意不让 `Path` 继承 `PathRef`（避免给 `Path` 的实现方增加源码义务），两种形态靠 `Path.of(...)` 互转。
- 2.2.3 的 7 个方法里 `fetch(PathRef, PathRef, PathRef)` 不在 3.1.0（撞「接口方法数 ≤ 6」的窄度规则）；三个关联写 `fetch(Path, Path, Path)`。
- **不能链两次 `fetch`**：`fetch(...)` 返回 `BaseWhereStep`，它没有 `fetch`；多个关联一次多参传。2.2.3 Javadoc 里的 `.fetch(a).fetch(b)` 本身编译不过，不要照抄。
- `select(...)` / `project(...)` 之后没有 `fetch`：投影行不是实体，没有关联可填。

`fetch(...)` 收的是关联路径（`@ManyToOne` / `@OneToOne` 等），普通列用 `select(...)`。语义上它改变「发几条 SQL」，不改变结果集：JPA 后端渲染为 `LEFT JOIN FETCH`（同一条 SQL 载入关联，嵌套 fetch 复用前缀，见 `JpaPaths`）；**JDBC 后端调用 `fetch(...)` 抛 `UnsupportedOperationException`**——JDBC 不组装实体图、没有懒加载代理，`fetch` 在该后端没有可兑现的语义，与其静默 no-op 让用户拿到 `null` 关联，不如 fail-fast（理由类型=架构约束，见 §13.4.3）。替代路径：用 `project(ProjectionMapper)` 显式取关联列（LEFT JOIN 可用），或改用 JPA 后端。验证「真的没有 N+1」请用 SQL 条数对照（同一查询 fetch 前后各数一次语句数），不要只看结果有没有值。

## 9. 锁

```java
// 只用自研枚举：api 不依赖任何持久化类型
Order pessimistic = query().where(Order::getId).eq(id).lock(LockMode.PESSIMISTIC_WRITE).single();
```

`lock(...)` 必须跟在产生条件的算子之后（例如 `.eq(id).lock(...)`），返回终止符。`LockMode` 的 5 个常量与 `jakarta.persistence.LockModeType` 同名（同名只为可读，`api` 并不依赖 jakarta），由 `nextentity-jpa` 映射成后端的锁模式。

3.1.0 起 JDBC 后端也提供 `PESSIMISTIC_READ`（JPA 后端自 3.0.0 起已按名字映射五种锁模式，本版只补验证与文档；2.2.3 在 MySQL + PostgreSQL 双容器验证过这套读锁用法，写法与 `PESSIMISTIC_WRITE` 同形）：JDBC 后端由方言 `lock` 钩子渲染共享锁（MySQL 与 PostgreSQL 都是 `for share`，**两条容器通道均已实测**：MySQL 容器；PostgreSQL 容器的 `PostgresItemLockIntegrationTest` 2 条、`PostgresLockContractIntegrationTest` 4 条全绿，且与 MySQL 通道跑同一批 590 条用例；H2 没有 `FOR SHARE`，用 `for update` 兜底——比 FOR SHARE 锁更强，是安全方向的近似，不是等价降级；SQL Server 方言不做行锁，显式拒绝；未实现的方言显式拒绝，**不静默降级成写锁**）。JDBC 后端只接受 `PESSIMISTIC_WRITE` 与 `PESSIMISTIC_READ`，其余三种模式显式拒绝（`UnsupportedOperationException`，不静默降级成写锁）。

`lock(...)` 之后还能继续分页（3.1.0 在 `Collector` 上加的重载，`lock(...)` 返回类型没变）：

```java
// 只锁本页的行，不是整表
List<Order> page = query()
        .where(Order::getStatus).eq(OrderStatus.PAID)
        .orderBy(Order::getId).asc()
        .lock(LockMode.PESSIMISTIC_WRITE)
        .list(20, 20);   // offset, limit（Collector 的 list(int, int) 重载）
```

锁只适用于**实体查询**：值选择（`select(...)`）或聚合规格上调用 `lock(...)` 抛 `UnsupportedOperationException`（H2 不接受 `FOR UPDATE` + `DISTINCT`/`GROUP BY`，方言行为不一致，本库不猜）；SQL Server 方言不做行锁，`lock` 在该方言上显式拒绝（理由见 [DESIGN.md](DESIGN.md) §7.1 CUT-20/CUT-22）。

## 10. 写入

写入走 `core.exec.PersistExecutor`（`EntityOperations` 即实现它）：四个实体写入方法，加上 3.1.0 新增的两个按条件写入入口（§10.1）：

```java
EntityOperations<Order> operations = factory.operations(Order.class);
operations.insert(order);
operations.insertAll(orders);
operations.update(order);       // 含 @Version 校验，并把新版本号回写到入参对象
operations.updateAll(orders);
```

`@Version` 字段的实体走乐观锁：版本冲突抛 `OptimisticLockException`，由调用方决定重试策略。

### 10.1 按条件更新与删除

3.1.0 恢复 2.2.3 的批量写入步骤（2.2.3 examples 在用：`EmployeeRepository.java` 的 `update().set(...).set(...).where(...).execute()` 与 `delete().where(...).execute()`）。入口在 `PersistExecutor`：`update()` 返回 `UpdateSetStep`、`delete()` 返回 `DeleteWhereStep`，`execute()` 返回受影响行数。签名已定稿——`set(...)` 有两个重载：`PathRef`（方法引用形态）与 `Path`（嵌套列 / 关联外键）：

```java
// 一条 UPDATE ... WHERE ...，不是「先查出来逐行改」
int deactivated = operations.update()
        .set(Employee::getActive, false)
        .set(Employee::getStatus, EmployeeStatus.INACTIVE)
        .where(Employee::getDepartmentId).eq(departmentId)
        .execute();

// 一条 DELETE ... WHERE ...
int purged = operations.delete()
        .where(Employee::getStatus).eq(EmployeeStatus.INACTIVE)
        .execute();

// set 的 Path 重载：嵌套 @Embedded 列，以及把关联外键置空
int relocated = operations.update()
        .set(Path.of(Employee::getAddress).get(Address::getCity), "Platform")
        .where(Employee::getStatus).eq(EmployeeStatus.INACTIVE)
        .execute();
int clearedFk = operations.update()
        .set(Path.of(Employee::getDepartment).get(Department::getId), null)
        .where(Employee::getStatus).eq(EmployeeStatus.INACTIVE)
        .execute();
```

`set(...)` 的方法引用重载只接受标量列；嵌套列与「把外键置空」必须用 `Path` 重载（`PathRef` 表达不了 `null` 与逐段路径）。右值只支持**字面量**：不支持「列 = 列」或引用了其它列的表达式；`value = null` 表示置 `NULL`。条件面复用 §3 的谓词（含 §3.1 的可选条件与 §3.6 的组合），且条件在语句里直接求值，不先取行。`execute()` 返回受影响行数；批量写不经过实体快照，因此不触发级联。

**并发语义（与 2.2.3 一致）**：条件更新/删除**不参与乐观锁**——既不校验、也不自增 `@Version`，也不回写调用方对象（Hibernate 的 `CriteriaUpdate`/`CriteriaDelete` 与 JDBC 批量语句都不施加版本列；只有 §10 的单实体 `update(entity)` 会把版本写回入参对象）。代价是：并发下批量更新可能覆盖别人刚提交的行，而调用方看不到 `OptimisticLockException`。要行级版本保护，改用单实体 `update(entity)`；或把版本列显式写进 `where(...)` 条件（`where(Employee::getVersion).eq(expectedVersion)`），再按受影响行数判断是否被并发改过并重试。

**只读列**：条件更新命中 `@Column(updatable=false)` / `@JoinColumn(updatable=false)` 的列时，**两个后端一致 fail-fast 抛 `UnsupportedOperationException`**（消息点名该列）——JDBC 与 JPA（3.1.0 起）都是同一口径；静默跳过会让「更新了但某列没变」难以察觉。替代：去掉该列标志；或改走单实体 `update(entity)`（只读列按映射本来就不进 `SET` 集）；或直接发原生 SQL。写操作必须在调用方事务里执行（§11）。

> ⚠️ **不写 `where(...)` 就是整表更新/删除**（`update()` / `delete()` 没有「空条件拒绝」的保护）；批量语句请始终显式给出条件，Javadoc 也写了同一句警告。

**不提供**对 `fetch(...)` 或投影规格的条件写入：`update()` / `delete()` 只从实体根进入，投影/DTO 没有可写的列集。

## 11. 事务

本库不自己开事务，边界由调用方的 `@Transactional` 决定。**两个后端对「没有事务」的约定不同，写路径必须按目标后端选边界**：

- **JPA 后端**：写操作必须在调用方事务里执行。没有事务时 `update(...)` 抛 `TransactionRequiredException`（JPA 对 `executeUpdate` 的契约），而 `insertAll(...)` 既不落库也不报错、读回是 0 行——它会静默丢数据，写路径务必挂在 `@Transactional` 上。证据：`ItemNoTransactionContextTest` 与 `MySqlItemNoTransactionIntegrationTest`（均在 `nextentity-tests-jpa`）。
- **JDBC 后端**：写入建立在 JDBC 自动提交之上，**没有事务时 `update` 也会立即生效、不抛异常**（不经过 `TransactionRequiredException`）；`insertAll` 同走自动提交（tester 波正在补一条「无事务 `insertAll` 落库」用例，落地后把证据补到这里）。需要「多条写原子提交/回滚」时必须由调用方显式开事务（`TransactionTemplate` 或 Spring `@Transactional`）。证据：`nextentity-tests-jdbc/.../NoTransactionContextTest.java#updateSucceedsWithoutExplicitTransaction`（该类 Javadoc 明写「JDBC 写入建立在自动提交之上，与 tests-jpa 相反」）。

这不是缺陷，而是「边界归调用方」在两个后端上的不同落点：JPA 用 fail-fast 提醒没挂事务，JDBC 按驱动默认自动提交直接生效。下面示例是推荐写法（显式事务边界，两个后端都成立）：

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

## 13. JDBC 第二后端

3.0.0 新增 `nextentity-jdbc`：用纯 JDBC 实现同一套 `EntityOperations`，不装配 Hibernate。它替换的是 `core` 的后端接缝（`Compiler`/`Executor`），DSL 一行不改。

### 13.1 装配

```java
EntityOperationsFactory factory = new JdbcEntityOperationsFactory(dataSource);
EntityOperations<Order> orders = factory.operations(Order.class);
```

- 单参构造器 `JdbcEntityOperationsFactory(DataSource)` 在构造期取一条连接、读 `DatabaseMetaData.getDatabaseProductName()` 探测方言后立即关闭；`operations(Class)` 定义在同文件。取连接与执行分别在 `exec/JdbcExecutor` 与 `exec/JdbcPersistExecutor`。
- 工厂**不持有连接、不开事务**：每次操作独立 `getConnection()`，事务边界归调用方（类级 Javadoc 有同样说明；取连接点在 `exec/JdbcExecutor`、`exec/JdbcPersistExecutor` 的各个执行方法里）。所以 `DataSource` 必须「每次返回可用连接」，或包装连接的 `close()` 是 no-op——「close 真关连接」的数据源上第二条 SQL 就会报 `The object is already closed`（见 [DESIGN.md](DESIGN.md) §6.1「装配面契约」）。
- Spring 自动配置只注册 JPA 侧的 `EntityOperationsFactory`（`nextentity-spring` 不引用 `jdbc`）：用 JDBC 后端时自己 `new` 工厂，或自己注册 bean。

### 13.2 方言

方言是渲染钩子 `JdbcDialect`（`io.github.nextentity.jdbc.dialect`），10 个成员里只有 `name()` 必须实现：8 个 `default` 钩子（`quote` / `selectHead` / `limitClause` / `lock` / `averageOf` / `likeEscape` / `lengthOf` / `substring`）+ 静态 `of(String)`。内置 H2 / MySQL / PostgreSQL / SQL Server 四种，钩子语义见 [DESIGN.md](DESIGN.md) §6.1「方言接缝」。

两种选法——交给工厂探测，或显式指定（显式指定不探测）：

```java
JdbcDialect dialect = JdbcDialect.of("PostgreSQL 16.2");
EntityOperationsFactory factory = new JdbcEntityOperationsFactory(dataSource, dialect);
```

`JdbcDialect.of(...)` 的匹配规则：MySQL/H2 全等、PostgreSQL 前缀、Microsoft SQL Server 前缀（忽略大小写）；认不出抛 `UnsupportedOperationException` 并提示自定义方言（规则见 [DESIGN.md](DESIGN.md) §6.1「方言接缝」）。双参构造器 `JdbcEntityOperationsFactory(DataSource, JdbcDialect)` 显式注入、不探测，是自定义方言唯一保证生效的路径。

自研方言只实现 `name()`，需要时再覆写钩子：

```java
JdbcDialect custom = new JdbcDialect() {
    @Override
    public String name() {
        return "Custom";
    }

    @Override
    public String quote(String identifier) {
        return "[" + identifier + "]";
    }
};
```

注意 `quote` 只对**被反引号标记**的标识符生效（注解名用反引号包裹 ⇒ 需要引用，渲染字符由方言决定；见 [DESIGN.md](DESIGN.md) §6.1「标识符引用」）。

### 13.3 同一套 DSL

查询与写入和后端无关（API 在 `api`/`core`，JDBC 只提供渲染与执行）：

```java
List<Order> pending = orders.query()
        .where(Order::getStatus)
        .eq(OrderStatus.CREATED)
        .where(Order::getAmount)
        .ge(BigDecimal.ZERO)
        .orderBy(Order::getId)
        .asc()
        .list();

orders.insert(order);
orders.update(order);
orders.insertAll(List.of(order));
orders.updateAll(List.of(order));
```

写入仍走 `core.exec.PersistExecutor` 的方法（见 §10），事务语义同 §11：边界归调用方。

### 13.4 JDBC 后端的支持边界

3.1.0 起，JDBC 后端的能力面按**三类**陈述，不再用一句话概括：**支持**（下列矩阵）、**识别但忽略**（集合关联，不参与 SQL、不抛异常）、**拒绝**（fail-fast，抛 `UnsupportedOperationException` / `IllegalArgumentException` 并带可操作文案）。「拒绝」与「忽略」是两种不同契约，混写成「命中即抛 UOE」会让读者以为集合关联也会炸——3.0.0 的旧表就是这个毛病。

#### 13.4.1 支持的类型与注解（3.1.0）

| 面 | 3.1.0 支持 |
|---|---|
| 字符串 | `String` |
| 数值 | `Integer`/`int`、`Long`/`long`、`Short`/`short`、`Double`/`double`、`Float`/`float`、`BigDecimal`、`BigInteger`（主要服务于生成键；普通列同样可用，绑定走 `BigDecimal`、读回精确转回 `BigInteger`） |
| 布尔 | `Boolean`/`boolean` |
| 枚举 | 任意 `enum`；`@Enumerated(STRING)` 存名字、`@Enumerated(ORDINAL)` 存序号，缺省 `ORDINAL`（与 JPA 默认一致） |
| 时间 | `LocalDate`、`LocalDateTime`、`LocalTime`、`Instant`、`java.sql.Date`/`Time`/`Timestamp` |
| 生成键策略 | `@GeneratedValue(strategy = IDENTITY)` 与 `AUTO`（按方言落到 `IDENTITY`） |
| 生成键类型 | `short`/`Short`、`int`/`Integer`、`long`/`Long`、`BigInteger`（按 `id.type()` 转换，窄类型做范围校验） |
| `@Version` 类型 | `int`/`long`/`short`/`Integer`/`Long`/`Short`（应用侧 +1 写回；`WHERE id [AND version]` 0 行抛 `OptimisticLockException`） |
| `@Column` / `@JoinColumn` 的 `insertable/updatable=false` | 支持：读回照常；`insertable=false` 不进 `INSERT` 列集、`updatable=false` 不进 `UPDATE SET`；该列仍可出现在 `WHERE`/`ORDER BY`（2.2.3 examples 用的是 `@JoinColumn` 形态） |
| `@Embedded`/`@Embeddable` | 支持**扁平**嵌入：列名默认 `<嵌入字段名>_<嵌套字段名>`（与 JPA 默认命名一致），可用 `@AttributeOverride` 覆盖；嵌套 `@Embedded`、点号形式的嵌套 `@AttributeOverride` 不提供（见 13.4.3） |
| `@Convert` | 支持**显式** `@Convert(converter = ...)`（`AttributeConverter` 无参实例化；`convertsTo` 的列类型必须落在上表内，否则拒绝并提示改用受支持类型） |
| 关联写入 | `@ManyToOne`/`@OneToOne`（持有外键一侧）可写：关联值非 `null` 时读关联对象 `@Id` 写外键列；缺 `@JoinColumn(name)` 时按 JPA 默认命名 `字段名_被引用列名` 推断；`@OneToOne(mappedBy=...)` 反侧不写（见 13.4.2） |
| 无参构造 | 实体必须有可调用的无参构造（`private`/`protected` 会 `setAccessible`）；缺失抛 `IllegalArgumentException`（消息含实体类名）；**record 实体不支持**（无无参构造、字段 final） |
| 3.1.0 新查询/写入节点 | 字符串函数（`LOWER`/`UPPER`/`TRIM`/`LENGTH`/`SUBSTRING`）、`startsWith`/`endsWith`/`notLike` 族（折成 `LIKE`/`NOT LIKE`）、`groupBy` 之后的 `count()`/`exists()`、条件 `update`/`delete` 都有渲染分支；**`fetch(...)` 在 JDBC 后端显式拒绝（`UOE`）**（JDBC 无实体图/懒加载，见 13.4.3）；SQL Server 的长度/截取形态差异见 13.4.5 |

**写关联的 null 语义与 JPA 不同（必须知道）**：JDBC 后端在关联值为 `null` 时**跳过该列**（不写 `NULL`），因此不能通过 `update(entity)` 把外键置空。等价写法是条件更新 + `Path` 重载的 `set(..., null)`：`update().set(Path.of(Employee::getDepartment).get(Department::getId), null).where(...).execute()`（见 §10.1）。另外，JDBC 读回只装外键列与标量列，**不填充关联对象**（关联字段为 `null`；显式 `project(...)` 投影除外）。

#### 13.4.2 识别但忽略：集合关联与反侧一对一

`@OneToMany`、`@ManyToMany`、`@ElementCollection`，以及 `@OneToOne(mappedBy = ...)`（反侧，本表没有外键列），在**写入列集**上会被扫描到但**忽略**：不进 `attributes()`、不参与任何 SQL、不抛异常；`@OneToOne` 的持有外键一侧按「关联写入」支持。**查询路径**是另一回事：穿过集合关联（如 `EntityPath.of(Order::getItems).get(Item::getName)`）时 JDBC 与 JPA 都显式抛 `UOE`（`SqlPaths` / `JpaPaths`），不静默——避免 join 出的子行让 `list()` 重复、分页与 `total` 失真。级联写需要调用方显式先写子实体（或自行在 DB 侧用触发器）；是否恢复由 DESIGN §7.1 CUT-18 的触发器决定。

#### 13.4.3 拒绝（fail-fast，不静默写坏数据）

本节是 **JDBC 实体映射拒绝清单的唯一出处**：代码里拒绝分支的注释一律指到这里（`USAGE.md §13.4.3`），不再引用编号或外部设计文件。

| 拒绝项 | 行为 | 理由类型 | 用户可走的替代路径 |
|---|---|---|---|
| `@Inheritance` 继承映射 | `UnsupportedOperationException` | 自设规则（当前只支持单表扁平映射） | 拆成独立实体 + 标量列；或换 JPA 后端 |
| `@IdClass` / 复合主键 / `@EmbeddedId` | `UOE` | 自设规则 | 增加单列代理主键；复合唯一约束交给 DB |
| `@Access(PROPERTY)` / 把映射注解写在 getter 上 | `UOE`（只支持字段访问） | 自设规则 | 把 `@Id`/`@Column` 等注解移到字段上 |
| 缺 `@Entity` / 无 `@Id` / 多 `@Id` / 多 `@Version` | `IllegalArgumentException`（实体结构错误） | 自设规则 | 按 JPA 实体规则修正模型 |
| `byte`/`Byte`、`char`/`Character`、`byte[]` 列类型 | `UOE` | 尚未验证（`char` 的定长补空格语义、`byte[]` 的 BLOB/VARBINARY 各方言类型名与流式读写未实测） | 改用受支持列类型，或 `@Convert` 转成 `String`/`byte[]` 之外的表示 |
| `@GeneratedValue(strategy = SEQUENCE)` | `UOE` | 尚未验证（无跨方言取号钩子；autocommit 下取号有空洞；各方言取号形态未实测） | 应用侧发号（`IdGenerator`）后手动赋值 id；或改用 `IDENTITY`/`AUTO` |
| `@GeneratedValue(strategy = TABLE)` | `UOE` | 外部约束（需要序列表 DDL，本模块不生成 DDL） | 应用侧发号或换 `IDENTITY`/`AUTO` |
| `@Version` 为 `Timestamp` | `UOE` | 尚未验证（应用侧取当前时间需 `Clock` 注入，JPA 侧本轮未同步，两端口径会分叉） | 改用六种数值型版本 |
| 嵌套 `@Embedded` / 点号形式的嵌套 `@AttributeOverride` | `UOE` | 尚未验证 | 拍平成一层的嵌入字段或标量列，覆盖只写单层属性名 |
| 依赖 `@Convert(autoApply = true)`（字段上没有显式 `@Convert`） | 不自动应用；该字段按原 Java 类型走列类型校验（不被自动转换） | 尚未验证（扫描成本 + 无正例） | 在字段上显式标 `@Convert(converter = ...)` |
| 锁模式：`PESSIMISTIC_READ`/`PESSIMISTIC_WRITE` 之外的三种 | `UOE` | 外部约束（无标准 SQL 形态） | 只使用这两种锁模式，或走原生 SQL |
| SQL Server 上的行锁（两种模式） | `UOE` | 外部约束：T-SQL 没有 `FOR SHARE`，也没有标准 `FOR UPDATE` 行锁；`with (rowlock)` 是索引提示、不是锁语义（未实测） | 应用层锁（悲观锁表 / 串行队列）、快照隔离（`READ COMMITTED SNAPSHOT`）、或原生 SQL |
| 条件更新/删除命中 `@Column(updatable=false)` / `@JoinColumn(updatable=false)` 列 | 两后端一致 `UOE`（消息点名列；fail-fast，不会静默跳过该列） | 自设规则：批量写入的赋值列集必须显式可写，静默跳过会让「更新了但某列没变」难以察觉 | 去掉该列标志；或改走单实体 `update(entity)`（只读列按映射不进 `SET`）；或原生 SQL（共享 DSL 契约，见 §10.1） |
| `fetch(...)`（JDBC 后端） | `UOE`（fail-fast，不静默 no-op） | 架构约束：JDBC 后端不组装实体图、无懒加载代理，`fetch` 没有可兑现的语义；静默接受只会让调用方拿到 `null` 关联 | `project(ProjectionMapper)` 显式取关联列（LEFT JOIN 可用），或改用 JPA 后端 |

#### 13.4.4 与 JPA 的语义差异（不是拒绝，但结果可能不同）

| 差异点 | JDBC 行为 | 与 JPA 的差别 |
|---|---|---|
| 关联置空 | 关联 `null` 时跳过列，`update(entity)` 无法置空外键 | JPA 按 `nullable`/级联语义写 `NULL`；JDBC 用条件更新 `set(Path.of(X::getDept).get(Dept::getId), null)` 表达 |
| 读回关联对象 | 只装标量列，关联字段为 `null` | JPA 按映射/预取加载关联 |
| `@Version` 自增 | 应用侧读取后 +1 写回 | JPA 由提供方管理版本并可 DB 侧生成 |
| 条件更新命中只读列（`updatable=false`） | `UOE`（走共享 DSL 契约，消息点名列） | **无差异**：条件更新是 3.1.0 新增入口，两个后端同版落地并一致 fail-fast（DSL 契约见 §10.1）。与 3.0.0 的差别在**单实体**更新路径：JPA 侧曾静默写只读列，JDBC 侧在映射期直接拒绝该标志 |
| 生成键回读 | 单列结果集取第 1 列；多列按列标签/列名匹配 id 列名；`BigInteger` 走 `getBigDecimal` 转换；窄类型做范围校验 | JPA 由提供方回填 |
| 分组 `count()`（`groupBy` + `count()`） | 派生表原生支持（子查询计数） | JPA 侧依赖 Hibernate 6+ 的 `createCountQuery()`（单条 SQL）；其它 provider 抛 `UOE`（文案指名 provider），见 §7.4 |
| `fetch(...)` 预取 | 显式拒绝：`UOE`（JDBC 不组装实体图、无懒加载代理，没有可兑现的语义；不静默 no-op） | JPA 渲染为 `LEFT JOIN FETCH`（同一条 SQL 载入关联），消除 N+1；JDBC 侧要读关联列请用 `project(...)` 投影或原生 SQL |
| 分页 `limit = 0` | 视为不限行 | 与 JPA 一致（api 契约），单列在此说明避免误读 |
| 事务 | 写入建立在 JDBC 自动提交上：无事务时 `update` 立即生效、不抛异常；`insertAll` 同走自动提交（tester 波补无事务 `insertAll` 落库用例后补证据，见 §11） | **不同约定**（理由类型=外部约束：自动提交是 JDBC 连接层的默认行为）：JPA 侧无事务 `update` 抛 `TransactionRequiredException`、`insertAll` 静默不落库（证据在 `nextentity-tests-jpa`）。需要原子性时两个后端都用 `TransactionTemplate` 或 Spring `@Transactional` 包住（§11） |
| DDL | 不生成，表结构自己建（测试按方言引号建表） | 不生成 |
| 异常 | `SQLException` 包成 `PersistenceException("SQL 执行失败: " + sql, e)` | 同 JPA 顶层异常族 |

#### 13.4.5 方言差异（可能出现「会算错」的项）

- **共享锁**：MySQL 与 PostgreSQL 都渲染 `for share`，**两条容器通道均已实测**（MySQL 容器；PostgreSQL 容器 `PostgresItemLockIntegrationTest` 2 条 + `PostgresLockContractIntegrationTest` 4 条全绿，且 PG 与 MySQL 跑同一批 590 条用例）；H2 没有 `FOR SHARE`，用 `for update` 兜底——**比 FOR SHARE 锁更强，是安全方向的近似，不是等价降级**。SQL Server 不提供行锁（13.4.3）。
- **字符串长度**：默认渲染 `length(x)`；SQL Server 用 `len(x)`，而 `LEN` 会**裁掉尾随空格**（`LEN('ab ')` 与标准 `CHAR_LENGTH` 不等价），是已披露的方言语义差异、不是缺陷——**跨库依赖长度的查询在 SQL Server 上会少算尾随空格**。
- **`substring` 省略长度**：H2/MySQL/PostgreSQL 可下发 `substring(x, pos)`；SQL Server 的 `SUBSTRING` 必须带长度参数，实现用 `substring(x, pos, 2147483647)`（int 上界）表达「到结尾」，该上界形态**已在 SQL Server 容器实测通过**（方言惯用法，语义为截取到结尾）。
- **生成键策略**：四种内置方言都只支持 `IDENTITY`/`AUTO`；`SEQUENCE`/`TABLE` 统一拒绝（13.4.3）。
- 以上差异的渲染钩子集中在 `JdbcDialect`，自定义方言必须遵守 [DESIGN.md](DESIGN.md) §6.1「方言接缝」的不变量；语义差异列入 §7.1 对应 CUT 的代价与触发器，而不是当作「实现细节」。

## 14. 何时不用本库

- 纯 CRUD、无动态条件 → 直接用 Spring Data 派生查询。
- 复杂报表 SQL（窗口函数、多表聚合、方言函数）→ 用 `@Query` 原生查询。
- 需要实体图/级联策略深度控制 → 用 Hibernate 原生 API。

## 15. 3.1.0 起提供的能力与仍不提供的能力

3.0.0 的「不提供」清单是按**内部证据缺席**裁的：point-mall 零调用点、共享测试套件没有正例。但证据面当时已经被整体删掉了——2.2.3 的 `nextentity-examples`（37 个 Java 文件 = 20 主 + 17 测试）从未移植到 3.0.0，而它用遍了下面这些 API。拿一个被删除的语料证成「没人用」是循环论证；3.1.0 按**恢复**处理（逐条理由、代价与 revisit 触发器见 [DESIGN.md](DESIGN.md) §7.1，具体调用点见 [CHANGELOG.md](../CHANGELOG.md) `[3.1.0]` 节）。纯加性恢复按语义化版本属 MINOR，故本版是 3.1.0 而不是补丁版。

### 15.1 3.1.0 起提供

| 能力 | 2.2.3 写法 | 3.1.0 用法 | 详见 |
|---|---|---|---|
| 显式 fetch | `.fetch(Order::getCustomer)` | JPA 同形（渲染为 `LEFT JOIN FETCH`；`FetchStep` 6 个方法；不能链两次；便捷重载最多 3 条 `Path`，4 条以上用 `fetch(Collection<Path>)`）；JDBC 显式拒绝（fail-fast `UOE`，替代 `project(ProjectionMapper)` 或 JPA 后端），见 §8.1 | §8.1 |
| DTO / record 投影 | `select(OrderRow.class)` | 同形；只覆盖扁平映射（属性名 = 实体单层属性名），去重用 `distinct().select(OrderRow.class)`；嵌套/改名仍用 `project(ProjectionMapper)` | §6.3 |
| 按条件更新 / 删除 | `update().set(..).where(..).execute()` / `delete().where(..).execute()` | 同形，返回受影响行数 | §10.1 |
| `startsWith` / `endsWith` / `notLike`（及取反族、`IfNotNull` / `IfNotEmpty` 变体） | `where(..).startsWith("A")` | 同形 | §3.8 |
| `lower` / `upper` / `trim` / `substring` / `length` | `where(..).lower().contains("a")` | 同形 | §3.9 |
| `groupBy` 之后的 `count()` / `exists()` / 分页取行 | `.groupBy(..).having(..).count()` | 同形 | §7.4 |
| `Slice.offset()` / `limit()` | `slice.offset()` | 同形 | §4 |
| `PESSIMISTIC_READ` | `.lock(LockMode.PESSIMISTIC_READ)` | 同形 | §9 |
| `lock(...)` 之后分页 | `.lock(..).list(offset, limit)` | 同形（`Collector` 上的重载） | §9 |
| JDBC 后端的列类型 / 映射 / 生成键 / 写关联 | — | 见 §13.4 的 3.1.0 支持面 | §13.4 |

### 15.2 仍不提供

| 能力 | 2.2.3 写法 | 3.1.0 替代 | 为什么仍不提供 / 何时重审 |
|---|---|---|---|
| 静态元模型 | `where(Order_.status)` | `where(Order::getStatus)` | 注解处理器不在本库目标内；重审触发：出现必须脱离方法引用生成查询的消费方 |
| 子查询 | `toSubQuery()` | `@Query` 原生 / JPQL | DSL 只覆盖平坦查询，子查询的跨方言渲染与对拍成本远高于收益；重审触发：需要 DSL 内子查询且能给出跨方言差分用例 |
| `*IfPresent` 命名 | `eqIfPresent(..)` | `eqIfNotNull(..)`（语义相同） | 纯命名别名，没有新语义；重审触发：4.0 允许破坏性重命名时 |
| `sliceWithoutCount` | 免 count 分页 | `Collector.list(offset, limit)`（`lock` 后的分页靠它），或自己取 `List` 再算总数 | `Slice` 的语义绑定 `total`，免 count 是另一个返回类型；重审触发：免 count 分页成为消费方热点并给出用例 |
| 多列 `distinct` 的 `count()` / `exists()` | `.distinct().select(a, b).count()` | `distinct().select(a, b).list()` 后内存去重计数 | `COUNT(DISTINCT a, b)` 在 MySQL / PostgreSQL / SQL Server 形状不同，单列近似会静默错数；重审触发：给出跨方言差分 oracle 与口径 |
| 字符串算子/函数挂在 `Path.of(...)` 上 | `Path.of(Employee::getName).startsWith(prefix)` | 从 `where(Employee::getName)` 进入算子（§3.8、§3.9） | 这些方法只在 `StringOperator` 上，`StringPath` 保持原 `StringOps`；给 `Path` 的所有实现方（含 JPA/JDBC/自定义路径）加方法会制造源码义务。重审触发：真实消费方需要在 `select(...)` 的路径上调用这些函数 |
| 函数结果上的 `in` / `notIn` / `isNull` / `isNotNull` | `where(..).lower().in(...)` | 等价改写为显式条件，或在内存里过滤 | `in` / `isNull` 的语义针对**属性**，对函数结果没有确定的 SQL 对应物；命中抛 `UnsupportedOperationException`。重审触发：SQL 方言给出可移植的函数结果 NULL/IN 语义 |
| 计算列投影（函数结果进 `select`） | `select(Path.of(name).lower())` | 原生 `@Query` 或后端 SQL（§6.3 的 `select(Class)` 只装属性列） | 投影管线只接受属性路径（`NumberOperator` 不 extends `Expression`），不是外部约束；代价是函数计算结果无法直接映射到 DTO 字段。重审触发：examples 或真实消费方出现该用法 |
| 七列以上的 `select(...)` | `select(c1..c8)` | `ProjectionMappers` 封顶六列；七列以上自定义 `ProjectionMapper` | `ExcessiveParameterListSix` 把参数上限定在 6；重审触发：语言/规则允许更多参数或出现不可拆的宽投影 |

「聚合投影上的 `count()`」仍被显式拒绝（见 §5、§7.3）：SQL 需要子查询计数；`groupBy` / `having` 之后的 `count()` / `exists()` / 分页**已恢复**（§7.4），两者不是一回事，不要混用口径。


