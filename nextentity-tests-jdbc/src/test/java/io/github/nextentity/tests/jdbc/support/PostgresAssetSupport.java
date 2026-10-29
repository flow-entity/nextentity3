package io.github.nextentity.tests.jdbc.support;

import io.github.nextentity.tests.jdbc.JdbcAssetSupport;

/// 真 PostgreSQL 通道的关联接线：每类一个独立 schema，其余与 H2 完全同形。
public final class PostgresAssetSupport extends JdbcAssetSupport {

    public PostgresAssetSupport(String schema) {
        super(PostgresL4Container.dataSource(schema), JdbcSchema.POSTGRES);
    }
}
