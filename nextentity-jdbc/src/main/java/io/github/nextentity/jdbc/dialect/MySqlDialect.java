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

    /// MySQL 的 `length` 返回字节数，字符数要走 `char_length`（与 H2/PostgreSQL 的 `length`、JPA 的
    /// `character_length` 对齐）。
    @Override
    public String lengthOf(String operand) {
        return "char_length(" + operand + ")";
    }
}
