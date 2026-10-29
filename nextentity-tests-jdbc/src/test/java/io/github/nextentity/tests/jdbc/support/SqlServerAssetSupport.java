package io.github.nextentity.tests.jdbc.support;

import io.github.nextentity.tests.jdbc.JdbcAssetSupport;

/// 真 SQL Server 通道的关联接线：每类一个独立 database，其余与 H2 完全同形。
public final class SqlServerAssetSupport extends JdbcAssetSupport {

    public SqlServerAssetSupport(String database) {
        super(SqlServerL4Container.dataSource(database), JdbcSchema.SQLSERVER);
    }
}
