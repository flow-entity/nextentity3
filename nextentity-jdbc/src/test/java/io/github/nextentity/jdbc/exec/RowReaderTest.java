package io.github.nextentity.jdbc.exec;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.model.Tuple2;
import io.github.nextentity.core.builder.QueryBuilder;
import io.github.nextentity.jdbc.compile.JdbcCompiler;
import io.github.nextentity.jdbc.compile.JdbcQuery;
import io.github.nextentity.jdbc.dialect.H2Dialect;
import io.github.nextentity.jdbc.meta.JdbcMeta;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// `RowReader` 的行装配（H2）：实体行按标量列序写入、NULL 不进基本类型字段、关联列不读；
/// 值行的单列裸值/多列、枚举投影、聚合（Double/Integer/Long）与关联路径的 join 读取。
class RowReaderTest {

    private final JdbcMeta meta = new JdbcMeta();
    private final DataSource dataSource = new SimpleDataSource("row_reader");
    private final JdbcCompiler compiler = new JdbcCompiler(meta, new H2Dialect());
    private final JdbcExecutor executor = new JdbcExecutor(dataSource, meta, compiler);

    @BeforeEach
    void seed() throws SQLException, IOException {
        applySchema(dataSource);
        execute("create table if not exists versioned_nullable (id bigint primary key, name varchar(255),"
                + " version bigint null)");
        execute("create table if not exists instant_item (id bigint primary key, created_at timestamp)");
        execute("create table if not exists status_owner (id bigint primary key, status int)");
        execute("create table if not exists owns_status_owner (id bigint primary key, owner_id bigint)");
        execute("delete from asset");
        execute("delete from owner");
        execute("delete from item");
        execute("delete from versioned_nullable");
        execute("insert into item (id, name, score, score2, note, status) values"
                + " (1, 'banana', 10, 1, 'n1', 'ACTIVE'),"
                + " (2, 'cherry', 20, 2, null, null),"
                + " (3, 'apple', 30, 3, 'n2', 'DONE')");
        execute("insert into owner (id, name) values (1, 'alpha'), (2, 'beta')");
        execute("insert into asset (id, name, owner_id) values (1, 'anvil', 1), (2, 'bandage', null)");
        execute("insert into versioned_nullable (id, name, version) values (1, 'plain', null)");
        execute("delete from instant_item");
        execute("insert into instant_item (id, created_at) values (1, timestamp '2024-05-01 10:00:00')");
        execute("delete from owns_status_owner");
        execute("delete from status_owner");
        execute("insert into status_owner (id, status) values (1, 2)");
        execute("insert into owns_status_owner (id, owner_id) values (1, 1)");
    }

    private QueryBuilder<SnapshotItem, JdbcQuery> items() {
        return new QueryBuilder<>(meta.entityType(SnapshotItem.class), compiler, executor);
    }

