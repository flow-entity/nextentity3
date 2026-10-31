package io.github.nextentity.tests.jpa.golden;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.testing.golden.GoldenSchema;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import jakarta.persistence.EntityManager;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// golden schema 的**物理列形状**证据：Hibernate 按实体注解经 `hbm2ddl` 建出的 H2 表，必须与 JDBC 侧
/// `nextentity-tests-jdbc/src/test/resources/schema-h2.sql:24-26` 的 golden 三表逐列同形。
///
/// 为什么单独立一条：`JpaGoldenSchemaLandingContract` 的往返断言只覆盖「写入什么、读回什么」。
/// 注解漏写 `length`/`precision`/`scale` 时 Hibernate 的默认值（`varchar(255)` / `numeric(38,2)`）
/// 照样装得下夹具值、也照样往返成功，两侧 DDL 的真实差异抓不住——现有唯一精度断言
/// `creditLimit().scale()==2` 在 `(38,2)` 下同样成立。
///
/// 期望值逐列抄自 `schema-h2.sql` 的 golden 段（7 个有宽度的 varchar 列 + `decimal(12,2)` 3 列），
/// 不取注解、也不从 Hibernate 元数据反推——否则就是拿实现验实现。
///
/// `golden_customer.tier` 是唯一的例外，不并入宽度断言：JDBC DDL 写的是 `varchar(16)`，但 Hibernate 7.1.8
/// 把 `@Enumerated(STRING)` 映射为 H2 原生 `ENUM('BASIC', 'ENTERPRISE', 'PRO')`（`COLUMN_SIZE` 是最长
/// 字面量 10），`@Column(length = 16)` 只对 varchar 生效、对原生 enum 无效。这是既有的类型族差异，
/// 不属于「漏写长度 → 默认 255」这一类漂移，另行作为残留风险上报。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenSchemaPhysicalColumnsTest {

    /// 与 [JpaGoldenSupport] 里 `GOLDEN_ENTITIES` 同一组实体。
    private final HibernateHarness harness = HibernateHarness.start(
            "golden_schema_physical_columns",
            GoldenSchema.Customer.class,
            GoldenSchema.Order.class,
            GoldenSchema.OrderLine.class);

    private Map<String, Column> columns;

    @BeforeAll
    void readColumnMetadata() {
        columns = readColumns(harness.entityManager());
    }

    @AfterAll
    void closeHarness() {
        harness.close();
    }

    /// `schema-h2.sql:24`（`golden_customer`）与 `schema-h2.sql:25`（`golden_order`）里的 6 个 varchar 列。
    /// `golden_customer.tier` 见类注释：它由 Hibernate 建成原生 ENUM，没有可断言的 varchar 宽度。
    @Test
    @DisplayName("6 个 varchar 列宽度与 schema-h2.sql 一致（不是 Hibernate 默认的 255）")
    void varcharWidthsMatchJdbcSchema() {
        assertVarcharWidth("golden_customer.display_name", 64);
        assertVarcharWidth("golden_customer.addr_street", 64);
        assertVarcharWidth("golden_customer.addr_city", 32);
        assertVarcharWidth("golden_customer.addr_zip", 12);
        assertVarcharWidth("golden_order.state", 16);
        assertVarcharWidth("golden_order.note", 128);
    }

    /// `schema-h2.sql:24-26` 里的 3 个 `decimal(12,2)` 列。
    @Test
    @DisplayName("3 个 decimal 列 precision/scale 与 schema-h2.sql 一致（不是 Hibernate 默认的 38,2）")
    void decimalPrecisionAndScaleMatchJdbcSchema() {
        assertDecimal("golden_customer.credit_limit");
        assertDecimal("golden_order.total");
        assertDecimal("golden_order_line.unit_price");
    }

    private void assertVarcharWidth(String qualifiedName, int expectedWidth) {
        Column column = column(qualifiedName);
        assertThat(column.size())
                .as("schema-h2.sql 里 %s 是 varchar(%d)（物理类型 %s）", qualifiedName, expectedWidth, column.typeName())
                .isEqualTo(expectedWidth);
    }

    private void assertDecimal(String qualifiedName) {
        Column column = column(qualifiedName);
        assertThat(column.precision())
                .as("schema-h2.sql 里 %s 是 decimal(12,2) 的 precision（物理类型 %s）", qualifiedName, column.typeName())
                .isEqualTo(12);
        assertThat(column.scale())
                .as("schema-h2.sql 里 %s 是 decimal(12,2) 的 scale", qualifiedName)
                .isEqualTo(2);
    }

    private Column column(String qualifiedName) {
        assertThat(columns).as("schema-h2.sql 的 golden 列必须被 hbm2ddl 建出").containsKey(qualifiedName);
        return columns.get(qualifiedName);
    }

    /// 读物理列：表名 pattern 传 `null` 再按 `golden_` 前缀过滤。
    ///
    /// 不写死表名 pattern：同一条用例还会在 H2 的 PostgreSQL 兼容模式（`DATABASE_TO_LOWER=TRUE`）下
    /// 重跑，那里表名是小写而 MySQL 模式是大写，写死任何一种都会让另一条方言通道假红。
    private static Map<String, Column> readColumns(EntityManager entityManager) {
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            Map<String, Column> result = new LinkedHashMap<>();
            try (ResultSet rows = metadata.getColumns(null, null, null, "%")) {
                while (rows.next()) {
                    String table = rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                    if (table.startsWith("golden_")) {
                        result.put(
                                table + "." + rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                                new Column(
                                        rows.getString("TYPE_NAME"),
                                        rows.getInt("COLUMN_SIZE"),
                                        rows.getInt("DECIMAL_DIGITS")));
                    }
                }
            }
            return result;
        });
    }

    /// 一列在物理库里的形状：`COLUMN_SIZE` 对 varchar 是宽度、对 decimal 是 precision，`DECIMAL_DIGITS` 是 scale。
    private record Column(String typeName, int size, int scale) {

        int precision() {
            return size;
        }
    }
}
