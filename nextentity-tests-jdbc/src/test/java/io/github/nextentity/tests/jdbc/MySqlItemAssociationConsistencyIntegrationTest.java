package io.github.nextentity.tests.jdbc;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationConsistencyTest;
import io.github.nextentity.tests.jdbc.support.MySqlAssetSupport;
import io.github.nextentity.tests.jdbc.support.MySqlL4Gate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 与 `ItemAssociationConsistencyTest` 一一对应，只把连接换成容器里的真 MySQL。
@ExtendWith(MySqlL4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlItemAssociationConsistencyIntegrationTest extends AssociationConsistencyTest<AssetEntity> {

    private final MySqlAssetSupport support = new MySqlAssetSupport();

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
