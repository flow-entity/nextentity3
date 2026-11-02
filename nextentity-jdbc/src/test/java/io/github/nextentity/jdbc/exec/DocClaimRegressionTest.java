package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.jdbc.support.ItemStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// 钉住 USAGE §13.4.* 里几条「听起来反直觉」的声明，按文档语义断言（文档改了这里要跟着改）：
/// 生成键实体带 id 调用 insert 时忽略入参并回写、`limit = 0` 视为不限行、
/// `update(entity)` 不能把外键置空（关联为 null 时跳过该列）、`@Version` 到类型上界抛 UOE、
/// 缺省 `@Enumerated` 按 ORDINAL 存储。
class DocClaimRegressionTest {

    private static final String URL = "jdbc:h2:mem:doc_claim_probe;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private final JdbcDataSource dataSource = dataSource();

    private final EntityOperations<GeneratedNote> notes =
            new JdbcEntityOperationsFactory(dataSource).operations(GeneratedNote.class);

    private final EntityOperations<RefItem> refItems =
            new JdbcEntityOperationsFactory(dataSource).operations(RefItem.class);

    private final EntityOperations<VersionedItem> versionedItems =
            new JdbcEntityOperationsFactory(dataSource).operations(VersionedItem.class);

    private final EntityOperations<OrdinalItem> ordinalItems =
            new JdbcEntityOperationsFactory(dataSource).operations(OrdinalItem.class);

    @BeforeEach
    void schema() throws SQLException {
        execute("drop table if exists generated_note");
        execute("drop table if exists ref_item");
        execute("drop table if exists ref_owner");
        execute("drop table if exists versioned_item");
        execute("drop table if exists ordinal_item");
        execute("create table generated_note (id bigint auto_increment primary key, text varchar(50))");
        execute("create table ref_owner (id bigint primary key, name varchar(50))");
        execute("create table ref_item (id bigint primary key, name varchar(50), owner_id bigint)");
        execute("create table versioned_item (id bigint primary key, name varchar(50), version int)");
        execute("create table ordinal_item (id bigint primary key, status int)");
        execute("insert into ref_owner values (7, 'owner-7')");
    }

    @Test
    @DisplayName("@Version 到类型上界时 update 抛 UOE，不回绕成负数")
    void versionOverflowRejected() throws SQLException {
        versionedItems.insert(new VersionedItem(1L, "a", 0));

        execute("update versioned_item set version = 2147483647 where id = 1");

        VersionedItem item = versionedItems
                .query()
                .where(Path.of(VersionedItem::getId))
                .eq(1L)
                .single();
        item.setName("b");

        assertThatThrownBy(() -> versionedItems.update(item))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("上界");
        assertThat(rawRowOf("versioned_item", 1L)).containsEntry("name", "a");
    }

    @Test
    @DisplayName("缺省 @Enumerated 按 ORDINAL 存序号并读回枚举")
    void ordinalEnumIsDefault() throws SQLException {
        ordinalItems.insert(new OrdinalItem(1L, ItemStatus.ACTIVE));

        assertThat(rawRowOf("ordinal_item", 1L)).containsEntry("status", 1);
        assertThat(ordinalItems
                        .query()
                        .where(Path.of(OrdinalItem::getId))
                        .eq(1L)
                        .single()
                        .getStatus())
                .isEqualTo(ItemStatus.ACTIVE);
    }

    @Test
    @DisplayName("生成键实体带 id 调 insert：忽略入参、回写库生成值")
    void generatedInsertIgnoresPresetId() throws SQLException {
        GeneratedNote note = new GeneratedNote();
        note.setId(99L);
        note.setText("a");

        notes.insert(note);

        assertThat(note.getId()).as("实体 id 应被回写成库生成值").isEqualTo(1L);
        assertThat(rawRowOf("generated_note", 1L)).containsEntry("text", "a");
        assertThat(count("generated_note")).isEqualTo(1);
    }

    @Test
    @DisplayName("分页 limit = 0 视为不限行（list 与 slice 都按文档语义）")
    void zeroLimitMeansUnlimited() throws SQLException {
        for (long id = 1; id <= 5; id++) {
            refItems.insert(new RefItem(id, "n" + id, null));
        }

        assertThat(refItems.query().list(0, 0)).hasSize(5);
        assertThat(refItems.query().slice(2, 0).data()).hasSize(3);
        assertThat(refItems.query().slice(2, 0).total()).isEqualTo(5);
    }

    @Test
    @DisplayName("update(entity) 无法把外键置空：关联为 null 时跳过该列（文档声明的语义）")
    void nullAssociationIsSkippedOnUpdate() throws SQLException {
        refItems.insert(new RefItem(1L, "a", new RefOwner(7L, "owner-7")));
        assertThat(rawRowOf("ref_item", 1L)).containsEntry("owner_id", 7L);

        RefItem loaded = refItems.query().where(Path.of(RefItem::getId)).eq(1L).single();
        loaded.setName("b");
        loaded.setOwner(null);
        refItems.update(loaded);

        Map<String, Object> row = rawRowOf("ref_item", 1L);
        assertThat(row).containsEntry("name", "b");
        assertThat(row).as("文档声明关联为 null 时跳过该列").containsEntry("owner_id", 7L);
    }

    // ------------------------------------------------------------- helpers

    private static JdbcDataSource dataSource() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(URL);
        source.setUser("sa");
        return source;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private Map<String, Object> rawRowOf(String table, long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("select * from " + table + " where id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as(table + " 里应有 id=" + id).isTrue();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    row.put(rows.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), rows.getObject(i));
                }
                return row;
            }
        }
    }

    // ------------------------------------------------------------- fixtures

    @Entity
    @Table(name = "generated_note")
    public static class GeneratedNote {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "text")
        private String text;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    @Entity
    @Table(name = "ref_owner")
    public static class RefOwner {

        @Id
        private Long id;

        private String name;

        protected RefOwner() {}

        public RefOwner(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "ref_item")
    public static class RefItem {

        @Id
        private Long id;

        private String name;

        @ManyToOne
        @JoinColumn(name = "owner_id")
        private RefOwner owner;

        protected RefItem() {}

        public RefItem(Long id, String name, RefOwner owner) {
            this.id = id;
            this.name = name;
            this.owner = owner;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public RefOwner getOwner() {
            return owner;
        }

        public void setOwner(RefOwner owner) {
            this.owner = owner;
        }
    }

    @Entity
    @Table(name = "versioned_item")
    public static class VersionedItem {

        @Id
        private Long id;

        private String name;

        @Version
        private Integer version;

        protected VersionedItem() {}

        public VersionedItem(Long id, String name, Integer version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getVersion() {
            return version;
        }
    }

    @Entity
    @Table(name = "ordinal_item")
    public static class OrdinalItem {

        @Id
        private Long id;

        private ItemStatus status;

        protected OrdinalItem() {}

        public OrdinalItem(Long id, ItemStatus status) {
            this.id = id;
            this.status = status;
        }

        public Long getId() {
            return id;
        }

        public ItemStatus getStatus() {
            return status;
        }
    }
}
