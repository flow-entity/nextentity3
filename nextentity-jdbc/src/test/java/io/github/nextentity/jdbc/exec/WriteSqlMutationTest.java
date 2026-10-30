package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.meta.JdbcTypes;
import io.github.nextentity.jdbc.support.IntegerVersionedItem;
import io.github.nextentity.jdbc.support.VersionedItem;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

/// `WriteSql` 的版本零值填充与版本自增类型分派（设计 §3.11 / §5.4）。
class WriteSqlMutationTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcDialect dialect = JdbcDialect.of("H2");

    @Test
    void nextVersionTreatsNullAsZeroAndIncrementsNumericTypes() {
        assertThat(JdbcTypes.nextVersion(null, Integer.class)).isEqualTo(0);
        assertThat(JdbcTypes.nextVersion(1, Integer.class)).isEqualTo(2);
        assertThat(JdbcTypes.nextVersion(null, Long.class)).isEqualTo(0L);
        assertThat(JdbcTypes.nextVersion(1L, Long.class)).isEqualTo(2L);
        assertThat(JdbcTypes.nextVersion(null, Short.class)).isEqualTo((short) 0);
        assertThat(JdbcTypes.nextVersion((short) 1, Short.class)).isEqualTo((short) 2);
    }

    @Test
    void nextVersionRejectsTimestampWithTheTypeName() {
        assertThatThrownBy(() -> JdbcTypes.nextVersion(new Timestamp(0L), Timestamp.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("@Version 只支持 int / long / short（含包装类型）: " + Timestamp.class.getName());
    }

    @Test
    void insertBindsZeroForNullLongVersion() {
        JdbcEntityType<VersionedItem> entityType = meta.entityType(VersionedItem.class);
        SqlStatement statement = WriteSql.insert(entityType, new VersionedItem(1L, "a"), dialect);

        assertThat(statement.parameters()).containsExactly(1L, "a", 0L);
    }

    @Test
    void insertBindsZeroForNullIntegerVersion() {
        JdbcEntityType<IntegerVersionedItem> entityType = meta.entityType(IntegerVersionedItem.class);
        SqlStatement statement = WriteSql.insert(entityType, new IntegerVersionedItem(2L, "b"), dialect);

        assertThat(statement.parameters()).containsExactly(2L, "b", 0);
    }

    @Test
    void insertBindsIntegerZeroForNullPrimitiveIntVersion() {
        JdbcEntityType<PrimitiveIntVersionItem> entityType = meta.entityType(PrimitiveIntVersionItem.class);

        SqlStatement statement = WriteSql.insert(entityType, new PrimitiveIntVersionItem(1L, "a"), dialect);

        assertThat(statement.parameters()).containsExactly(1L, "a", 0);
        assertThat(statement.parameters().get(2)).isInstanceOf(Integer.class);
    }

    @Test
    void insertBindsLongZeroForNullPrimitiveLongVersion() {
        JdbcEntityType<VersionedItem> entityType = meta.entityType(VersionedItem.class);

        SqlStatement statement = WriteSql.insert(entityType, new VersionedItem(2L, "b"), dialect);

        assertThat(statement.parameters()).containsExactly(2L, "b", 0L);
        assertThat(statement.parameters().get(2)).isInstanceOf(Long.class);
    }

    @Entity
    @Table(name = "primitive_int_version_item")
    static class PrimitiveIntVersionItem {

        @Id
        Long id;

        String name;

        @Version
        int version;

        protected PrimitiveIntVersionItem() {}

        PrimitiveIntVersionItem(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
