package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationConsistencyTest;
import io.github.nextentity.tests.jpa.support.MySqlAssetSupport;
import io.github.nextentity.tests.jpa.support.MySqlL4Gate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/// 把关联嵌套路径的一致性用例接到真实 MySQL 上：左连接与「关联为空」的语义在真方言上重跑一遍。
///
/// 这一族最值得上真库：嵌套路径会被编译成 join，`isNull` 命中「没有关联」的行、`eq` 不命中，
/// 这类行为在 H2 的 MySQL 兼容模式与真 MySQL 之间最容易分叉，而 H2 侧的断言全是结果集，不比 SQL 文本。
@ExtendWith(MySqlL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlAssetAssociationIntegrationTest extends AssociationConsistencyTest<AssetEntity> {

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
