package io.github.nextentity.tests.jpa.support;

import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// 写侧**往返**用例的接线：同一套写入代码既能连 H2 也能连真 MySQL，并且暴露 `drainSql()`。
///
/// 与 `JpaItemSupport` / `MySqlEntityOperationsSupport` 的差别只有一处：那两个装置的用途是「查询一致性」，
/// 不需要看下发的语句；往返用例必须同时钉住**语句形态**（`insert into` / `update … where id` 的骨架），
/// 否则「应用侧循环单条写」「update 少了 where」「delete 少了条件」这类改动比值断言完全看不出来
/// ——多写一条再覆盖回来，读回的值一样对。
///
/// 装配仍走 `JpaEntityOperationsFactory`（`NextEntityAutoConfiguration` 注册的就是它），不引入测试专用旁路。
///
/// @param <T> 主实体类型
public final class WriteRoundTripSupport<T> implements AutoCloseable {

    private final HibernateHarness harness;
    private final EntityOperations<T> operations;

    private WriteRoundTripSupport(HibernateHarness harness, Class<T> entityType) {
        this.harness = harness;
        this.operations = new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory())
                .operations(entityType);
    }

    /// 起一套 H2 内存库（MySQL 兼容模式），只映射给定实体。
    ///
    /// @param databaseName H2 内存库名
    /// @param entityType   主实体类型
    /// @param mapped       同装置上额外映射的实体类型
    /// @param <T>          主实体类型
    /// @return 装置：用完必须 `close()`
    public static <T> WriteRoundTripSupport<T> onH2(String databaseName, Class<T> entityType, Class<?>... mapped) {
        return new WriteRoundTripSupport<>(
                HibernateHarness.start(databaseName, entities(entityType, mapped)), entityType);
    }

    /// 连上共享的 MySQL 容器（`hbm2ddl` 建表），只映射给定实体。
    ///
    /// @param entityType 主实体类型
    /// @param mapped     同装置上额外映射的实体类型
    /// @param <T>        主实体类型
    /// @return 装置：用完必须 `close()`
    public static <T> WriteRoundTripSupport<T> onMySql(Class<T> entityType, Class<?>... mapped) {
        return new WriteRoundTripSupport<>(
                HibernateHarness.start(
                        MySqlL4Container.jdbcUrl(),
                        MySqlL4Container.username(),
                        MySqlL4Container.password(),
                        entities(entityType, mapped)),
                entityType);
    }

    private static Class<?>[] entities(Class<?> entityType, Class<?>[] mapped) {
        Class<?>[] all = Arrays.copyOf(mapped, mapped.length + 1);
        all[mapped.length] = entityType;
        return all;
    }

    /// @return 主实体的查询/写入入口
    public EntityOperations<T> operations() {
        return operations;
    }

    /// 同一个装置上的另一种实体：一个 `SessionFactory` 可以映射多个实体，写入接缝按实体类型取。
    ///
    /// @param entityType 目标实体类型
    /// @param <E>        目标实体类型
    /// @return 该实体的查询/写入入口
    public <E> EntityOperations<E> operationsFor(Class<E> entityType) {
        return new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory())
                .operations(entityType);
    }

    /// @return 共享的 `EntityManager`：原生查询与受管状态都在它上面
    public EntityManager entityManager() {
        return harness.entityManager();
    }

    /// 在事务里执行一段写入；异常一律回滚后原样抛出，免得共享装置里残留活动事务把一条真缺陷放大成一片假红。
    ///
    /// @param work 事务内的工作
    public void inTransaction(Consumer<EntityManager> work) {
        inTransaction(() -> {
            work.accept(harness.entityManager());
            return null;
        });
    }

    /// 在事务里执行并返回结果。
    ///
    /// @param work 事务内的工作
    /// @param <R>  返回类型
    /// @return `work` 的结果
    public <R> R inTransaction(Supplier<R> work) {
        EntityManager entityManager = harness.entityManager();
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

    /// 清空某实体的全部行（JPQL 批量删除，按实体名指认）。
    ///
    /// @param entityName 实体名，例如 `ItemEntity`
    public void truncate(String entityName) {
        inTransaction(entityManager ->
                entityManager.createQuery("delete from " + entityName).executeUpdate());
    }

    /// 丢掉受管状态，让下一次读取真的走库。
    public void clear() {
        harness.entityManager().clear();
    }

    /// @return 按执行顺序排列的下发语句；取走后账本清空
    public List<String> drainSql() {
        return harness.drainSql();
    }

    /// 清空语句账本（跳过建表与前置准备，只记被测动作）。
    public void clearSql() {
        harness.clearSql();
    }

    /// 把记录到的语句归一化成一行：小写、连续空白压成单空格、去首尾空白。
    ///
    /// 断言侧一律拿归一化后的**整句**比对，不用片段：片段在列清单换序、占位符个数变化、
    /// 别名或 `where` 子句被改写时都不会红，只有整句相等才能钉住「实际下发的是什么」。
    ///
    /// @param statement 记录到的原始语句
    /// @return 归一化后的一行文本
    public static String normalized(String statement) {
        return statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    @Override
    public void close() {
        harness.close();
    }
}
