package io.github.nextentity.jdbc.dialect;

/// MySQL 8 方言。
public final class MySqlDialect implements JdbcDialect {

    @Override
    public String name() {
        return "mysql";
    }

    /// MySQL 反引号，内部反引号双写转义。
    @Override
    public String quote(String identifier) {
        return '`' + identifier.replace("`", "``") + '`';
    }
}
