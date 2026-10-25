package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationDifferentialTest;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import java.util.List;
import org.junit.jupiter.api.AfterEach;

/// 把关联嵌套路径的**差分**用例接到 JPA 后端（H2 `MODE=MySQL`）上：用例本体在 `nextentity-testing`。
///
/// 接线与 `ItemAssociationConsistencyTest` 同形，只多给一条主键路径（分页/排序 tiebreaker 用）。
/// H2 用独立的库名，避免与一致性用例共用同一个内存库而互相看到对方的夹具。
class ItemAssociationDifferentialTest extends AssociationDifferentialTest<AssetEntity> {

    /// 方言事实从 `HibernateHarness` 的一处声明传进来，基类不内建某一方言的 NULL 序。
    ItemAssociationDifferentialTest() {
        super(HibernateHarness.nullOrdering());
    }

    private final JpaAssetSupport support = new JpaAssetSupport("item_association_differential");

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

    @AfterEach
    void closeSupport() {
        support.close();
    }
}
