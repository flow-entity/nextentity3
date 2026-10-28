package io.github.nextentity.tests.jdbc.support;

import io.github.nextentity.tests.jdbc.JdbcReservedWordSupport;

/// PostgreSQL 通道的保留字接线：每类独立 schema，建引用表用双引号 DDL。
public final class PostgresReservedWordSupport extends JdbcReservedWordSupport {

    public PostgresReservedWordSupport(String schema) {
        super(PostgresL4Container.dataSource(schema), JdbcSchema.POSTGRES, "\"order\"");
    }
}
