package io.github.nextentity.testing.golden;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// 独立 golden schema 夹具：三张贴近真实的表，字段类型/映射形态混布。
///
/// ## 为什么不用 `Item` 加字段
///
/// 3.0.0 的能力面之所以悄悄变小，是因为证据体系本身被小能力面定义：共享夹具 `Item` 只有
/// Long/String/Integer/enum 六种字段类型，`JdbcColumns` 的列类型白名单就是这六个类型的镜像。
/// 往 `Item` 上加字段**不是**证据——它仍然是一张为测试而生的表，加不出 `@ManyToOne` 的 FK 列、
/// 加不出 `@Column(updatable=false)` 的写侧规则、也加不出真实表里 `DECIMAL(12,2)` + `@Version`
/// 同时出现的组合。独立 golden schema 才是：表结构按真实业务表设计，实体注解与 DDL 是两份独立写下的
/// 契约，任何一侧漂移都会被落地用例的往返断言抓住。
///
/// ## 证据分工
///
/// - 实体与夹具（本类的三个 `@Entity` + [GoldenFixtures]）两个后端共享；
/// - JDBC 的 DDL 在 `nextentity-tests-jdbc/src/test/resources/schema-*.sql` 的 golden 段里，
///   由 `JdbcSchema.apply` 在 H2/MySQL/PostgreSQL/SQL Server 四条通道上真执行；
/// - JPA 侧由同一套注解经 `hbm2ddl` 建等价表，列集靠 `GoldenSchemaLandingTest` 的往返断言对齐；
/// - `GoldenSchemaSqlSnapshotTest` 钉住 golden 表上的整句 SQL（JOIN/派生表计数/带窗口分页）。
///
/// 字段刻意分布在不同位置上（列名与属性名不同、混合 `@Embedded`/`@Convert`/`@Enumerated(STRING)`/
/// `@Column(updatable=false)`/`@Version`/`@ManyToOne`/`@OneToMany`），让「只按字段名猜列名」的编译分支
/// 立刻现形。
public final class GoldenSchema {

    private GoldenSchema() {}

    /// 客户等级：`@Enumerated(STRING)` 直接写枚举名（与真实表里的人工可读列一致）。
    public enum Tier {
        BASIC,
        PRO,
        ENTERPRISE
    }

    /// 订单状态：走 `@Convert`，库里存**小写 code**（已有系统常见的历史格式），
    /// 与 [Tier] 的 `@Enumerated(STRING)` 形态刻意不同，覆盖两条枚举映射路径。
    public enum OrderState {
        NEW,
        PAID,
        SHIPPED
    }

    /// 订单状态的属性转换器：写小写 code、读回大写枚举名。
    @Converter
    public static class OrderStateConverter implements AttributeConverter<OrderState, String> {

        @Override
        public String convertToDatabaseColumn(OrderState attribute) {
            return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public OrderState convertToEntityAttribute(String dbData) {
            return dbData == null ? null : OrderState.valueOf(dbData.toUpperCase(Locale.ROOT));
        }
    }

    /// 客户地址：`@Embedded` 摊平进 `golden_customer`，列名前缀 `addr_`。
    @Embeddable
    public static class Address {

        @Column(name = "addr_street")
        String street;

        @Column(name = "addr_city")
        String city;

        @Column(name = "addr_zip")
        String zip;

        protected Address() {}

        public Address(String street, String city, String zip) {
            this.street = street;
            this.city = city;
            this.zip = zip;
        }

        public String street() {
            return street;
        }

        public String city() {
            return city;
        }

        public String zip() {
            return zip;
        }
    }

    /// 客户表：`DECIMAL(12,2)` 金额、`BOOLEAN`、时间、`@Enumerated(STRING)`、`@Embedded`、`@Version` 同表。
    @Entity(name = "GoldenCustomer")
    @Table(name = "golden_customer")
    public static class Customer {

        @Id
        Long id;

        @Column(name = "display_name")
        String name;

        @Enumerated(EnumType.STRING)
        Tier tier;

        @Column(name = "credit_limit")
        BigDecimal creditLimit;

        boolean active;

