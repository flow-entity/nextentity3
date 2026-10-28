package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.IntegerVersionedItem;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.OrdinalItem;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.VersionedItem;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

/// 写入 SQL 的版本列/枚举绑定与「没有可写标量列」拒绝（§3.11、§4.3 #25）：
/// SET 列序、`where id=? [and version=?]`、参数顺序、版本旧值 +1、ORDINAL 绑定序号。
class WriteSqlVersionTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcDialect dialect = JdbcDialect.of("H2");

    @Test
    void insertWritesZeroVersionForNullVersion() {
        SqlStatement longVersion =
                WriteSql.insert(meta.entityType(VersionedItem.class), new VersionedItem(3L, "v"), dialect);
        assertThat(longVersion.sql()).isEqualTo("insert into versioned_item (id, name, version) values (?, ?, ?)");
        assertThat(longVersion.parameters()).containsExactly(3L, "v", 0L);

        SqlStatement integerVersion = WriteSql.insert(
                meta.entityType(IntegerVersionedItem.class), new IntegerVersionedItem(4L, "v"), dialect);
        assertThat(integerVersion.parameters()).containsExactly(4L, "v", 0);
    }

    @Test
    void insertBindsOrdinalEnumAsNumber() {
        SqlStatement statement =
                WriteSql.insert(meta.entityType(OrdinalItem.class), new OrdinalItem(5L, ItemStatus.DONE), dialect);

        assertThat(statement.sql()).isEqualTo("insert into ordinal_item (id, status) values (?, ?)");
        assertThat(statement.parameters()).containsExactly(5L, ItemStatus.DONE.ordinal());
    }

    @Test
    void updateUsesNextIntegerVersion() {
        IntegerVersionedItem item = new IntegerVersionedItem(6L, "v");
        setVersion(item, 2);

        SqlStatement statement = WriteSql.update(meta.entityType(IntegerVersionedItem.class), item, dialect);

        assertThat(statement.sql())
                .isEqualTo("update integer_versioned_item set name=?, version=? where id=? and version=?");
        assertThat(statement.parameters()).containsExactly("v", 3, 6L, 2);
    }

    @Test
    void updateWithoutVersionMatchesOnlyId() {
        SqlStatement statement = WriteSql.update(
                meta.entityType(SnapshotItem.class), new SnapshotItem(7L, "n", 1, 2, "note", ItemStatus.NEW), dialect);

        assertThat(statement.sql()).isEqualTo("update item set name=?, score=?, score2=?, note=?, status=? where id=?");
        assertThat(statement.parameters()).containsExactly("n", 1, 2, "note", "NEW", 7L);
    }

    @Test
    void updateOfIdOnlyEntityIsRejected() {
        assertThatThrownBy(() -> WriteSql.update(meta.entityType(IdOnly.class), new IdOnly(8L), dialect))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("实体 " + IdOnly.class.getName() + " 没有可写标量列，update 无法生成 SET 子句");
    }

    @Test
    void versionedEntityWithoutScalarColumnsStillUpdatesVersion() {
        SqlStatement statement = WriteSql.update(meta.entityType(VersionOnly.class), new VersionOnly(9L), dialect);

        assertThat(statement.sql()).isEqualTo("update write_sql_version_only set version=? where id=? and version=?");
        assertThat(statement.parameters()).containsExactly(1L, 9L, 0L);
    }

    private static void setVersion(IntegerVersionedItem item, int version) {
        try {
            var field = IntegerVersionedItem.class.getDeclaredField("version");
            field.setAccessible(true);
            field.set(item, version);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Entity
    @Table(name = "write_sql_id_only")
    static class IdOnly {

        @Id
        private Long id;

        protected IdOnly() {}

        IdOnly(Long id) {
            this.id = id;
        }
    }

    @Entity
    @Table(name = "write_sql_version_only")
    static class VersionOnly {

        @Id
        private Long id;

        @Version
        private long version;

        protected VersionOnly() {}

        VersionOnly(Long id) {
            this.id = id;
        }
    }
}
