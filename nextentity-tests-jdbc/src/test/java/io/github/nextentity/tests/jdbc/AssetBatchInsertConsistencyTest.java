package io.github.nextentity.tests.jdbc;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationBatchInsertConsistencyTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;

/// 批量插入混批（关联为 `null` 的行与带外键的行同批）的 JDBC 接线：H2 内存库。
///
/// 走真实的 `insertAll` 路径。JDBC 的插入执行器按 SQL 文本分组、每组各自准备 `PreparedStatement`
/// （修复前的实现是整批共用一条按首条语句准备的语句，所以混批是它最容易出错的形态）；
/// 证据必须来自原始列读回，而不是只看 `insertAll` 不抛异常。
class AssetBatchInsertConsistencyTest extends AssociationBatchInsertConsistencyTest<AssetEntity> {

    private final JdbcAssetSupport support = new JdbcAssetSupport("assetbatchinsertconsistency");

    private final Fixture<AssetEntity> fixture = new JdbcFixture(support);

    @Override
    protected Fixture<AssetEntity> fixture() {
        return fixture;
    }

    @AfterEach
    void closeSupport() {
        support.close();
    }

    /// 把真实的批量写路径包成夹具：JDBC 后端建立在自动提交之上，整批一次调用。
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
        public void insertAll(List<AssetFixture> rows) {
            support.operations()
                    .insertAll(rows.stream()
                            .map(row -> new AssetEntity(row.id(), row.name(), owner(row.ownerName())))
                            .toList());
        }

        @Override
        public Long foreignKeyOf(long assetId) {
            return support.foreignKeyOf(assetId);
        }

        @Override
        public void clearPersistenceContext() {
            // JDBC 没有持久化上下文：每次查询都直读数据库，无需清理。
        }

        /// 只带主键的关联对象：JDBC 写关联路径只看 `@Id`（USAGE §13.4.4）。
        private static OwnerEntity owner(String ownerName) {
            if (ownerName == null) {
                return null;
            }
            if (ALPHA.equals(ownerName)) {
                return new OwnerEntity(ALPHA_OWNER_ID, ALPHA);
            }
            if (BETA.equals(ownerName)) {
                return new OwnerEntity(BETA_OWNER_ID, BETA);
            }
            throw new IllegalArgumentException("夹具里没有这个 owner: " + ownerName);
        }
    }
}
