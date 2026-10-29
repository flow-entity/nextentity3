package io.github.nextentity.tests.jdbc.support;

import io.github.nextentity.tests.jdbc.JdbcAssetSupport;

/// 真 MySQL 通道的关联接线：只换连接（容器共享库），其余与 H2 完全同形。
public final class MySqlAssetSupport extends JdbcAssetSupport {

    public MySqlAssetSupport() {
        super(SingleConnectionDataSource.of(MySqlL4Container.newConnection()));
    }
}
