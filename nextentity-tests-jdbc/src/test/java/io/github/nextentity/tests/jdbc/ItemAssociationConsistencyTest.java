package io.github.nextentity.tests.jdbc;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationConsistencyTest;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 把关联嵌套路径的一致性用例接到 JDBC（H2 `MODE=MySQL`）后端上：用例本体在 `nextentity-testing`。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemAssociationConsistencyTest extends AssociationConsistencyTest<AssetEntity> {

    private final JdbcAssetSupport support = new JdbcAssetSupport("item_association_consistency");

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

    @AfterAll
    void closeSupport() {
        support.close();
    }
}
