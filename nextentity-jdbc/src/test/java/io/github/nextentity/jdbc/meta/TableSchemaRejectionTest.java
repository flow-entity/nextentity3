package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `@Table(schema = …)` / `@Table(catalog = …)` 必须在元数据期显式拒绝。
///
/// 本后端的表名只取 `@Table(name)`，schema / catalog 从不进 SQL。静默丢弃的后果不是「少写一个前缀」：
/// 库里有默认 schema 的同名表时，`insert` / `select` 会打到那张表上（Hibernate 会渲染
/// `insert into audit.probe (…)`，本后端渲染 `insert into probe (…)`），写入落到别处且不报错。
/// 未实现的映射要么支持、要么 fail-fast——本后端没有 schema 支持，所以按后者处理，
/// 与 `@Inheritance` / `@IdClass` / `@Access(PROPERTY)` 同一口径。
class TableSchemaRejectionTest {

    @Test
    @DisplayName("@Table(schema = …) 拒绝，不静默丢弃")
    void schemaQualifiedTableIsRejected() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(SchemaQualified.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("@Table(schema/catalog) 未支持")
                .hasMessageContaining(SchemaQualified.class.getName());
    }

    @Test
    @DisplayName("@Table(catalog = …) 拒绝，不静默丢弃")
    void catalogQualifiedTableIsRejected() {
        assertThatThrownBy(() -> new JdbcMeta().entityType(CatalogQualified.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("@Table(schema/catalog) 未支持")
                .hasMessageContaining(CatalogQualified.class.getName());
    }

    @Test
    @DisplayName("对照：只给 @Table(name) 的实体照常解析")
    void plainTableNameIsStillAccepted() {
        assertThat(new JdbcMeta().entityType(PlainTable.class).table()).isEqualTo("plain_table");
    }

    @Entity
    @Table(name = "schema_qualified", schema = "audit")
    public static class SchemaQualified {

        @Id
        private Long id;
    }

    @Entity
    @Table(name = "catalog_qualified", catalog = "analytics")
    public static class CatalogQualified {

        @Id
        private Long id;
    }

    @Entity
    @Table(name = "plain_table")
    public static class PlainTable {

        @Id
        private Long id;
    }
}
