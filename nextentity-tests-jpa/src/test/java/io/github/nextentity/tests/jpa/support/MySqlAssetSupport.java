package io.github.nextentity.tests.jpa.support;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.testing.AssetFixture;
import io.github.nextentity.tests.jpa.AssetEntity;
import io.github.nextentity.tests.jpa.OwnerEntity;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// 关联嵌套用例的 MySQL 接线：与 H2 侧 `JpaAssetSupport` 同形，只把连接换成共享容器。
///
/// 两张表（`asset` / `owner`）由同一份实体元数据生成，夹具描述也相同——`ownerName` 为 `null`
/// 的那一行必须真的落成「关联为空」，否则左连接语义的对拍就失去意义。
public final class MySqlAssetSupport implements AutoCloseable {

    private static final String CLEAR_ASSETS = "delete from AssetEntity";
    private static final String CLEAR_OWNERS = "delete from OwnerEntity";

    private final HibernateHarness harness;
    private final EntityOperations<AssetEntity> operations;

    /// 连上共享的 MySQL 容器，并为本次会话建两张表（`hbm2ddl` 的 `create-drop`）。
    public MySqlAssetSupport() {
        this.harness = HibernateHarness.start(
                MySqlL4Container.jdbcUrl(),
                MySqlL4Container.username(),
                MySqlL4Container.password(),
                OwnerEntity.class,
                AssetEntity.class);
        EntityOperationsFactory factory =
                new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory());
        this.operations = factory.operations(AssetEntity.class);
    }

    /// 全新的查询入口。
    ///
    /// @return 全新的查询入口：条件不得跨用例累积
    public EntityQuery<AssetEntity> query() {
        return operations.query();
    }

    /// 两段嵌套路径：`AssetEntity::owner` → `OwnerEntity::name`。
    ///
    /// @return 两段嵌套的字符串路径
    public StringPath<AssetEntity> ownerNamePath() {
        return EntityPath.of(AssetEntity::owner).get(OwnerEntity::name);
    }

    /// 清空两表并写入夹具；`ownerName` 为 `null` 的夹具落成没有关联的行。
    ///
    /// 与 `MySqlItemSupport.seedData` 同款的两条防护：先 `clear()` 丢掉上一个用例留下的受管实体
    /// （否则同一主键会被判成 `NonUniqueObjectException`），异常一律回滚（否则共享装置里残留
    /// 活动事务，后面每个用例都报「Resource-local transaction already active」）。
    ///
    /// @param fixtures 夹具描述
    public void seedData(List<AssetFixture> fixtures) {
        EntityManager entityManager = harness.entityManager();
        entityManager.clear();
        entityManager.getTransaction().begin();
        try {
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
            entityManager.getTransaction().commit();
        } catch (RuntimeException e) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw e;
        } finally {
            entityManager.clear();
        }
    }

    @Override
    public void close() {
        harness.close();
    }
}
