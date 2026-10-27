package io.github.nextentity.jdbc.dialect;

/// MySQL 8 方言。
public final class MySqlDialect implements JdbcDialect {

    @Override
    public String name() {
        return "mysql";
    }
}
