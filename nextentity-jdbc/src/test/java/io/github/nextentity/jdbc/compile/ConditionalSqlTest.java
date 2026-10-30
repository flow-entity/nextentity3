package io.github.nextentity.jdbc.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.spec.Assignment;
import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.DeleteSpec;
import io.github.nextentity.core.spec.Literal;
import io.github.nextentity.core.spec.UpdateSpec;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.ColumnFlagsItem;
import io.github.nextentity.jdbc.support.EmbeddedProfile;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.QuotedOrder;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 条件改删的 SQL 快照与拒绝分支：`UPDATE t SET … WHERE …` / `DELETE FROM t WHERE …`。
///
/// 赋值值一律参数绑定、`null` 是 `SET c = NULL`；列引用不带表别名（见 `ConditionalSql`）。
class ConditionalSqlTest {

    private static final JdbcMeta META = new JdbcMeta();
    private static final JdbcDialect DIALECT = new H2Dialect();

    private static AttributePath path(Class<?> type, String... names) {
        return new AttributePath(type, List.of(names));
    }

    private static Comparison equals(AttributePath left, Object value) {
        return new Comparison(left, ComparisonOperator.EQ, Literal.of(value));
    }

    private static JdbcEntityType<?> entity(Class<?> type) {
        return META.entityType(type);
    }

    @Test
    void updateBindsEveryAssignmentAndWhereParameter() {
        UpdateSpec spec = new UpdateSpec(
                SnapshotItem.class,
                List.of(
                        new Assignment(path(SnapshotItem.class, "name"), "banana"),
                        new Assignment(path(SnapshotItem.class, "status"), ItemStatus.DONE)),
                equals(path(SnapshotItem.class, "id"), 7L));

        SqlStatement statement = ConditionalSql.update(spec, entity(SnapshotItem.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update item set name=?, status=? where id = ?");
        assertThat(statement.parameters()).containsExactly("banana", "DONE", 7L);
        assertThat(statement.columnTypes()).isEmpty();
    }

    @Test
    void updateRendersNullAssignmentAsNullLiteral() {
        UpdateSpec spec = new UpdateSpec(
                SnapshotItem.class,
                List.of(new Assignment(path(SnapshotItem.class, "note"), null)),
                equals(path(SnapshotItem.class, "id"), 7L));

        SqlStatement statement = ConditionalSql.update(spec, entity(SnapshotItem.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update item set note=NULL where id = ?");
        assertThat(statement.parameters()).containsExactly(7L);
    }

    @Test
    void updateSupportsEmbeddedColumn() {
        UpdateSpec spec = new UpdateSpec(
                EmbeddedProfile.class,
                List.of(new Assignment(path(EmbeddedProfile.class, "address", "city"), "Yantai")),
                equals(path(EmbeddedProfile.class, "id"), 5L));

        SqlStatement statement = ConditionalSql.update(spec, entity(EmbeddedProfile.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update embedded_profile set town=? where id = ?");
        assertThat(statement.parameters()).containsExactly("Yantai", 5L);
    }

    @Test
    void updateQuotesFlatColumnReferences() {
        UpdateSpec spec = new UpdateSpec(
                QuotedOrder.class,
                List.of(new Assignment(path(QuotedOrder.class, "desc"), "x")),
                equals(path(QuotedOrder.class, "id"), 1L));

        SqlStatement statement = ConditionalSql.update(spec, entity(QuotedOrder.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update \"order\" set \"desc\"=? where \"id\" = ?");
        assertThat(statement.parameters()).containsExactly("x", 1L);
    }

    @Test
    void updateRejectsEmbeddedObjectItself() {
        UpdateSpec spec = new UpdateSpec(
                EmbeddedProfile.class, List.of(new Assignment(path(EmbeddedProfile.class, "address"), null)), null);

        assertThatThrownBy(() -> ConditionalSql.update(spec, entity(EmbeddedProfile.class), DIALECT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件改删只支持嵌入字段列: " + EmbeddedProfile.class.getName() + ".address");
    }

    @Test
    void updateRejectsAssociationColumn() {
        UpdateSpec spec = new UpdateSpec(
                SnapshotAsset.class, List.of(new Assignment(path(SnapshotAsset.class, "owner", "name"), "bob")), null);

        assertThatThrownBy(() -> ConditionalSql.update(spec, entity(SnapshotAsset.class), DIALECT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件改删不支持关联列: " + SnapshotAsset.class.getName() + ".owner.name");
    }

    @Test
    void updateRejectsNotUpdatableColumn() {
        UpdateSpec spec = new UpdateSpec(
                ColumnFlagsItem.class, List.of(new Assignment(path(ColumnFlagsItem.class, "name"), "renamed")), null);

        assertThatThrownBy(() -> ConditionalSql.update(spec, entity(ColumnFlagsItem.class), DIALECT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件更新不支持 @Column(updatable=false) 列: " + ColumnFlagsItem.class.getName() + ".name");
    }

    @Test
    void deleteWithoutWhereIsWholeTable() {
        SqlStatement statement =
                ConditionalSql.delete(new DeleteSpec(SnapshotItem.class, null), entity(SnapshotItem.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("delete from item");
        assertThat(statement.parameters()).isEmpty();
    }

    @Test
    void deleteWithWhereBindsEnumLiteral() {
        DeleteSpec spec =
                new DeleteSpec(SnapshotItem.class, equals(path(SnapshotItem.class, "status"), ItemStatus.ACTIVE));

        SqlStatement statement = ConditionalSql.delete(spec, entity(SnapshotItem.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("delete from item where status = ?");
        assertThat(statement.parameters()).containsExactly("ACTIVE");
    }
}
