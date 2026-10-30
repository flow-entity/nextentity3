package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationWriteConsistencyTest;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 写关联（CUT-17）的 JPA 接线：H2 内存库 + Hibernate，走真实的单实体 `insert`/`update` 路径。
///
/// 空关联语义 = `CLEARS_FOREIGN_KEY`：JPA 用实体值赋值，关联为 `null` 就写 `NULL`
/// （USAGE §13.4.4 披露的差异，容器通道 `MySqlAssetWriteAssociationIntegrationTest`
/// 与 `PostgresAssetWriteAssociationIntegrationTest` 有同口径的整句 SQL 证据）。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetAssociationWriteConsistencyTest extends AssociationWriteConsistencyTest<AssetEntity> {

    private final JpaAssetSupport support = new JpaAssetSupport("asset_association_write");

    private final Fixture<AssetEntity> fixture = new JpaFixture(support);

    @Override
    protected Fixture<AssetEntity> fixture() {
        return fixture;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    /// 把真实写入路径包成夹具：`insert`/`update` 必须在事务里跑（Hibernate 的写入要事务）。
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
        public StringPath<AssetEntity> ownerNamePath() {
            return support.ownerNamePath();
        }

        @Override
        public void insert(long assetId, String name, Long ownerId) {
            support.inTransaction(() -> {
                support.operations().insert(new AssetEntity(assetId, name, owner(ownerId)));
                return null;
            });
        }

        @Override
        public void update(long assetId, String name, Long ownerId) {
            support.inTransaction(() -> {
                support.operations().update(new AssetEntity(assetId, name, owner(ownerId)));
                return null;
            });
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

        @Override
        public NullAssociationBehavior nullAssociationBehavior() {
            return NullAssociationBehavior.CLEARS_FOREIGN_KEY;
        }

        /// 只带主键的关联对象：写入路径只看 `@Id`，不会级联持久化。
        private static OwnerEntity owner(Long ownerId) {
            return ownerId == null ? null : new OwnerEntity(ownerId, "owner-" + ownerId);
        }
    }
}
