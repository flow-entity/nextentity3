package io.github.nextentity.tests.jdbc;

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

/// 条件写「SET 目标是关联列」的 JDBC 接线：H2 内存库，走真实的 `update()` 条件写路径（自动提交语义）。
///
/// 外键列走嵌套路径 `EntityPath.of(AssetEntity::owner).get(OwnerEntity::id)`，原始列读回走原生 SQL：
/// JDBC 读回不填充关联对象（USAGE §13.4.4），证据要的是库里的 `owner_id` 原值。
class AssetCriteriaWriteAssociationConsistencyTest extends CriteriaWriteAssociationConsistencyTest<AssetEntity> {

    private final JdbcAssetSupport support = new JdbcAssetSupport("assetcriteriawriteassociation");

    private final Fixture<AssetEntity> fixture = new JdbcFixture(support);

    @Override
    protected Fixture<AssetEntity> fixture() {
        return fixture;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 把真实的条件写路径包成夹具：JDBC 后端建立在自动提交之上，`execute()` 直接跑。
    private static final class JdbcFixture implements Fixture<AssetEntity> {

        private final JdbcAssetSupport support;

        JdbcFixture(JdbcAssetSupport support) {
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
            return step.execute();
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
            // JDBC 没有持久化上下文：每次查询都直读数据库，无需清理。
        }
    }
}
