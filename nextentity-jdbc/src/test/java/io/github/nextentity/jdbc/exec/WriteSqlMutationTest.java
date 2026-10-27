package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.IntegerVersionedItem;
import io.github.nextentity.jdbc.support.VersionedItem;
import org.junit.jupiter.api.Test;

/// `WriteSql` 的版本零值填充与版本自增类型分派（设计 §3.11 / §5.4）。
class WriteSqlMutationTest {

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void nextVersionTreatsNullAsZeroAndIncrementsBoxedTypes() {
        assertThat(WriteSql.nextVersion(null)).isEqualTo(0);
        assertThat(WriteSql.nextVersion(1)).isEqualTo(2);
        assertThat(WriteSql.nextVersion(1L)).isEqualTo(2L);
    }

    @Test
    void nextVersionRejectsOtherTypesWithTheValueClassName() {
        assertThatThrownBy(() -> WriteSql.nextVersion((short) 1))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("@Version 只支持 Integer / Long: " + Short.class.getName());
    }

    @Test
    void insertBindsZeroForNullLongVersion() {
        JdbcEntityType<VersionedItem> entityType = meta.entityType(VersionedItem.class);
        SqlStatement statement = WriteSql.insert(entityType, new VersionedItem(1L, "a"));

        assertThat(statement.parameters()).containsExactly(1L, "a", 0L);
    }

    @Test
    void insertBindsZeroForNullIntegerVersion() {
        JdbcEntityType<IntegerVersionedItem> entityType = meta.entityType(IntegerVersionedItem.class);
        SqlStatement statement = WriteSql.insert(entityType, new IntegerVersionedItem(2L, "b"));

        assertThat(statement.parameters()).containsExactly(2L, "b", 0);
    }
}
