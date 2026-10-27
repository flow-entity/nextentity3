package io.github.nextentity.tests.jdbc.support;

/// MySQL 容器通道的开启条件：语义与 [L4Gate] 完全一致，保留此名是因为 31 个
/// `MySql*IntegrationTest` 已经按它接线（改名会散成一次无收益的大 diff）。
public final class MySqlL4Gate extends L4Gate {}