    @Test
    void entityRowsCarryNullColumnsAndEnums() {
        List<SnapshotItem> rows =
                items().orderBy(Path.of(SnapshotItem::id)).asc().list();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).status()).isEqualTo(ItemStatus.ACTIVE);
        assertThat(rows.get(0).note()).isEqualTo("n1");
        assertThat(rows.get(1).note()).isNull();
        assertThat(rows.get(1).status()).isNull();
        assertThat(rows.get(1).score()).isEqualTo(20);
    }

    @Test
    void nullColumnNeverOverwritesPrimitiveField() {
        List<PrimitiveVersioned> rows = new QueryBuilder<>(
                        meta.entityType(PrimitiveVersioned.class), compiler, executor)
                .orderBy(Path.of(PrimitiveVersioned::id))
                .asc()
                .list();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).version()).isZero();
        assertThat(rows.get(0).name()).isEqualTo("plain");
    }

    @Test
    void entityRowsOnlyReadScalarColumns() {
        List<SnapshotAsset> rows = new QueryBuilder<>(meta.entityType(SnapshotAsset.class), compiler, executor)
                .orderBy(Path.of(SnapshotAsset::id))
                .asc()
                .list();

        assertThat(rows).extracting(SnapshotAsset::name).containsExactly("anvil", "bandage");
        assertThat(rows).extracting(SnapshotAsset::owner).containsOnlyNulls();
    }

    @Test
    void singleColumnEnumProjectionYieldsConstants() {
        List<ItemStatus> statuses = items().select(Path.of(SnapshotItem::status))
                .orderBy(Path.of(SnapshotItem::id))
                .asc()
                .list();

        assertThat(statuses).containsExactly(ItemStatus.ACTIVE, null, ItemStatus.DONE);
    }

    @Test
    void multiColumnProjectionMixesEnumAndNulls() {
        List<Tuple2<String, ItemStatus>> rows = items().select(
                        Path.of(SnapshotItem::name), Path.of(SnapshotItem::status))
                .orderBy(Path.of(SnapshotItem::id))
                .asc()
                .list();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get0()).isEqualTo("banana");
        assertThat(rows.get(0).get1()).isEqualTo(ItemStatus.ACTIVE);
        assertThat(rows.get(1).get1()).isNull();
    }

    @Test
    void aggregateProjectionsReadBackWithTheirStaticTypes() {
        assertThat(items().select(Path.of(SnapshotItem::score).avg()).list()).containsExactly(20.0);
        assertThat(items().select(Path.of(SnapshotItem::score).sum()).list()).containsExactly(60);
        assertThat(items().select(Path.of(SnapshotItem::score).max()).list()).containsExactly(30);
        assertThat(items().select(Path.of(SnapshotItem::score).min()).list()).containsExactly(10);
        assertThat(items().select(Path.of(SnapshotItem::id).count()).list()).containsExactly(3L);
    }

    @Test
    void associationPathReadsJoinedColumn() {
        EntityPath<SnapshotAsset, SnapshotOwner> owner = EntityPath.of(SnapshotAsset::owner);

        List<String> names = new QueryBuilder<>(meta.entityType(SnapshotAsset.class), compiler, executor)
                .select(owner.get(SnapshotOwner::name))
                .orderBy(Path.of(SnapshotAsset::id))
                .asc()
                .list();

        assertThat(names).containsExactly("alpha", null);
    }

    @Test
    void joinedEnumColumnIsResolvedThroughTheAssociationTarget() {
        EntityPath<OwnsStatusOwner, StatusOwner> owner = EntityPath.of(OwnsStatusOwner::owner);

        List<ItemStatus> statuses = new QueryBuilder<>(meta.entityType(OwnsStatusOwner.class), compiler, executor)
                .select(owner.get(StatusOwner::status))
                .list();

        assertThat(statuses).containsExactly(ItemStatus.DONE);
    }

    @Test
    void timestampColumnIsReadAsInstant() {
        List<InstantItem> rows = new QueryBuilder<>(meta.entityType(InstantItem.class), compiler, executor).list();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).createdAt())
                .isEqualTo(Timestamp.valueOf("2024-05-01 10:00:00").toInstant());
    }

    @Test
    void associationPathCountSkipsMissingOwners() {
        EntityPath<SnapshotAsset, SnapshotOwner> owner = EntityPath.of(SnapshotAsset::owner);

        assertThat(new QueryBuilder<>(meta.entityType(SnapshotAsset.class), compiler, executor)
                        .select(owner.get(SnapshotOwner::name).count())
                        .list())
                .containsExactly(1L);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void applySchema(DataSource dataSource) throws SQLException, IOException {
        String schema;
        try (InputStream stream = RowReaderTest.class.getResourceAsStream("/schema.sql")) {
            schema = new String(stream.readAllBytes(), UTF_8);
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String statementSql : schema.split(";")) {
                if (!statementSql.isBlank()) {
                    statement.execute(statementSql);
                }
            }
        }
    }

    @Entity
    @Table(name = "status_owner")
    static class StatusOwner {

        @Id
        private Long id;

        private ItemStatus status;

        protected StatusOwner() {}

        ItemStatus status() {
            return status;
        }
    }

    @Entity
    @Table(name = "owns_status_owner")
    static class OwnsStatusOwner {

        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "owner_id")
        private StatusOwner owner;

        protected OwnsStatusOwner() {}

        StatusOwner owner() {
            return owner;
        }
    }

    @Entity
    @Table(name = "instant_item")
    static class InstantItem {

        @Id
        private Long id;

        @Column(name = "created_at")
        private Instant createdAt;

        protected InstantItem() {}

        Instant createdAt() {
            return createdAt;
        }
    }

    @Entity
    @Table(name = "versioned_nullable")
    static class PrimitiveVersioned {

        @Id
        private Long id;

        private String name;

        @Version
        private long version;

        protected PrimitiveVersioned() {}

        Long id() {
            return id;
        }

        String name() {
            return name;
        }

        long version() {
            return version;
        }
    }

    /// 极简 DataSource：每次操作新连接。
    private static final class SimpleDataSource implements DataSource {

        private final String url;

        SimpleDataSource(String database) {
            this.url = "jdbc:h2:mem:" + database + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, "sa", "");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
