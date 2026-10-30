package io.github.nextentity.tests.jpa;

import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.NullOrdering;
import io.github.nextentity.testing.SortDifferentialTest;
import io.github.nextentity.tests.jpa.support.PostgresItemSupport;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把排序家族的差分用例接到真实 PostgreSQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 这一格的意义比聚合那边更大：`null` 的排序位置是**方言语义**（Postgres 与 PostgreSQL 相反），
/// 只有把同一批排序规格在 H2 `MODE=PostgreSQL` 与真实 PostgreSQL 8.0.18 上各跑一遍，
/// 「本基类钉的 `null` 口径在两套装置上一致」才算有证据。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresItemSortDifferentialIntegrationTest extends SortDifferentialTest<ItemEntity> {

    private final PostgresItemSupport support = new PostgresItemSupport();

    @Override
    protected ConsistencySupport<ItemEntity> support() {
        return support;
    }

    /// 这一格连的是**真 PostgreSQL**，NULL 序因此与 `-Dnextentity.h2mode` 无关：
    /// 带 `-Dnextentity.h2mode=postgresql` 跑容器通道时，若沿用属性读出的值，
    /// 排序 oracle 会用 `NULLS_LAST` 去比真 PostgreSQL 的 `NULLS_LAST`，整族假红。
    @Override
    protected NullOrdering nullOrdering() {
        return NullOrdering.NULLS_LAST;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
