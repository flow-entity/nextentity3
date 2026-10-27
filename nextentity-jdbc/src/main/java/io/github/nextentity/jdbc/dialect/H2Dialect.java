package io.github.nextentity.jdbc.dialect;

/// H2（MODE=MySQL）方言。
public final class H2Dialect implements JdbcDialect {

    @Override
    public String name() {
        return "h2";
    }
}
