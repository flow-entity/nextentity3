package io.github.nextentity.tests.jdbc;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationWriteConsistencyTest;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/// 写关联（CUT-17）的 JDBC 接线：H2 内存库，走真实的单实体 `insert`/`update` 路径（自动提交语义）。
///
/// 空关联语义 = `SKIPS_COLUMN`：JDBC 的写入执行器在关联为 `null` 时**跳过该列**，
/// 所以 `update(entity)` 不能把外键置空（USAGE §13.4.4 披露的差异）。
/// 基类的置空用例在这里断言"外键保持旧值、但标量列真的更新了"，
/// 而不是整条跳过——跳过会让这条已披露的差异变成没人测的灰区。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssetAssociationWriteConsistencyTest extends AssociationWriteConsistencyTest<AssetEntity> {

    private final JdbcAssetSupport support = new JdbcAssetSupport("asset_association_write");

    private final Fixture<AssetEntity> fixture = new JdbcFixture(support);

    @Override
    protected Fixture<AssetEntity> fixture() {
        return fixture;
    }

    @AfterAll
    void closeSupport() {
        support.close();
    }

    /// 把真实写入路径包成夹具：JDBC 后端建立在自动提交之上，不需要显式事务。
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
        public StringPath<AssetEntity> ownerNamePath() {
            return support.ownerNamePath();
        }

        @Override
        public void insert(long assetId, String name, Long ownerId) {
            support.operations().insert(new AssetEntity(assetId, name, owner(ownerId)));
        }

        @Override
        public void update(long assetId, String name, Long ownerId) {
            support.operations().update(new AssetEntity(assetId, name, owner(ownerId)));
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

        @Override
        public NullAssociationBehavior nullAssociationBehavior() {
            return NullAssociationBehavior.SKIPS_COLUMN;
        }

        /// 只带主键的关联对象：写入路径只看 `@Id`。
        private static OwnerEntity owner(Long ownerId) {
            return ownerId == null ? null : new OwnerEntity(ownerId, "owner-" + ownerId);
        }
    }
}
