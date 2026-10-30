package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationDifferentialTest;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import io.github.nextentity.tests.jpa.support.HibernateHarness.H2Mode;
import io.github.nextentity.tests.jpa.support.PostgresAssetSupport;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把关联嵌套路径的差分用例接到真实 PostgreSQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 这一族最值得上真库：`null` 关联行的谓词与排序位置都是**方言语义**，H2 的 PostgreSQL 兼容模式与真 PostgreSQL
/// 之间最容易分叉，而本族的断言全是结果集（不比 SQL 文本），只有同一批格子在两套装置上各跑一遍，
/// 「基类钉的 `null` 口径成立」才算有证据。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresItemAssociationDifferentialIntegrationTest extends AssociationDifferentialTest<AssetEntity> {

    /// 方言事实从 `HibernateHarness` 的一处声明传进来，基类不内建某一方言的 NULL 序。
    PostgresItemAssociationDifferentialIntegrationTest() {
        // 钉 POSTGRES：容器通道连的是真 PostgreSQL，与 `-Dnextentity.h2mode`（H2 兼容模式开关）无关。
        super(HibernateHarness.nullOrdering(H2Mode.POSTGRESQL));
    }

    private final PostgresAssetSupport support = new PostgresAssetSupport();

    @Override
    protected EntityQuery<AssetEntity> query() {
        return support.query();
    }

    @Override
    protected void persistFixtures(List<AssetFixture> fixtures) {
        support.seedData(fixtures);
    }

    @Override
    protected StringPath<AssetEntity> ownerNamePath() {
        return support.ownerNamePath();
    }

    @Override
    protected PathRef<AssetEntity, Long> idRef() {
        return AssetEntity::id;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
