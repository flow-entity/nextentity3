package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationDifferentialTest;
import io.github.nextentity.tests.jpa.support.MySqlAssetSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把关联嵌套路径的差分用例接到真实 MySQL 上：L4 的载体，用例本体仍是 `nextentity-testing` 的基类。
///
/// 这一族最值得上真库：`null` 关联行的谓词与排序位置都是**方言语义**，H2 的 MySQL 兼容模式与真 MySQL
/// 之间最容易分叉，而本族的断言全是结果集（不比 SQL 文本），只有同一批格子在两套装置上各跑一遍，
/// 「基类钉的 `null` 口径成立」才算有证据。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemAssociationDifferentialIntegrationTest extends AssociationDifferentialTest<AssetEntity> {

    private final MySqlAssetSupport support = new MySqlAssetSupport();

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
