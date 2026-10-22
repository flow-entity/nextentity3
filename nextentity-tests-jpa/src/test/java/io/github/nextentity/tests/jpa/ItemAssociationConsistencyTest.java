package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationConsistencyTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;

/// 把关联嵌套路径的一致性用例接到 JPA 后端上（用例本体在 nextentity-testing 模块）。
class ItemAssociationConsistencyTest extends AssociationConsistencyTest<AssetEntity> {

    private final JpaAssetSupport support = new JpaAssetSupport("item_association");

    @Override
    protected EntityQuery<AssetEntity> query() {
        return support.query();
    }

    @Override
    protected void seedData(List<AssetFixture> fixtures) {
        support.seedData(fixtures);
    }

    @Override
    protected StringPath<AssetEntity> ownerNamePath() {
        return support.ownerNamePath();
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
