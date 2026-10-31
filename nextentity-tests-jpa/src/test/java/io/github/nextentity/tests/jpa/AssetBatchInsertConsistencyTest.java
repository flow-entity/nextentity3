package io.github.nextentity.tests.jpa;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.testing.AssociationBatchInsertConsistencyTest;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;

/// 批量插入混批（关联为 `null` 的行与带外键的行同批）的 JPA 接线：H2 内存库 + Hibernate。
///
/// 走真实的 `insertAll` 路径，写入与读回都在事务里（Hibernate 的写必须在事务内）。
/// 关联对象用 `entityManager.find` 取托管实例：给 `persist` 传游离的关联对象会撞
/// `TransientObjectException`，那是测试写法问题、不是被测语义。
class AssetBatchInsertConsistencyTest extends AssociationBatchInsertConsistencyTest<AssetEntity> {

    private final HibernateHarness harness =
            HibernateHarness.start("asset_batch_insert_consistency", OwnerEntity.class, AssetEntity.class);

    private final EntityOperations<AssetEntity> operations = new JpaEntityOperationsFactory(
                    harness.entityManager(), harness.entityManagerFactory())
            .operations(AssetEntity.class);

    private final Fixture<AssetEntity> fixture = new JpaFixture();

    @Override
    protected Fixture<AssetEntity> fixture() {
        return fixture;
    }

    @AfterEach
    void closeSupport() {
        harness.close();
    }

    /// 把真实的批量写路径包成夹具：JPA 的写必须在事务里跑，关联对象取托管实例。
    private final class JpaFixture implements Fixture<AssetEntity> {

        @Override
        public void seedData(List<AssetFixture> fixtures) {
            harness.entityManager().clear();
            harness.inTransaction(entityManager -> {
                entityManager.createQuery("delete from AssetEntity").executeUpdate();
                entityManager.createQuery("delete from OwnerEntity").executeUpdate();
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
                    entityManager.persist(
                            new AssetEntity(fixture.id(), fixture.name(), owners.get(fixture.ownerName())));
                }
            });
            harness.entityManager().clear();
        }

        @Override
        public EntityQuery<AssetEntity> query() {
            return operations.query();
        }

        @Override
        public void insertAll(List<AssetFixture> rows) {
            harness.inTransaction(entityManager -> {
                List<AssetEntity> entities = rows.stream()
                        .map(row -> new AssetEntity(row.id(), row.name(), owner(entityManager, row.ownerName())))
                        .toList();
                operations.insertAll(entities);
            });
            harness.entityManager().clear();
        }

        @Override
        public Long foreignKeyOf(long assetId) {
            // 放在事务里跑：Hibernate 的 session 只有在事务边界内才稳定地借/还连接。
            Object value = harness.inTransaction(() -> harness.entityManager()
                    .createNativeQuery("select owner_id from asset where id = ?")
                    .setParameter(1, assetId)
                    .getSingleResult());
            return value == null ? null : ((Number) value).longValue();
        }

        @Override
        public void clearPersistenceContext() {
            harness.entityManager().clear();
        }
    }

    /// 夹具里的 owner 名 → 已落库的 `@Id`（owner 按首次出现顺序编号：`alpha`=1、`beta`=2）。
    ///
    /// @param entityManager 写事务里的实体管理器
    /// @param ownerName     owner 名，`null` 表示无关联
    /// @return 托管关联对象；`ownerName` 为 `null` 时返回 `null`
    private static OwnerEntity owner(EntityManager entityManager, String ownerName) {
        if (ownerName == null) {
            return null;
        }
        if (ALPHA.equals(ownerName)) {
            return entityManager.find(OwnerEntity.class, ALPHA_OWNER_ID);
        }
        if (BETA.equals(ownerName)) {
            return entityManager.find(OwnerEntity.class, BETA_OWNER_ID);
        }
        throw new IllegalArgumentException("夹具里没有这个 owner: " + ownerName);
    }
}
