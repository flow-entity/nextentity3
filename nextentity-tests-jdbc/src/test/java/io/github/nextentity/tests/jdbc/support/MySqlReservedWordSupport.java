package io.github.nextentity.tests.jdbc.support;

import io.github.nextentity.tests.jdbc.JdbcReservedWordSupport;

/// 真 MySQL 通道的保留字接线：只换连接（容器共享库，建引用表用反引号 DDL）。
public final class MySqlReservedWordSupport extends JdbcReservedWordSupport {

    public MySqlReservedWordSupport() {
        super(SingleConnectionDataSource.of(MySqlL4Container.newConnection()), JdbcSchema.MYSQL, "`order`");
    }
}
