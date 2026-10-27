package io.github.nextentity.tests.jdbc.support;

import io.github.nextentity.tests.jdbc.JdbcItemSupport;

/// 真 PostgreSQL 通道的 Item 接线：每类一个独立 schema，其余与 H2 完全同形。
public final class PostgresItemSupport extends JdbcItemSupport {

    public PostgresItemSupport(String schema) {
        super(PostgresL4Container.dataSource(schema), JdbcSchema.POSTGRES);
    }
}
