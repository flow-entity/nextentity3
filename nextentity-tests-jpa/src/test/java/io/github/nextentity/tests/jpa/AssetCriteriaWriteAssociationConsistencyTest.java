package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.Path;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.api.UpdateSetStep;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.CriteriaWriteAssociationConsistencyTest;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;

/// 条件写「SET 目标是关联列」的 JPA 接线：H2 内存库 + Hibernate，走真实的 `update()` 条件写路径。
///
/// JPA 的批量写必须在事务里执行，所以装配 step 与执行分开、执行时包一层事务（JDBC 侧没有这个约束）。
class AssetCriteriaWriteAssociationConsistencyTest extends CriteriaWriteAssociationConsistencyTest<AssetEntity> {

    private final JpaAssetSupport support = new JpaAssetSupport("asset_criteria_write_association");

    private final Fixture<AssetEntity> fixture = new JpaFixture(support);

    @Override
    protected Fixture<AssetEntity> fixture() {
        return fixture;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 把真实的条件写路径包成夹具：`execute()` 在事务里跑。
    private static final class JpaFixture implements Fixture<AssetEntity> {

        private final JpaAssetSupport support;

        JpaFixture(JpaAssetSupport support) {
            this.support = support;
        }

        @Override
        public void seedData(List<AssetFixture> fixtures) {
            support.seedData(fixtures);
        }

        @Override
        public EntityQuery<AssetEntity> query() {
            return support.query();
        }

        @Override
        public PathRef<AssetEntity, Long> idRef() {
            return AssetEntity::id;
        }

        @Override
        public Path<AssetEntity, Long> associationIdPath() {
            return EntityPath.of(AssetEntity::owner).get(OwnerEntity::id);
        }

        @Override
        public StringPath<AssetEntity> associatedNamePath() {
            return support.ownerNamePath();
        }

        @Override
        public int runUpdate(Consumer<UpdateSetStep<AssetEntity>> work) {
            UpdateSetStep<AssetEntity> step = support.operations().update();
            work.accept(step);
            try {
                return support.inTransaction(step::execute);
            } catch (UnsupportedOperationException e) {
                // 拒绝路径：HibernateHarness 的事务体抛异常不会自动回滚，先收尾再原样抛出，
                // 否则后续 foreignKeyOf/nameOf 的读回会撞 "transaction already active"。
                support.rollbackActiveTransaction();
                throw e;
            }
        }

        @Override
        public Long foreignKeyOf(long assetId) {
            return support.foreignKeyOf(assetId);
        }

        @Override
        public String nameOf(long assetId) {
            return support.nameOf(assetId);
        }

        @Override
        public void clearPersistenceContext() {
            support.clearPersistenceContext();
        }
    }
}
