package io.github.nextentity.tests.jdbc.extended;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.testing.ItemStatus;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// 3.1.0 能力面的落库证据（H2）：扩展列类型往返、`@Embedded` 扁平列、`@Convert`、`@Column(insertable=false)`、
/// `short` 生成键回填、`short` 版本自增。SQL 快照在 `nextentity-jdbc` 的 `WriteSqlTest` / `JdbcTypesTest`。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtendedCapabilitiesE2ETest {

    private final ExtendedSupport support = support();

    /// 方言通道的接线：MySQL 用例继承本类并覆盖此方法，保证两条通道跑同一批用例。
    protected ExtendedSupport support() {
        return new ExtendedSupport("extended_capabilities_test");
    }

    @BeforeEach
    void clear() {
        for (String table : List.of(
                "extended_item",
                "embedded_profile",
                "converted_item",
                "flag_column_item",
                "short_versioned_item",
                "short_generated_item")) {
            support.execute("delete from " + table);
        }
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    @Test
    void extendedColumnTypesRoundTrip() {
        Timestamp sqlTimestamp = Timestamp.valueOf("2024-05-01 09:15:00");
        ExtendedEntities.ExtendedItem item = new ExtendedEntities.ExtendedItem(
                1L,
                Boolean.TRUE,
                2.5d,
                1.25f,
                (short) 7,
                new BigDecimal("12.34"),
                BigInteger.valueOf(99L),
                LocalDate.of(2024, 5, 1),
                LocalDateTime.of(2024, 5, 1, 10, 0),
                LocalTime.of(7, 30),
                Instant.parse("2024-05-01T10:00:00Z"),
                Date.valueOf("2024-05-01"),
                Time.valueOf("07:30:00"),
                sqlTimestamp);

        support.operations(ExtendedEntities.ExtendedItem.class).insert(item);

        List<ExtendedEntities.ExtendedItem> rows = support.query(ExtendedEntities.ExtendedItem.class);
        assertThat(rows).hasSize(1);
        ExtendedEntities.ExtendedItem read = rows.get(0);
        assertThat(read.id).isEqualTo(1L);
        assertThat(read.flag).isTrue();
        assertThat(read.ratio).isEqualTo(2.5d);
        assertThat(read.weight).isEqualTo(1.25f);
        assertThat(read.count).isEqualTo((short) 7);
        assertThat(read.price).isEqualByComparingTo("12.34");
        assertThat(read.serial).isEqualTo(BigInteger.valueOf(99L));
        assertThat(read.birthDate).isEqualTo(LocalDate.of(2024, 5, 1));
        assertThat(read.createdAt).isEqualTo(LocalDateTime.of(2024, 5, 1, 10, 0));
        assertThat(read.wakeTime).isEqualTo(LocalTime.of(7, 30));
        assertThat(read.syncedAt).isEqualTo(Instant.parse("2024-05-01T10:00:00Z"));
        assertThat(read.sqlDate).isEqualTo(Date.valueOf("2024-05-01"));
        assertThat(read.sqlTime).isEqualTo(Time.valueOf("07:30:00"));
        assertThat(read.sqlTimestamp).isEqualTo(sqlTimestamp);
    }

    @Test
    void embeddedColumnsAreFlattenedAndRoundTrip() {
        ExtendedEntities.EmbeddedProfile profile =
                new ExtendedEntities.EmbeddedProfile(1L, new ExtendedEntities.Address("Yantai", "No. 1"), "home");

        support.operations(ExtendedEntities.EmbeddedProfile.class).insert(profile);

        List<ExtendedEntities.EmbeddedProfile> rows = support.query(ExtendedEntities.EmbeddedProfile.class);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).label).isEqualTo("home");
        assertThat(rows.get(0).address.city).isEqualTo("Yantai");
        assertThat(rows.get(0).address.street).isEqualTo("No. 1");
    }

    @Test
    void convertBridgesEnumToDatabaseColumn() {
        ExtendedEntities.ConvertedStatusItem item =
                new ExtendedEntities.ConvertedStatusItem(1L, ItemStatus.ACTIVE, "n");

        support.operations(ExtendedEntities.ConvertedStatusItem.class).insert(item);

        assertThat(support.query(ExtendedEntities.ConvertedStatusItem.class).get(0).status)
                .isEqualTo(ItemStatus.ACTIVE);
        assertThat(support.queryString("select status from converted_item where id = 1"))
                .isEqualTo("ACTIVE");
    }

    @Test
    void notInsertableColumnIsSelectedButNotWritten() {
        support.execute("insert into flag_column_item (id, name, created_by) values (1, 'raw', 'admin')");
        assertThat(support.query(ExtendedEntities.FlagColumnItem.class).get(0).createdBy)
                .isEqualTo("admin");

        support.operations(ExtendedEntities.FlagColumnItem.class)
                .insert(new ExtendedEntities.FlagColumnItem(2L, "written", "ignored"));

        assertThat(support.queryString("select created_by from flag_column_item where id = 2"))
                .isNull();
    }

    @Test
    void shortGeneratedKeyIsWrittenBack() {
        ExtendedEntities.ShortGeneratedItem item = new ExtendedEntities.ShortGeneratedItem("a");

        support.operations(ExtendedEntities.ShortGeneratedItem.class).insert(item);

        assertThat(item.id).isEqualTo((short) 1);
        assertThat(support.query(ExtendedEntities.ShortGeneratedItem.class).get(0).name)
                .isEqualTo("a");
    }

    @Test
    void shortVersionIncrementsOnUpdate() {
        ExtendedEntities.ShortVersionedItem item = new ExtendedEntities.ShortVersionedItem(1L, "a");
        support.operations(ExtendedEntities.ShortVersionedItem.class).insert(item);
        assertThat(item.version).isEqualTo((short) 0);

        item.name = "b";
        support.operations(ExtendedEntities.ShortVersionedItem.class).update(item);

        assertThat(item.version).isEqualTo((short) 1);
        ExtendedEntities.ShortVersionedItem read =
                support.query(ExtendedEntities.ShortVersionedItem.class).get(0);
        assertThat(read.name).isEqualTo("b");
        assertThat(read.version).isEqualTo((short) 1);
    }
}