        @Column(name = "joined_at")
        Instant joinedAt;

        @Embedded
        Address address;

        @Version
        Long version;

        protected Customer() {}

        @SuppressWarnings("PMD.ExcessiveParameterListSix") // 全参构造刻意平铺：字段即表列，拆 builder 会让「注解↔列」对照消失
        public Customer(
                long id,
                String name,
                Tier tier,
                BigDecimal creditLimit,
                boolean active,
                Instant joinedAt,
                Address address) {
            this.id = id;
            this.name = name;
            this.tier = tier;
            this.creditLimit = creditLimit;
            this.active = active;
            this.joinedAt = joinedAt;
            this.address = address;
        }

        public Long id() {
            return id;
        }

        public String name() {
            return name;
        }

        public Tier tier() {
            return tier;
        }

        public BigDecimal creditLimit() {
            return creditLimit;
        }

        public boolean active() {
            return active;
        }

        public Instant joinedAt() {
            return joinedAt;
        }

        public Address address() {
            return address;
        }

        public Long version() {
            return version;
        }
    }

    /// 订单表：`@ManyToOne` 的 FK 列、`@Convert` 枚举、`DECIMAL(12,2)`、`LocalDateTime`、
    /// `@Column(updatable=false)` 的只读备注列、`@Version` → `@OneToMany` 集合关联。
    @Entity(name = "GoldenOrder")
    @Table(name = "golden_order")
    public static class Order {

        @Id
        Long id;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "customer_id")
        Customer customer;

        @Convert(converter = OrderStateConverter.class)
        @Column(name = "state")
        OrderState state;

        BigDecimal total;

        @Column(name = "placed_at")
        LocalDateTime placedAt;

        /// 只读备注：`updatable=false` 让 UPDATE 的 SET 里不含它，条件 update 若点名该列必须被拒。
        @Column(name = "note", updatable = false)
        String note;

        @Version
        Long version;

        @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
        List<OrderLine> lines = new ArrayList<>();

        protected Order() {}

        public Order(
                long id, Customer customer, OrderState state, BigDecimal total, LocalDateTime placedAt, String note) {
            this.id = id;
            this.customer = customer;
            this.state = state;
            this.total = total;
            this.placedAt = placedAt;
            this.note = note;
        }

        public Long id() {
            return id;
        }

        public Customer customer() {
            return customer;
        }

        public OrderState state() {
            return state;
        }

        public BigDecimal total() {
            return total;
        }

        public LocalDateTime placedAt() {
            return placedAt;
        }

        public String note() {
            return note;
        }

        public Long version() {
            return version;
        }

        public List<OrderLine> lines() {
            return lines;
        }
    }

    /// 订单行表：`@ManyToOne` 回指订单、可空 `Instant`、`BOOLEAN`、`INT` 与 `DECIMAL(12,2)`。
    @Entity(name = "GoldenOrderLine")
    @Table(name = "golden_order_line")
    public static class OrderLine {

        @Id
        Long id;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "order_id")
        Order order;

        @Column(name = "line_no")
        int lineNo;

        int quantity;

        @Column(name = "unit_price")
        BigDecimal unitPrice;

        boolean shipped;

        @Column(name = "shipped_at")
        Instant shippedAt;

        protected OrderLine() {}

        @SuppressWarnings("PMD.ExcessiveParameterListSix") // 全参构造刻意平铺：字段即表列，拆 builder 会让「注解↔列」对照消失
        public OrderLine(
                long id,
                Order order,
                int lineNo,
                int quantity,
                BigDecimal unitPrice,
                boolean shipped,
                Instant shippedAt) {
            this.id = id;
            this.order = order;
            this.lineNo = lineNo;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.shipped = shipped;
            this.shippedAt = shippedAt;
        }

        public Long id() {
            return id;
        }

        public Order order() {
            return order;
        }

        public int lineNo() {
            return lineNo;
        }

        public int quantity() {
            return quantity;
        }

        public BigDecimal unitPrice() {
            return unitPrice;
        }

        public boolean shipped() {
            return shipped;
        }

        public Instant shippedAt() {
            return shippedAt;
        }
    }
}
