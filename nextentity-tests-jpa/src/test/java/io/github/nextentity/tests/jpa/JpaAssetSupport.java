package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/// 关联嵌套用例的 JPA 接线：建 owner + asset 两张表，按夹具描述落库，并给出两段嵌套路径。
final class JpaAssetSupport implements AutoCloseable {

    private static final String CLEAR_ASSETS = "delete from AssetEntity";
    private static final String CLEAR_OWNERS = "delete from OwnerEntity";

    private final HibernateHarness harness;
    private final EntityOperations<AssetEntity> operations;

    JpaAssetSupport(String databaseName) {
        this.harness = HibernateHarness.start(databaseName, OwnerEntity.class, AssetEntity.class);
        EntityOperationsFactory factory =
                new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory());
        this.operations = factory.operations(AssetEntity.class);
    }

    EntityQuery<AssetEntity> query() {
        return operations.query();
    }

    /// 两段嵌套路径：`AssetEntity::owner` → `OwnerEntity::name`。
    StringPath<AssetEntity> ownerNamePath() {
        return EntityPath.of(AssetEntity::owner).get(OwnerEntity::name);
    }

    void seedData(List<AssetFixture> fixtures) {
        harness.inTransaction(entityManager -> {
            entityManager.createQuery(CLEAR_ASSETS).executeUpdate();
            entityManager.createQuery(CLEAR_OWNERS).executeUpdate();
            Map<String, OwnerEntity> owners = new LinkedHashMap<>();
            long ownerId = 1L;
            for (AssetFixture fixture : fixtures) {
                String ownerName = fixture.ownerName();
                if (ownerName != null && !owners.containsKey(ownerName)) {
                    OwnerEntity owner = new OwnerEntity(ownerId++, ownerName);
                    entityManager.persist(owner);
                    owners.put(ownerName, owner);
                }
            }
            for (AssetFixture fixture : fixtures) {
                entityManager.persist(new AssetEntity(fixture.id(), fixture.name(), owners.get(fixture.ownerName())));
            }
        });
        harness.entityManager().clear();
    }

    /// 执行器本身：写关联 / 条件 update 的语句快照要用到写路径（`update()`）。
    EntityOperations<AssetEntity> operations() {
        return operations;
    }

    /// 在事务里跑一段写入并提交（Hibernate 的批量写与 flush 必须在事务内）。
    <R> R inTransaction(Supplier<R> work) {
        return harness.inTransaction(work);
    }

    /// 取走已抓到的 SQL（关联用例的整句快照用得到）。
    ///
    /// @return 按执行顺序排列的 SQL；取走后账本清空
    List<String> drainSql() {
        return harness.drainSql();
    }

    /// 清空 SQL 账本。
    void clearSql() {
        harness.clearSql();
    }

    @Override
    public void close() {
        harness.close();
    }
}
