package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

/// 标识符引用：注解里用反引号标记的保留字 / 存量标识符，meta 扫描期剥引号并记录 quoted 标志。
///
/// 依据：（`@Table` / `@Column` / `@JoinColumn` 名字反引号包裹 = quoted；比较一律裸名）。
class JdbcQuotedNameTest {

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void quotedTableNameIsStrippedAndFlagged() {
        JdbcEntityType<QuotedOrder> type = meta.entityType(QuotedOrder.class);

        assertThat(type.table()).isEqualTo("order");
        assertThat(type.isTableQuoted()).isTrue();
    }

    @Test
    void unquotedTableNameKeepsOldShape() {
        JdbcEntityType<SnapshotItem> type = meta.entityType(SnapshotItem.class);

        assertThat(type.table()).isEqualTo("item");
        assertThat(type.isTableQuoted()).isFalse();
    }

    @Test
    void quotedColumnNamesAreStrippedAndFlagged() {
        JdbcEntityType<QuotedOrder> type = meta.entityType(QuotedOrder.class);

        assertThat(type.attribute("id").column()).isEqualTo("id");
        assertThat(type.attribute("id").isColumnQuoted()).isTrue();
        assertThat(type.attribute("desc").column()).isEqualTo("desc");
        assertThat(type.attribute("desc").isColumnQuoted()).isTrue();
        assertThat(type.attribute("key").column()).isEqualTo("key");
        assertThat(type.attribute("key").isColumnQuoted()).isTrue();
        assertThat(type.column("desc")).isEqualTo("desc");
    }

    @Test
    void unquotedColumnNamesKeepOldShape() {
        JdbcEntityType<SnapshotItem> type = meta.entityType(SnapshotItem.class);

        assertThat(type.attribute("name").column()).isEqualTo("name");
        assertThat(type.attribute("name").isColumnQuoted()).isFalse();
        assertThat(type.isTableQuoted()).isFalse();
    }

    @Test
    void quotedJoinColumnAndReferencedColumnAreStrippedAndFlagged() {
        JdbcEntityType<QuotedAsset> type = meta.entityType(QuotedAsset.class);
        JdbcEntityAttribute owner = type.attribute("owner");

        assertThat(owner.isAssociation()).isTrue();
        assertThat(owner.joinColumn()).isEqualTo("owner_key");
        assertThat(owner.isJoinColumnQuoted()).isTrue();
        assertThat(owner.isReferencedColumnQuoted()).isTrue();
        assertThat(owner.associationTarget()).isEqualTo(QuotedOwner.class);
    }

    @Test
    void unquotedJoinColumnKeepsOldShape() {
        JdbcEntityAttribute owner = meta.entityType(SnapshotAsset.class).attribute("owner");

        assertThat(owner.joinColumn()).isEqualTo("owner_id");
        assertThat(owner.isJoinColumnQuoted()).isFalse();
        assertThat(owner.isReferencedColumnQuoted()).isFalse();
    }

    @Test
    void referencedColumnNameIsComparedAfterStrippingBackticks() {
        // `id` 剥引号后等于目标 @Id 列名 → 放行（不抛）
        assertThat(meta.entityType(QuotedAsset.class).attribute("owner").joinColumn())
                .isEqualTo("owner_key");
    }

    @Test
    void mismatchedReferencedColumnNameIsStillRejectedWithBareName() {
        assertThatThrownBy(() -> meta.entityType(BadReferencedAsset.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("@JoinColumn(referencedColumnName=other) 未支持")
                .hasMessageContaining(BadReferencedAsset.class.getName() + ".owner");
    }

    /// 表名与全部列名都带反引号：写侧应渲染为 `` `order` ``（自定义方言决定引用形态）。
    @Entity
    @Table(name = "`order`")
    static class QuotedOrder {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "`id`")
        Long id;

        @Column(name = "`desc`")
        String desc;

        @Column(name = "`key`")
        Integer key;

        protected QuotedOrder() {}
    }

    @Entity
    @Table(name = "`quoted_asset`")
    static class QuotedAsset {

        @Id
        @Column(name = "`id`")
        Long id;

        @ManyToOne
        @JoinColumn(name = "`owner_key`", referencedColumnName = "`id`")
        QuotedOwner owner;

        protected QuotedAsset() {}
    }

    @Entity
    @Table(name = "`quoted_owner`")
    static class QuotedOwner {

        @Id
        @Column(name = "`id`")
        Long id;

        protected QuotedOwner() {}
    }

    @Entity
    @Table(name = "`bad_referenced_asset`")
    static class BadReferencedAsset {

        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "`owner_id`", referencedColumnName = "`other`")
        QuotedOwner owner;

        protected BadReferencedAsset() {}
    }

