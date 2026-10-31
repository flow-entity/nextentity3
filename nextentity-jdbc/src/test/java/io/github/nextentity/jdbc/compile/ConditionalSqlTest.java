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
import io.github.nextentity.jdbc.support.ConvertedIdAssociationItem;
import io.github.nextentity.jdbc.support.EmbeddedProfile;
import io.github.nextentity.jdbc.support.FlaggedAssociationEntity;
import io.github.nextentity.jdbc.support.IgnoredAssociationItem;
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

        SqlStatement statement = ConditionalSql.update(spec, META, entity(SnapshotItem.class), DIALECT);

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

        SqlStatement statement = ConditionalSql.update(spec, META, entity(SnapshotItem.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update item set note=NULL where id = ?");
        assertThat(statement.parameters()).containsExactly(7L);
    }

    @Test
    void updateSupportsEmbeddedColumn() {
        UpdateSpec spec = new UpdateSpec(
                EmbeddedProfile.class,
                List.of(new Assignment(path(EmbeddedProfile.class, "address", "city"), "Yantai")),
                equals(path(EmbeddedProfile.class, "id"), 5L));

        SqlStatement statement = ConditionalSql.update(spec, META, entity(EmbeddedProfile.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update embedded_profile set town=? where id = ?");
        assertThat(statement.parameters()).containsExactly("Yantai", 5L);
    }

    @Test
    void updateQuotesFlatColumnReferences() {
        UpdateSpec spec = new UpdateSpec(
                QuotedOrder.class,
                List.of(new Assignment(path(QuotedOrder.class, "desc"), "x")),
                equals(path(QuotedOrder.class, "id"), 1L));

        SqlStatement statement = ConditionalSql.update(spec, META, entity(QuotedOrder.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update \"order\" set \"desc\"=? where \"id\" = ?");
        assertThat(statement.parameters()).containsExactly("x", 1L);
    }

    @Test
    void updateRejectsEmbeddedObjectItself() {
        UpdateSpec spec = new UpdateSpec(
                EmbeddedProfile.class, List.of(new Assignment(path(EmbeddedProfile.class, "address"), null)), null);

        assertThatThrownBy(() -> ConditionalSql.update(spec, META, entity(EmbeddedProfile.class), DIALECT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件改删只支持嵌入字段列: " + EmbeddedProfile.class.getName() + ".address");
    }

    @Test
    void updateClearsForeignKeyThroughAssociationId() {
        UpdateSpec spec = new UpdateSpec(
                SnapshotAsset.class,
                List.of(new Assignment(path(SnapshotAsset.class, "owner", "id"), null)),
                equals(path(SnapshotAsset.class, "id"), 1L));

        SqlStatement statement = ConditionalSql.update(spec, META, entity(SnapshotAsset.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update asset set owner_id=NULL where id = ?");
        assertThat(statement.parameters()).containsExactly(1L);
    }

    @Test
    void updateSetsForeignKeyThroughAssociationId() {
        UpdateSpec spec = new UpdateSpec(
                SnapshotAsset.class,
                List.of(new Assignment(path(SnapshotAsset.class, "owner", "id"), 20L)),
                equals(path(SnapshotAsset.class, "id"), 1L));

        SqlStatement statement = ConditionalSql.update(spec, META, entity(SnapshotAsset.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update asset set owner_id=? where id = ?");
        assertThat(statement.parameters()).containsExactly(20L, 1L);
    }

    @Test
    void whereOnAssociationIdFiltersByForeignKey() {
        DeleteSpec spec = new DeleteSpec(SnapshotAsset.class, equals(path(SnapshotAsset.class, "owner", "id"), 10L));

        SqlStatement statement = ConditionalSql.delete(spec, META, entity(SnapshotAsset.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("delete from asset where owner_id = ?");
        assertThat(statement.parameters()).containsExactly(10L);
    }

    @Test
    void updateRejectsAssociationObjectItself() {
        UpdateSpec spec = new UpdateSpec(
                SnapshotAsset.class, List.of(new Assignment(path(SnapshotAsset.class, "owner"), null)), null);

        assertThatThrownBy(() -> ConditionalSql.update(spec, META, entity(SnapshotAsset.class), DIALECT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件改删不支持关联列: " + SnapshotAsset.class.getName() + ".owner");
    }

    @Test
    void updateRejectsAssociationColumnOtherThanId() {
        UpdateSpec spec = new UpdateSpec(
                SnapshotAsset.class, List.of(new Assignment(path(SnapshotAsset.class, "owner", "name"), "bob")), null);

        assertThatThrownBy(() -> ConditionalSql.update(spec, META, entity(SnapshotAsset.class), DIALECT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件改删不支持关联路径: " + SnapshotAsset.class.getName() + ".owner.name");
    }

    @Test
    void updateQuotesForeignKeyColumnWhenJoinColumnNameIsQuoted() {
        UpdateSpec spec = new UpdateSpec(
                QuotedOrder.class,
                List.of(new Assignment(path(QuotedOrder.class, "owner", "id"), 20L)),
                equals(path(QuotedOrder.class, "id"), 1L));

        SqlStatement statement = ConditionalSql.update(spec, META, entity(QuotedOrder.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update \"order\" set \"order_id\"=? where \"id\" = ?");
        assertThat(statement.parameters()).containsExactly(20L, 1L);
    }

    @Test
    void whereOnQuotedAssociationIdFiltersByQuotedForeignKey() {
        DeleteSpec spec = new DeleteSpec(QuotedOrder.class, equals(path(QuotedOrder.class, "owner", "id"), 10L));

        SqlStatement statement = ConditionalSql.delete(spec, META, entity(QuotedOrder.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("delete from \"order\" where \"order_id\" = ?");
        assertThat(statement.parameters()).containsExactly(10L);
    }

    @Test
    void updateConvertsForeignKeyValueThroughTargetIdConverter() {
        UpdateSpec spec = new UpdateSpec(
                ConvertedIdAssociationItem.class,
                List.of(new Assignment(path(ConvertedIdAssociationItem.class, "owner", "id"), 9L)),
                equals(path(ConvertedIdAssociationItem.class, "id"), 1L));

        SqlStatement statement = ConditionalSql.update(spec, META, entity(ConvertedIdAssociationItem.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("update converted_id_association set owner_id=? where id = ?");
        assertThat(statement.parameters()).containsExactly("9", 1L);
    }

    @Test
    void updateRejectsAssociationPathDeeperThanId() {
        UpdateSpec spec = new UpdateSpec(
                SnapshotAsset.class,
                List.of(new Assignment(path(SnapshotAsset.class, "owner", "id", "extra"), 1L)),
                null);

        assertThatThrownBy(() -> ConditionalSql.update(spec, META, entity(SnapshotAsset.class), DIALECT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件改删不支持关联路径: " + SnapshotAsset.class.getName() + ".owner.id.extra");
    }

    @Test
    void updateRejectsReadOnlyForeignKey() {
        UpdateSpec spec = new UpdateSpec(
                FlaggedAssociationEntity.class,
                List.of(new Assignment(path(FlaggedAssociationEntity.class, "locked", "id"), null)),
                null);

        assertThatThrownBy(() -> ConditionalSql.update(spec, META, entity(FlaggedAssociationEntity.class), DIALECT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件更新不支持 @Column(updatable=false) 列: " + FlaggedAssociationEntity.class.getName()
                        + ".locked.id");
    }

    @Test
    void updateRejectsIgnoredCollectionPath() {
        UpdateSpec spec = new UpdateSpec(
                IgnoredAssociationItem.class,
                List.of(new Assignment(path(IgnoredAssociationItem.class, "owners", "id"), 1L)),
                null);

        assertThatThrownBy(() -> ConditionalSql.update(spec, META, entity(IgnoredAssociationItem.class), DIALECT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件改删不支持关联路径: " + IgnoredAssociationItem.class.getName() + ".owners.id");
    }

    @Test
    void updateRejectsNotUpdatableColumn() {
        UpdateSpec spec = new UpdateSpec(
                ColumnFlagsItem.class, List.of(new Assignment(path(ColumnFlagsItem.class, "name"), "renamed")), null);

        assertThatThrownBy(() -> ConditionalSql.update(spec, META, entity(ColumnFlagsItem.class), DIALECT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("条件更新不支持 @Column(updatable=false) 列: " + ColumnFlagsItem.class.getName() + ".name");
    }

    @Test
    void deleteWithoutWhereIsWholeTable() {
        SqlStatement statement = ConditionalSql.delete(
                new DeleteSpec(SnapshotItem.class, null), META, entity(SnapshotItem.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("delete from item");
        assertThat(statement.parameters()).isEmpty();
    }

    @Test
    void deleteWithWhereBindsEnumLiteral() {
        DeleteSpec spec =
                new DeleteSpec(SnapshotItem.class, equals(path(SnapshotItem.class, "status"), ItemStatus.ACTIVE));

        SqlStatement statement = ConditionalSql.delete(spec, META, entity(SnapshotItem.class), DIALECT);

        assertThat(statement.sql()).isEqualTo("delete from item where status = ?");
        assertThat(statement.parameters()).containsExactly("ACTIVE");
    }
}
