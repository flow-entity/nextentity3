package io.github.nextentity.tests.jdbc;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationDifferentialTest;
import io.github.nextentity.testing.NullOrdering;
import io.github.nextentity.tests.jdbc.support.L4Gate;
import io.github.nextentity.tests.jdbc.support.PostgresAssetSupport;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 与 `ItemAssociationDifferentialTest` 一一对应，只把连接换成容器里的真 PostgreSQL。
@ExtendWith(L4Gate.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresItemAssociationDifferentialIntegrationTest extends AssociationDifferentialTest<AssetEntity> {

    PostgresItemAssociationDifferentialIntegrationTest() {
        super(NullOrdering.NULLS_LAST);
    }

    private final PostgresAssetSupport support =
            new PostgresAssetSupport("postgresitemassociationdifferentialintegrationtest");

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
