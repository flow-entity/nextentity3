package io.github.nextentity.tests.jpa.golden;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Expression;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.ProjectionMapper;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.testing.golden.GoldenSchema;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// golden schema 上的整句 SQL 快照（H2 + 真 Hibernate）：`@Embedded` 扁平列、`@ManyToOne` 的 LEFT JOIN、
/// `@Convert` 枚举的参数位、分组计数派生表、条件 UPDATE/DELETE 的 SET/WHERE 形状。
///
/// 与 [`GoldenSchemaLandingTest`] 分工：那边证明「值对不对」，这边钉「语句长什么样」。断言用
/// 「关键子句必须出现 / 错误形态必须不出现」而不是逐字符整句，是因为列顺序与别名由 Hibernate 版本
/// 决定——钉死它会让升级 Hibernate 时每个 golden 用例都红，而红点与能力无关。`Item` 上的整句快照
/// 已经逐字符钉过同一批渲染（`ItemCapabilitySqlSnapshotTest`），这边只补 golden 表特有的形状。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenSchemaSqlSnapshotTest {

    private final JpaGoldenSupport support = new JpaGoldenSupport("golden_schema_sql_snapshot");

    @BeforeEach
    void seedGoldenSchema() {
        support.clear();
        support.seedGolden();
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    /// `@Embedded` 必须摊平成 `addr_*` 列直接参与 WHERE，不能出现「查地址表」的痕迹。
    @Test
    @DisplayName("@Embedded：地址列摊平进 golden_customer，WHERE 直接命中 addr_city")
    void embeddedColumnsRenderFlattenedAddressColumns() {
        String statement = sqlOf(() -> support.query(GoldenSchema.Customer.class)
                .where(addressCity())
                .eq("Springfield")
                .list());

        assertThat(statement)
                .contains("from golden_customer")
                .contains("addr_city=?")
                .doesNotContain("golden_address");
    }

    /// `@ManyToOne` 的 fetch 预取渲染成 LEFT JOIN，外键列出现在 ON 子句里，且只发一条语句。
    @Test
    @DisplayName("@ManyToOne fetch：一条语句 + LEFT JOIN + 外键 customer_id")
    void manyToOneFetchRendersLeftJoinOnForeignKey() {
        List<String> statements = support.inTransaction(() -> {
            support.clearSql();
            support.query(GoldenSchema.Order.class)
                    .fetch(GoldenSchema.Order::customer)
                    .list();
            return normalized(support.drainSql());
        });

        assertThat(statements).hasSize(1);
        String statement = statements.getFirst();
        assertThat(statement)
                .contains("from golden_order")
                .contains("left join golden_customer")
                .contains("customer_id");
    }

    /// 分组计数走派生表：`select count(*) from (select ... group by ...)`，不是客户端数。
    @Test
    @DisplayName("分组计数：派生表包裹，group by 必须在派生表里")
    void groupCountUsesDerivedTable() {
        String statement = sqlOf(() -> support.query(GoldenSchema.Customer.class)
                .groupBy(GoldenSchema.Customer::tier)
                .count());

        assertThat(statement).contains("select count(*) from (").contains("group by");
    }

    /// 条件 UPDATE：SET 只写被赋值的列（`total`），where 里出现 golden 表的主键。
    @Test
    @DisplayName("条件 UPDATE：SET 里只有被赋值的列")
    void conditionalUpdateSnapshotsSetClause() {
        String statement = sqlOf(() -> support.inTransaction(() -> support.operations(GoldenSchema.Order.class)
                .update()
                .set(Path.of(GoldenSchema.Order::total), new BigDecimal("99.99"))
                .where(GoldenSchema.Order::id)
                .eq(102L)
                .execute()));

        assertThat(statement)
                .contains("update golden_order")
                .contains("set total=?")
                .contains("where");
        assertThat(statement).doesNotContain("customer_id=");
    }

    /// 不带 where 的条件 delete：整表删除，子行表也一样（顺序按调用方自觉，FK 由库兜）。
    @Test
    @DisplayName("不带 where 的 DELETE：整表删除")
    void deleteWithoutWhereEmptiesWholeTable() {
        String statement = sqlOf(() -> support.inTransaction(
                () -> support.operations(GoldenSchema.OrderLine.class).delete().execute()));

        assertThat(statement).contains("delete from golden_order_line").doesNotContain("where");
    }

    /// 嵌套关联投影：`project(ProjectionMapper)` 里声明「订单 id + 客户名」，渲染成 LEFT JOIN + 外键列，
    /// 一条语句取完；同时钉住结果映射（列序即映射器表达式序）。
    ///
    /// `select(Class)` 只做扁平同名映射，嵌套列必须走映射器——这条用例是那个分工的可执行证据。
    @Test
    @DisplayName("嵌套关联投影：project 映射器渲染 LEFT JOIN，一条语句取完并正确装回")
    void nestedAssociationProjectionJoinsAndMaps() {
        support.clearSql();
        List<OrderCustomer> rows = support.query(GoldenSchema.Order.class)
                .project(new OrderCustomerMapper())
                .orderBy(GoldenSchema.Order::id)
                .asc()
                .list();
        List<String> statements = normalized(support.drainSql());

        assertThat(rows)
                .containsExactly(
                        new OrderCustomer(101L, "alice"),
                        new OrderCustomer(102L, "alice"),
                        new OrderCustomer(103L, "bob"));
        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst()).contains("left join golden_customer").contains("customer_id");
    }

    private String sqlOf(Runnable query) {
        support.clearSql();
        query.run();
        List<String> statements = normalized(support.drainSql());
        assertThat(statements).hasSize(1);
        return statements.getFirst();
    }

    private static StringPath<GoldenSchema.Customer> addressCity() {
        return EntityPath.of(GoldenSchema.Customer::address).get(GoldenSchema.Address::city);
    }

    private static List<String> normalized(List<String> raw) {
        List<String> result = new ArrayList<>(raw.size());
        for (String sql : raw) {
            result.add(sql.toLowerCase().replaceAll("\\s+", " ").trim());
        }
        return result;
    }

    /// 嵌套关联投影的结果类型：订单 id + 客户显示名。
    ///
    /// @param orderId      订单主键
    /// @param customerName 客户显示名
    private record OrderCustomer(long orderId, String customerName) {}

    /// 声明「订单 id + 关联客户名」两列；下标常量紧邻表达式列表，列序错位在审查里能直接看见。
    private static final class OrderCustomerMapper implements ProjectionMapper<GoldenSchema.Order, OrderCustomer> {

        private static final int IDX_ORDER_ID = 0;

        private static final int IDX_CUSTOMER_NAME = 1;

        @Override
        public List<Expression<GoldenSchema.Order, ?>> expressions() {
            return List.of(
                    Path.of(GoldenSchema.Order::id),
                    EntityPath.of(GoldenSchema.Order::customer).get(GoldenSchema.Customer::name));
        }

        @Override
        public OrderCustomer map(Tuple row) {
            return new OrderCustomer((Long) row.get(IDX_ORDER_ID), (String) row.get(IDX_CUSTOMER_NAME));
        }
    }
}
