package io.github.nextentity.jdbc.exec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.jdbc.dialect.JdbcDialect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// 标识符引用端到端：注解用反引号标记的保留字表 / 列，经工厂 + 自定义「双引号」方言在 H2 上
/// 完成 insert / insertAll / update / select / count。
///
/// 约束：H2 里建的是双引号小写标识符，注入的方言 `quote` 也产双引号 —— 与内置 H2 方言的引用字符
/// （Lead 裁决：双引号）一致，但本用例不依赖内置方言实现，直接用自定义方言把链路走通。
class QuotedIdentifierE2ETest {

    private static final String URL = "jdbc:h2:mem:quoted_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private final JdbcDataSource dataSource = dataSource();
    private final JdbcEntityOperationsFactory factory =
            new JdbcEntityOperationsFactory(dataSource, new DoubleQuoteDialect());
    private final EntityOperations<QuotedOrder> operations = factory.operations(QuotedOrder.class);

    @BeforeEach
    void schema() throws SQLException {
        execute("drop table if exists \"order\"");
        execute("create table \"order\" (\"id\" bigint auto_increment primary key,"
                + " \"desc\" varchar(64), \"key\" int)");
    }

    @Test
    void insertAssignsIdentifierAndSelectReadsQuotedColumns() {
        QuotedOrder first = new QuotedOrder("first", 10);
        QuotedOrder second = new QuotedOrder("second", 20);

        operations.insert(first);
        operations.insertAll(List.of(second));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).isNotNull();
        assertThat(first.id()).isNotEqualTo(second.id());

        List<QuotedOrder> rows =
                operations.query().orderBy(Path.of(QuotedOrder::key)).asc().list();
        assertThat(rows).extracting(QuotedOrder::desc).containsExactly("first", "second");
        assertThat(rows).extracting(QuotedOrder::key).containsExactly(10, 20);
        assertThat(operations.query().count()).isEqualTo(2);
        assertThat(operations.query().where(Path.of(QuotedOrder::key)).eq(20).exists())
                .isTrue();
    }

    @Test
    void updateUsesQuotedColumns() {
        QuotedOrder order = new QuotedOrder("before", 30);
        operations.insert(order);

        order.desc("after");
        operations.update(order);

        List<QuotedOrder> rows = operations.query().list();
        assertThat(rows).extracting(QuotedOrder::desc).containsExactly("after");
        assertThat(rows).extracting(QuotedOrder::key).containsExactly(30);
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
        return dataSource;
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

    /// 保留字表名 / 列名的实体：注解里用反引号标记，列名全小写（H2 引号下大小写敏感）。
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

        Long id() {
            return id;
        }

        String desc() {
            return desc;
        }

        void desc(String desc) {
            this.desc = desc;
        }

        Integer key() {
            return key;
        }
    }
}
