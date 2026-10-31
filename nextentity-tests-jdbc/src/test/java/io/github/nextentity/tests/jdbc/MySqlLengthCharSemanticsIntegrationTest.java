package io.github.nextentity.tests.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.tests.jdbc.phasec.MySqlLengthItemEntity;
import io.github.nextentity.tests.jdbc.support.MySqlL4Container;
import io.github.nextentity.tests.jdbc.support.MySqlL4Gate;
import io.github.nextentity.tests.jdbc.support.SingleConnectionDataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// Phase C 语义证据（MySQL 容器通道）：`length()` 必须按**字符**计数，不是字节。
///
/// [io.github.nextentity.jdbc.dialect.MySqlDialect] 若直接下发 `length(x)`，utf8mb4 下
/// `length('香蕉')` = 6 字节、`char_length('香蕉')` = 2 字符 —— 同一谓词 `.length().eq(2)`
/// 在 H2/PG 命中、在 MySQL 不命中，跨后端结果不一致。
///
/// 两条断言成对：只钉「2 命中」的话，把渲染改成 `length(x) >= 2` 之类的错也会绿；
/// 「6 不命中」把字节口径钉死。
@ExtendWith(MySqlL4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlLengthCharSemanticsIntegrationTest {

    private static final String CREATE_TABLE =
            "create table if not exists mysql_length_item (id bigint primary key, name varchar(32))";
    private static final PathRef.StringRef<MySqlLengthItemEntity> NAME = MySqlLengthItemEntity::name;

    private final SingleConnectionDataSource dataSource =
            SingleConnectionDataSource.of(MySqlL4Container.newConnection());
    private final EntityOperations<MySqlLengthItemEntity> items =
            new JdbcEntityOperationsFactory(dataSource).operations(MySqlLengthItemEntity.class);

    MySqlLengthCharSemanticsIntegrationTest() {
        execute(CREATE_TABLE);
        execute("delete from mysql_length_item");
        execute("insert into mysql_length_item (id, name) values (1, '香蕉'), (2, 'abcd')");
    }

    /// `'香蕉'` 是 2 个字符：`char_length` 口径下命中。
    @Test
    void lengthCountsCharacters() {
        List<MySqlLengthItemEntity> hits =
                items.query().where(NAME).length().eq(2).list();

        assertThat(hits).extracting(MySqlLengthItemEntity::name).containsExactly("香蕉");
    }

    /// `'香蕉'` 是 6 个 utf8mb4 字节：字节口径（MySQL `length`）会在这里命中，正确实现必须查不到。
    @Test
    void lengthDoesNotCountBytes() {
        List<MySqlLengthItemEntity> byteHits =
                items.query().where(NAME).length().eq(6).list();

        assertThat(byteHits).isEmpty();
    }

    @AfterAll
    void closeDataSource() {
        dataSource.close();
    }

    private void execute(String sql) {
        try (Statement statement = dataSource.connection().createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("执行 DDL/DML 失败: " + sql, e);
        }
    }
}
