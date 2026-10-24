package io.github.nextentity.tests.jpa.support;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import jakarta.persistence.EntityManager;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// 单实体写路径用例的 MySQL 接线：`@Version`、`@Convert`、批量写这类没有一致性基类可继承的场景，
/// 直接对着容器里的 `EntityOperations` 写，与 H2 侧同名用例一一对应。
///
/// 与 `MySqlItemSupport` 同款的两条防护：事务一律能回滚（否则共享装置里残留活动事务，后续用例全红）、
/// 每个用例前可复位表内容（H2 侧靠「每个用例一个新内存库」，容器侧只能靠 `delete`）。
///
/// @param <T> 被操作的实体类型
public final class MySqlEntityOperationsSupport<T> implements AutoCloseable {

    private final HibernateHarness harness;
    private final EntityOperations<T> operations;

    /// 连上共享的 MySQL 容器，并为本次会话建表。
    ///
    /// @param entityType    查询/写入入口的实体类型
    /// @param entityClasses 参与映射的实体类；空数组表示只映射 `entityType`
    public MySqlEntityOperationsSupport(Class<T> entityType, Class<?>... entityClasses) {
        Class<?>[] mapped = entityClasses.length == 0 ? new Class<?>[] {entityType} : entityClasses;
        this.harness = HibernateHarness.start(
                MySqlL4Container.jdbcUrl(), MySqlL4Container.username(), MySqlL4Container.password(), mapped);
        this.operations = new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory())
                .operations(entityType);
    }

    /// @return 该实体的查询/写入入口
    public EntityOperations<T> operations() {
        return operations;
    }

    /// @return 共享的 `EntityManager`：原生查询与受管状态都在它上面
    public EntityManager entityManager() {
        return harness.entityManager();
    }

    /// 在事务里执行并返回结果；已有事务就加入，异常一律回滚。
    ///
    /// @param work 事务内的工作
    /// @param <R>  返回类型
    /// @return `work` 的结果
    public <R> R inTransaction(Supplier<R> work) {
        EntityManager entityManager = harness.entityManager();
        if (entityManager.getTransaction().isActive()) {
            return work.get();
        }
        entityManager.getTransaction().begin();
        try {
            R result = work.get();
            entityManager.getTransaction().commit();
            return result;
        } catch (RuntimeException e) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw e;
        } finally {
            entityManager.clear();
        }
    }

    /// 在事务里执行一段没有返回值的工作。
    ///
    /// @param work 事务内的工作
    public void inTransaction(Consumer<EntityManager> work) {
        inTransaction(() -> {
            work.accept(harness.entityManager());
            return null;
        });
    }

    /// 清空某实体的全部行（JPQL 批量删除，按实体名指认）。
    ///
    /// @param entityName 实体名，例如 `VersionedEntity`
    public void truncate(String entityName) {
        inTransaction(entityManager ->
                entityManager.createQuery("delete from " + entityName).executeUpdate());
    }

    /// 丢掉受管状态，让下一次读取真的走库。
    public void clear() {
        harness.entityManager().clear();
    }

    @Override
    public void close() {
        harness.close();
    }
}
