package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// `@Id` 上的 `@Column(insertable = false)`（非生成键）：主键列仍必须进 `INSERT` 列集并写入实体携带的 id。
///
/// 与 Hibernate 对齐：标识列上的 `@Column(insertable=false)` 在 Hibernate 里是 no-op，它照样生成
/// `insert into t (name, id) values (?, ?)`。本层若按 `insertable` 把主键列摘掉，库内主键就由库默认值（或
/// 库拒绝）决定，实体持有的 id 与落库行不是同一个——随后 `update(entity)` / `delete` 按实体 id 命中 0 行，
/// 抛的却是 `EntityNotFoundException`，而插入全程不报错。表里给主键一个非零默认值（42）就是为了让这种
/// 「静默落到别的 id」可观测。
///
/// 同族的 `@Version + @Column(insertable=false)` 已在元数据期拒绝（`JdbcEntityRules.VERSION_INSERTABLE_UNSUPPORTED`），
/// 那条规则的立论是「insertable 只约束业务列」，主键同理不受它约束。
class ReadOnlyIdInsertTest {

    private static final String URL = "jdbc:h2:mem:read_only_id;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private final JdbcDataSource dataSource = dataSource();
    private final EntityOperations<ReadOnlyIdRow> operations =
            new JdbcEntityOperationsFactory(dataSource).operations(ReadOnlyIdRow.class);

    @BeforeEach
    void schema() throws SQLException {
        execute("drop table if exists read_only_id");
        execute("create table read_only_id (id bigint default 42 primary key, name varchar(50))");
    }

    @Test
    @DisplayName("声明 insertable=false 的主键：写入行的主键必须仍是实体携带的 id")
    void insertedRowKeepsEntityId() throws SQLException {
        operations.insert(new ReadOnlyIdRow(7L, "x"));

        assertThat(storedId()).isEqualTo(7L);
        assertThat(operations.query().where(Path.of(ReadOnlyIdRow::id)).eq(7L).list())
                .hasSize(1);
    }

    private Long storedId() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select id from read_only_id")) {
            assertThat(rows.next()).isTrue();
            long id = rows.getLong(1);
            assertThat(rows.next()).isFalse();
            return id;
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(URL);
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    @Entity
    @Table(name = "read_only_id")
    static class ReadOnlyIdRow {

        @Id
        @Column(insertable = false)
        Long id;

        String name;

        protected ReadOnlyIdRow() {}

        ReadOnlyIdRow(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        Long id() {
            return id;
        }
    }
}
