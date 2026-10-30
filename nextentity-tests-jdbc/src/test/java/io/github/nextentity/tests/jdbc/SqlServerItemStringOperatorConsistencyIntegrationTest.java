package io.github.nextentity.tests.jdbc;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.StringOperatorConsistencyTest;
import io.github.nextentity.tests.jdbc.support.L4Gate;
import io.github.nextentity.tests.jdbc.support.SqlServerItemSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 与 `ItemStringOperatorConsistencyTest` 一一对应，连接换成容器里的真 SQL Server。
/// 重点验证两处方言差异：`substring(x, pos)` 省略长度必须走 SQL Server 的三参形态
/// （渲染成 `substring(x, pos, 2147483647)`），`length(x)` 走 `len(x)`——后者会裁掉尾随空格，
/// 与 H2/MySQL/PG 的 `LENGTH` 不等价（见 `SqlServerDialect#lengthOf` 与 USAGE §13.4）。
@ExtendWith(L4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlServerItemStringOperatorConsistencyIntegrationTest extends StringOperatorConsistencyTest<ItemEntity> {

    private final SqlServerItemSupport support = new SqlServerItemSupport("sqlserverstringoperator");

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
