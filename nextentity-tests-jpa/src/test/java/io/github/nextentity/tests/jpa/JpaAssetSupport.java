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
        // 上一个用例可能留下同 id 的托管实体（读取过 asset），先清上下文再按固定主键重写
        harness.entityManager().clear();
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

    /// 原始列读回：`select owner_id from asset where id = ?`。
    ///
    /// 走 native query 而不是实体查询：关联是懒加载的，读实体不保证拿到"库里的外键值"，
    /// 而写关联的证据恰恰需要这个原始值。
    ///
    /// @param assetId 主键
    /// @return 外键值；列为 `NULL` 时返回 `null`
    Long foreignKeyOf(long assetId) {
        // 放在事务里跑：Hibernate 的 session 只有在事务边界内才稳定地借/还连接
        Object value = harness.inTransaction(() -> harness.entityManager()
                .createNativeQuery("select owner_id from asset where id = ?")
                .setParameter(1, assetId)
                .getSingleResult());
        return value == null ? null : ((Number) value).longValue();
    }

    /// 原始列读回：`select name from asset where id = ?`。
    ///
    /// @param assetId 主键
    /// @return 名称
    String nameOf(long assetId) {
        return harness.inTransaction(() -> (String) harness.entityManager()
                .createNativeQuery("select name from asset where id = ?")
                .setParameter(1, assetId)
                .getSingleResult());
    }

    /// 丢弃一级缓存/持久化上下文：让后续读回真的来自数据库，而不是刚才写入的托管对象。
    void clearPersistenceContext() {
        harness.entityManager().clear();
    }

    @Override
    public void close() {
        harness.close();
    }
}
