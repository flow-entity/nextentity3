package io.github.nextentity.tests.jpa.support;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.api.PathRef;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.testing.ConsistencySupport;
import io.github.nextentity.testing.ItemStatus;
import io.github.nextentity.tests.jpa.ItemEntity;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Supplier;

/// 一致性用例的 MySQL 接线：连共享容器、用生产同款工厂取操作入口、清表并写夹具。
///
/// 与 H2 侧 `JpaItemSupport` 的差别只有「连哪个库」这一处：同一个实体、同一套夹具、
/// 同一个 `JpaEntityOperationsFactory` 装配路径。夹具与装配任一处不同，两边的结果集就没有
/// 可比性——那就不再是对拍，只是在两个库上各跑一套自己的题。
public final class MySqlItemSupport implements ConsistencySupport<ItemEntity>, AutoCloseable {

    private static final String CLEAR_ALL = "delete from ItemEntity";

    private final HibernateHarness harness;
    private final EntityOperations<ItemEntity> operations;

    /// 连上共享的 MySQL 容器，并为本次会话建表（`hbm2ddl` 的 `create-drop`）。
    public MySqlItemSupport() {
        this.harness = HibernateHarness.start(
                MySqlL4Container.jdbcUrl(), MySqlL4Container.username(), MySqlL4Container.password(), ItemEntity.class);
        EntityOperationsFactory factory =
                new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory());
        this.operations = factory.operations(ItemEntity.class);
    }

    @Override
    public EntityQuery<ItemEntity> query() {
        return operations.query();
    }

    @Override
    public void seedData(List<ItemEntity> items) {
        // 一个装置被同一个用例类的全部用例共享（容器冷启动太贵，不能每个用例起一套），
        // 所以持久化上下文里可能还留着上一个用例查出来的受管实体：先丢掉它们，否则同一个主键
        // 会被判成「已有关联对象」（`NonUniqueObjectException`），而不是被覆盖写。
        EntityManager entityManager = harness.entityManager();
        entityManager.clear();
        entityManager.getTransaction().begin();
        try {
            entityManager.createQuery(CLEAR_ALL).executeUpdate();
            operations.insertAll(items);
            entityManager.getTransaction().commit();
        } catch (RuntimeException e) {
            // 用例失败也必须回滚：共享装置里残留一个活动事务，后面每个用例都会报
            // 「Resource-local transaction already active」——一条真缺陷会放大成一片假红。
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw e;
        } finally {
            entityManager.clear();
        }
    }

    @Override
    public ItemEntity newItem(long id, String name, Integer score, Integer score2, String note, ItemStatus status) {
        return new ItemEntity(id, name, score, score2, note, status);
    }

    @Override
    public PathRef<ItemEntity, Long> idRef() {
        return ItemEntity::id;
    }

    @Override
    public PathRef.StringRef<ItemEntity> nameRef() {
        return ItemEntity::name;
    }

    @Override
    public PathRef.NumberRef<ItemEntity, Integer> scoreRef() {
        return ItemEntity::score;
    }

    @Override
    public PathRef.NumberRef<ItemEntity, Integer> score2Ref() {
        return ItemEntity::score2;
    }

    @Override
    public PathRef.StringRef<ItemEntity> noteRef() {
        return ItemEntity::note;
    }

    @Override
    public PathRef<ItemEntity, ItemStatus> statusRef() {
        return ItemEntity::status;
    }

    @Override
    public void close() {
        harness.close();
    }

    /// 在事务里执行并返回结果：悲观锁这类要求「查询在事务内」的场景用得到。
    ///
    /// 已有事务就加入，不嵌套开新的；异常一律回滚——共享装置里残留活动事务会让同类的
    /// 后续用例全部报「Resource-local transaction already active」。
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

    /// 取走本线程已抓到的 SQL（按执行顺序）并清空账本。
    ///
    /// @return 本次抓到的 SQL 列表
    public List<String> drainSql() {
        return harness.drainSql();
    }

    /// 清空 SQL 账本（建表 DDL 也会被记录，断言前先丢掉）。
    public void clearSql() {
        harness.clearSql();
    }
}
