package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jdbc.compile.SqlStatement;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import io.github.nextentity.jdbc.meta.JdbcEntityType;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.SnapshotItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

/// Phase F：meta 标记为 quoted 的标识符在写侧过 `JdbcDialect#quote`；未标记的保持裸名（零 churn）。
class WriteSqlQuotedTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final JdbcDialect dialect = new DoubleQuoteDialect();

    @Test
    void quotedTableAndColumnsAreQuotedOnInsert() {
        JdbcEntityType<QuotedOrder> type = meta.entityType(QuotedOrder.class);

        SqlStatement statement = WriteSql.insert(type, new QuotedOrder("one", 11), dialect);

        assertThat(statement.sql()).isEqualTo("insert into \"order\" (\"desc\", \"key\") values (?, ?)");
        assertThat(statement.parameters()).containsExactly("one", 11);
    }

    @Test
    void quotedTableAndColumnsAreQuotedOnUpdate() {
        JdbcEntityType<QuotedOrder> type = meta.entityType(QuotedOrder.class);
        QuotedOrder order = new QuotedOrder("two", 22);
        order.id = 3L;

        SqlStatement statement = WriteSql.update(type, order, dialect);

        assertThat(statement.sql()).isEqualTo("update \"order\" set \"desc\"=?, \"key\"=? where \"id\"=?");
        assertThat(statement.parameters()).containsExactly("two", 22, 3L);
    }

    @Test
    void unquotedIdentifiersStayBareEvenWithQuotingDialect() {
        JdbcEntityType<SnapshotItem> type = meta.entityType(SnapshotItem.class);
        SnapshotItem item = new SnapshotItem(1L, "apple", 10, 10, null, null);

        SqlStatement insert = WriteSql.insert(type, item, dialect);

        assertThat(insert.sql())
                .isEqualTo("insert into item (id, name, score, score2, note, status) values (?, ?, ?, ?, ?, ?)");
        assertThat(insert.sql()).doesNotContain("\"");
    }

    /// 引用一切标识符（双引号、不改大小写）的自定义方言。
    private static final class DoubleQuoteDialect implements JdbcDialect {

        @Override
        public String name() {
            return "double-quoted";
        }

        @Override
        public String quote(String identifier) {
            return "\"" + identifier + "\"";
        }
    }

    /// 保留字表名 / 列名的实体：注解里用反引号标记。
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

        QuotedOrder(String desc, Integer key) {
            this.desc = desc;
            this.key = key;
        }
    }

    @Test
    void quotedIdentifierAndVersionColumnsAreQuotedOnUpdate() {
        JdbcEntityType<QuotedVersioned> type = meta.entityType(QuotedVersioned.class);
        QuotedVersioned entity = new QuotedVersioned(5L, "value", 2L);

        SqlStatement statement = WriteSql.update(type, entity, dialect);

        assertThat(statement.sql())
                .isEqualTo("update versioned_quoted set name=?, \"version\"=? where \"id\"=? and \"version\"=?");
        assertThat(statement.parameters()).containsExactly("value", 3L, 5L, 2L);
    }

    @Test
    void mixedQuotedIdentifierWithoutQuotedVersionStaysMixedOnUpdate() {
        JdbcEntityType<MixedQuotedVersion> type = meta.entityType(MixedQuotedVersion.class);
        MixedQuotedVersion entity = new MixedQuotedVersion(7L, "value", 4L);

        SqlStatement statement = WriteSql.update(type, entity, dialect);

        assertThat(statement.sql())
                .isEqualTo("update mixed_quoted_version set name=?, version=? where \"id\"=? and version=?");
        assertThat(statement.parameters()).containsExactly("value", 5L, 7L, 4L);
    }

    @Entity
    @Table(name = "versioned_quoted")
    static class QuotedVersioned {

        @Id
        @Column(name = "`id`")
        Long id;

        String name;

        @Version
        @Column(name = "`version`")
        long version;

        protected QuotedVersioned() {}

        QuotedVersioned(Long id, String name, long version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }
    }

    @Entity
    @Table(name = "mixed_quoted_version")
    static class MixedQuotedVersion {

        @Id
        @Column(name = "`id`")
        Long id;

        String name;

        @Version
        Long version;

        protected MixedQuotedVersion() {}

        MixedQuotedVersion(Long id, String name, Long version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }
    }
}
