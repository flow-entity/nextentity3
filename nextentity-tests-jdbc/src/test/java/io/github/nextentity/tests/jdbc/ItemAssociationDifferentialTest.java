package io.github.nextentity.tests.jdbc;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationDifferentialTest;
import io.github.nextentity.testing.NullOrdering;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 把关联嵌套路径的**差分**用例接到 JDBC（H2 `MODE=MySQL`）后端上：只多给一条主键路径。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemAssociationDifferentialTest extends AssociationDifferentialTest<AssetEntity> {

    /// H2 `MODE=MySQL` 与 MySQL 的 `ASC` 都把 `null` 排最前，差分 oracle 从这一处取方言事实。
    ItemAssociationDifferentialTest() {
        super(NullOrdering.NULLS_FIRST);
    }

    private final JdbcAssetSupport support = new JdbcAssetSupport("item_association_differential");

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
