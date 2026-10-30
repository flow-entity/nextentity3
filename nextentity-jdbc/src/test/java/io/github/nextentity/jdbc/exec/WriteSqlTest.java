package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.ColumnFlagsItem;
import io.github.nextentity.jdbc.support.ConvertedItem;
import io.github.nextentity.jdbc.support.EmbeddedAddress;
import io.github.nextentity.jdbc.support.EmbeddedProfile;
import io.github.nextentity.jdbc.support.FlaggedAssociationEntity;
import io.github.nextentity.jdbc.support.GeneratedItem;
import io.github.nextentity.jdbc.support.GeneratedOnlyItem;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.MappedSubclassOwner;
import io.github.nextentity.jdbc.support.NumbersItem;
import io.github.nextentity.jdbc.support.OwnsMappedOwner;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import io.github.nextentity.jdbc.support.TimesItem;
import io.github.nextentity.jdbc.support.VersionedItem;
import io.github.nextentity.jdbc.support.VersionedShortItem;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/// 写入语句快照（3 条写入语句）：直接断言包内 `WriteSql` 的整句与绑定值——
/// 这条用例在本包（`io.github.nextentity.jdbc.exec`）里，`WriteSql` 不进 public 面。
///
/// 列序/SET 列序/`where` 片段都是契约：`GeneratedIdInsertContract` 的 `rowCount` 分支和
/// tests-jpa 的写回契约都依赖「`set` 与 `where` 之间是 `name=?`」这种无空格形态（已冻结）。
class WriteSqlTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcDialect dialect = JdbcDialect.of("H2");

    @Test
    void insertWithoutGeneratedKeyListsEveryScalarColumn() {
        SnapshotItem item = new SnapshotItem(7L, "banana", 10, 20, "n1", ItemStatus.ACTIVE);

        SqlStatement statement = WriteSql.insert(meta.entityType(SnapshotItem.class), item, dialect);

        assertThat(statement.sql())
                .isEqualTo("insert into item (id, name, score, score2, note, status) values (?, ?, ?, ?, ?, ?)");
        assertThat(statement.parameters()).containsExactly(7L, "banana", 10, 20, "n1", "ACTIVE");
    }

    @Test
    void insertSkipsGeneratedKeyColumn() {
        GeneratedItem item = new GeneratedItem("generated");

        SqlStatement statement = WriteSql.insert(meta.entityType(GeneratedItem.class), item, dialect);

        assertThat(statement.sql()).isEqualTo("insert into generated_item (name) values (?)");
        assertThat(statement.parameters()).containsExactly("generated");
    }

    @Test
    void insertRejectsEntityWithoutInsertableColumnWithActionableMessage() {
        GeneratedOnlyItem item = new GeneratedOnlyItem();

        assertThatThrownBy(() -> WriteSql.insert(meta.entityType(GeneratedOnlyItem.class), item, dialect))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageStartingWith("实体 " + GeneratedOnlyItem.class.getName() + " 没有可插入列")
                .hasMessageContaining("请至少保留一个可插入的标量列")
                .hasMessageContaining("@GeneratedValue");
    }

    @Test
    void insertSkipsJoinColumnMarkedNotInsertableButKeepsNotUpdatableOne() {
        FlaggedAssociationEntity entity =
                new FlaggedAssociationEntity(1L, "f", new SnapshotOwner(2L, "s"), new SnapshotOwner(3L, "l"));

        SqlStatement statement = WriteSql.insert(meta.entityType(FlaggedAssociationEntity.class), entity, dialect);

        assertThat(statement.sql()).isEqualTo("insert into flagged_association (id, name, locked_id) values (?, ?, ?)");
        assertThat(statement.parameters()).containsExactly(1L, "f", 3L);
    }

    @Test
    void updateSkipsJoinColumnMarkedNotUpdatableButKeepsNotInsertableOne() {
        FlaggedAssociationEntity entity =
                new FlaggedAssociationEntity(1L, "f", new SnapshotOwner(2L, "s"), new SnapshotOwner(3L, "l"));

        SqlStatement statement = WriteSql.update(meta.entityType(FlaggedAssociationEntity.class), entity, dialect);

        assertThat(statement.sql()).isEqualTo("update flagged_association set name=?, skipped_id=? where id=?");
        assertThat(statement.parameters()).containsExactly("f", 2L, 1L);
    }

    @Test
    void insertSkipsNullAssociationColumn() {
        SnapshotAsset asset = new SnapshotAsset(3L, "bandage", null);

        SqlStatement statement = WriteSql.insert(meta.entityType(SnapshotAsset.class), asset, dialect);

        assertThat(statement.sql()).isEqualTo("insert into asset (id, name) values (?, ?)");
        assertThat(statement.parameters()).containsExactly(3L, "bandage");
    }

    @Test
    void insertWritesJoinColumnFromAssociationId() {
        SnapshotAsset asset = new SnapshotAsset(3L, "anvil", new SnapshotOwner(1L, "alpha"));

        SqlStatement statement = WriteSql.insert(meta.entityType(SnapshotAsset.class), asset, dialect);

        assertThat(statement.sql()).isEqualTo("insert into asset (id, name, owner_id) values (?, ?, ?)");
        assertThat(statement.parameters()).containsExactly(3L, "anvil", 1L);
    }

    @Test
    void insertSkipsColumnsMarkedNotInsertable() {
        ColumnFlagsItem item = new ColumnFlagsItem(9L, "flag", "admin");

        SqlStatement statement = WriteSql.insert(meta.entityType(ColumnFlagsItem.class), item, dialect);

        assertThat(statement.sql()).isEqualTo("insert into column_flags_item (id, name) values (?, ?)");
        assertThat(statement.parameters()).containsExactly(9L, "flag");
    }

    @Test
    void insertFlattensEmbeddedColumnsWithAttributeOverride() {
        EmbeddedProfile profile = new EmbeddedProfile(5L, new EmbeddedAddress("Yantai", "No. 1"), "home");

        SqlStatement statement = WriteSql.insert(meta.entityType(EmbeddedProfile.class), profile, dialect);

        assertThat(statement.sql())
                .isEqualTo("insert into embedded_profile (id, town, address_street, label) values (?, ?, ?, ?)");
        assertThat(statement.parameters()).containsExactly(5L, "Yantai", "No. 1", "home");
    }

    @Test
    void insertBindsConvertedColumnThroughAttributeConverter() {
        ConvertedItem item = new ConvertedItem(1L, ItemStatus.DONE, "n");

        SqlStatement statement = WriteSql.insert(meta.entityType(ConvertedItem.class), item, dialect);

        assertThat(statement.sql()).isEqualTo("insert into converted_item (id, status, note) values (?, ?, ?)");
        assertThat(statement.parameters()).containsExactly(1L, "DONE", "n");
    }

    @Test
    void insertBindsEveryNumericAndBooleanColumnType() {
        NumbersItem item = new NumbersItem(
                1L,
                true,
                false,
                1.5d,
                2.5d,
                1.25f,
                2.25f,
                (short) 3,
                (short) 4,
                new BigDecimal("12.34"),
                new BigInteger("99"));

        SqlStatement statement = WriteSql.insert(meta.entityType(NumbersItem.class), item, dialect);

        assertThat(statement.sql())
                .isEqualTo(
                        "insert into numbers_item (id, active, enabled, ratio, ratioPrimitive, weight, weightPrimitive,"
                                + " count, countPrimitive, price, serial) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        assertThat(statement.parameters())
                .containsExactly(
                        1L,
                        true,
                        false,
                        1.5d,
                        2.5d,
                        1.25f,
                        2.25f,
                        (short) 3,
                        (short) 4,
                        new BigDecimal("12.34"),
                        new BigDecimal("99"));
    }

    @Test
    void insertConvertsJavaTimeAndSqlTimeColumns() {
        LocalDate birthDate = LocalDate.of(1990, 1, 2);
        LocalDateTime createdAt = LocalDateTime.of(2024, 3, 4, 5, 6, 7);
        LocalTime wakeTime = LocalTime.of(6, 30);
        Instant syncedAt = Instant.parse("2024-01-02T03:04:05Z");
        Date sqlDate = Date.valueOf(LocalDate.of(2020, 5, 6));
        Time sqlTime = Time.valueOf(LocalTime.of(7, 8, 9));
        Timestamp sqlTimestamp = Timestamp.valueOf(LocalDateTime.of(2021, 6, 7, 8, 9, 10));
        TimesItem item = new TimesItem(1L, birthDate, createdAt, wakeTime, syncedAt, sqlDate, sqlTime, sqlTimestamp);

        SqlStatement statement = WriteSql.insert(meta.entityType(TimesItem.class), item, dialect);

        assertThat(statement.sql())
                .isEqualTo("insert into times_item (id, birthDate, createdAt, wakeTime, syncedAt, sqlDate, sqlTime,"
                        + " sqlTimestamp) values (?, ?, ?, ?, ?, ?, ?, ?)");
        assertThat(statement.parameters())
                .containsExactly(
                        1L,
                        Date.valueOf(birthDate),
                        Timestamp.valueOf(createdAt),
                        Time.valueOf(wakeTime),
                        Timestamp.from(syncedAt),
                        sqlDate,
                        sqlTime,
                        sqlTimestamp);
    }

    @Test
    void updateSetsScalarColumnsAndMatchesId() {
        SnapshotItem item = new SnapshotItem(7L, "banana", 10, 20, "n1", ItemStatus.DONE);

        SqlStatement statement = WriteSql.update(meta.entityType(SnapshotItem.class), item, dialect);

        assertThat(statement.sql()).isEqualTo("update item set name=?, score=?, score2=?, note=?, status=? where id=?");
        assertThat(statement.parameters()).containsExactly("banana", 10, 20, "n1", "DONE", 7L);
    }

    @Test
    void updateAppendsVersionInSetAndWhere() {
        VersionedItem item = new VersionedItem(7L, "banana");
        item.version(2L);

        SqlStatement statement = WriteSql.update(meta.entityType(VersionedItem.class), item, dialect);

        assertThat(statement.sql()).isEqualTo("update versioned_item set name=?, version=? where id=? and version=?");
        assertThat(statement.parameters()).containsExactly("banana", 3L, 7L, 2L);
    }

    @Test
    void updateIncrementsShortVersion() {
        VersionedShortItem item = new VersionedShortItem(7L, "banana");
        item.version((short) 2);

        SqlStatement statement = WriteSql.update(meta.entityType(VersionedShortItem.class), item, dialect);

        assertThat(statement.sql())
                .isEqualTo("update short_versioned_item set name=?, version=? where id=? and version=?");
        assertThat(statement.parameters()).containsExactly("banana", (short) 3, 7L, (short) 2);
    }

    @Test
    void updateWritesJoinColumnFromAssociationId() {
        SnapshotAsset asset = new SnapshotAsset(3L, "anvil", new SnapshotOwner(1L, "alpha"));

        SqlStatement statement = WriteSql.update(meta.entityType(SnapshotAsset.class), asset, dialect);

        assertThat(statement.sql()).isEqualTo("update asset set name=?, owner_id=? where id=?");
        assertThat(statement.parameters()).containsExactly("anvil", 1L, 3L);
    }

    @Test
    void updateSkipsColumnsMarkedNotUpdatable() {
        ColumnFlagsItem item = new ColumnFlagsItem(9L, "flag", "admin");

        SqlStatement statement = WriteSql.update(meta.entityType(ColumnFlagsItem.class), item, dialect);

        assertThat(statement.sql()).isEqualTo("update column_flags_item set createdBy=? where id=?");
        assertThat(statement.parameters()).containsExactly("admin", 9L);
    }

    @Test
    void updateFlattensEmbeddedColumns() {
        EmbeddedProfile profile = new EmbeddedProfile(5L, new EmbeddedAddress("Yantai", "No. 1"), "home");

        SqlStatement statement = WriteSql.update(meta.entityType(EmbeddedProfile.class), profile, dialect);

        assertThat(statement.sql())
                .isEqualTo("update embedded_profile set town=?, address_street=?, label=? where id=?");
        assertThat(statement.parameters()).containsExactly("Yantai", "No. 1", "home", 5L);
    }

    @Test
    void insertWritesJoinColumnFromIdDeclaredInMappedSuperclass() {
        OwnsMappedOwner entity = new OwnsMappedOwner(3L, new MappedSubclassOwner(7L, "owner"));

        SqlStatement statement = WriteSql.insert(meta.entityType(OwnsMappedOwner.class), entity, dialect);

        assertThat(statement.sql()).isEqualTo("insert into owns_mapped_owner (id, owner_id) values (?, ?)");
        assertThat(statement.parameters()).containsExactly(3L, 7L);
    }

    @Test
    void parametersAreImmutableCopy() {
        SnapshotItem item = new SnapshotItem(1L, "a", null, null, null, ItemStatus.NEW);
        SqlStatement statement = WriteSql.insert(meta.entityType(SnapshotItem.class), item, dialect);

        assertThat(statement.parameters()).containsExactly(1L, "a", null, null, null, "NEW");
        assertThatThrownBy(() -> statement.parameters().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
