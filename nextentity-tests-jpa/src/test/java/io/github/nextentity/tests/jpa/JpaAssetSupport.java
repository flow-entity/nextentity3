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
    ///
    /// @return 嵌套字符串路径
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

    @Override
    public void close() {
        harness.close();
    }
}
