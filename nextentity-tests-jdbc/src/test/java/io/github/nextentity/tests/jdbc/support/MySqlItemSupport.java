package io.github.nextentity.tests.jdbc.support;

import io.github.nextentity.tests.jdbc.JdbcItemSupport;

/// 真 MySQL 通道的 Item 接线：只换连接（容器共享库），其余与 H2 完全同形。
public final class MySqlItemSupport extends JdbcItemSupport {

    public MySqlItemSupport() {
        super(SingleConnectionDataSource.of(MySqlL4Container.newConnection()));
    }
}