    @Test
    void scalarAttributesReportNoQuotedAssociationColumns() {
        JdbcEntityAttribute name = meta.entityType(SnapshotItem.class).attribute("name");

        assertThat(name.isJoinColumnQuoted()).isFalse();
        assertThat(name.isReferencedColumnQuoted()).isFalse();
    }

    @Test
    void joinColumnQuotedAndReferencedColumnQuotedAreIndependent() {
        JdbcEntityAttribute quotedJoin = meta.entityType(MixedQuotedAsset.class).attribute("owner");
        assertThat(quotedJoin.isJoinColumnQuoted()).isTrue();
        assertThat(quotedJoin.isReferencedColumnQuoted()).isFalse();

        JdbcEntityAttribute quotedReferenced =
                meta.entityType(ReferencedOnlyQuotedAsset.class).attribute("owner");
        assertThat(quotedReferenced.isJoinColumnQuoted()).isFalse();
        assertThat(quotedReferenced.isReferencedColumnQuoted()).isTrue();
    }

    @Test
    void backtickOnOneSideOnlyIsNotAQuotedName() {
        JdbcEntityType<SuffixBacktickTable> suffix = meta.entityType(SuffixBacktickTable.class);
        assertThat(suffix.table()).isEqualTo("suffix`");
        assertThat(suffix.isTableQuoted()).isFalse();

        JdbcEntityType<PrefixBacktickColumn> prefix = meta.entityType(PrefixBacktickColumn.class);
        assertThat(prefix.column("name")).isEqualTo("`prefix");
        assertThat(prefix.attribute("name").isColumnQuoted()).isFalse();
    }

    @Test
    void explicitAndImplicitIdColumnNamesBothDriveReferencedColumnChecks() {
        assertThat(meta.entityType(ExplicitIdAsset.class).attribute("owner").joinColumn())
                .isEqualTo("owner_ref");
        assertThat(meta.entityType(ImplicitIdAsset.class).attribute("owner").joinColumn())
                .isEqualTo("owner_ref");
    }

    @Entity
    @Table(name = "mixed_quoted_asset")
    static class MixedQuotedAsset {

        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "`owner_key`")
        PlainOwner owner;

        protected MixedQuotedAsset() {}
    }

    @Entity
    @Table(name = "referenced_only_quoted_asset")
    static class ReferencedOnlyQuotedAsset {

        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "owner_id", referencedColumnName = "`id`")
        PlainOwner owner;

        protected ReferencedOnlyQuotedAsset() {}
    }

    @Entity
    @Table(name = "plain_owner")
    static class PlainOwner {

        @Id
        Long id;

        protected PlainOwner() {}
    }

    @Entity
    @Table(name = "suffix`")
    static class SuffixBacktickTable {

        @Id
        Long id;

        protected SuffixBacktickTable() {}
    }

    @Entity
    @Table(name = "prefix_backtick_column")
    static class PrefixBacktickColumn {

        @Id
        Long id;

        @Column(name = "`prefix")
        String name;

        protected PrefixBacktickColumn() {}
    }

    @Entity
    @Table(name = "explicit_id_owner")
    static class ExplicitIdOwner {

        @Id
        @Column(name = "owner_key")
        Long id;

        protected ExplicitIdOwner() {}
    }

    @Entity
    @Table(name = "explicit_id_asset")
    static class ExplicitIdAsset {

        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "owner_ref", referencedColumnName = "OWNER_KEY")
        ExplicitIdOwner owner;

        protected ExplicitIdAsset() {}
    }

    @Entity
    @Table(name = "implicit_id_asset")
    static class ImplicitIdAsset {

        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "owner_ref", referencedColumnName = "ID")
        PlainOwner owner;

        protected ImplicitIdAsset() {}
    }

    @Test
    void emptyColumnNameOnTargetIdFallsBackToFieldNameForReferencedColumnCheck() {
        assertThat(meta.entityType(EmptyColumnNameIdAsset.class)
                        .attribute("owner")
                        .joinColumn())
                .isEqualTo("owner_ref");
    }

    @Entity
    @Table(name = "empty_column_name_owner")
    static class EmptyColumnNameOwner {

        @Id
        @Column(name = "")
        Long code;

        protected EmptyColumnNameOwner() {}
    }

    @Entity
    @Table(name = "empty_column_name_asset")
    static class EmptyColumnNameIdAsset {

        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "owner_ref", referencedColumnName = "code")
        EmptyColumnNameOwner owner;

        protected EmptyColumnNameIdAsset() {}
    }
}
