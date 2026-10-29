package io.github.nextentity.tests.jdbc.support;

import io.github.nextentity.tests.jdbc.JdbcReservedWordSupport;

/// SQL Server 通道的保留字接线：每类独立 database，建引用表用方括号 DDL。
public final class SqlServerReservedWordSupport extends JdbcReservedWordSupport {

    public SqlServerReservedWordSupport(String database) {
        super(SqlServerL4Container.dataSource(database), JdbcSchema.SQLSERVER, "[order]");
    }
}
