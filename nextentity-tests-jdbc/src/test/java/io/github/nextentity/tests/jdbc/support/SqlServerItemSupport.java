package io.github.nextentity.tests.jdbc.support;

import io.github.nextentity.tests.jdbc.JdbcItemSupport;

/// 真 SQL Server 通道的 Item 接线：每类一个独立 database，其余与 H2 完全同形。
public final class SqlServerItemSupport extends JdbcItemSupport {

    public SqlServerItemSupport(String database) {
        super(SqlServerL4Container.dataSource(database), JdbcSchema.SQLSERVER);
    }
}
