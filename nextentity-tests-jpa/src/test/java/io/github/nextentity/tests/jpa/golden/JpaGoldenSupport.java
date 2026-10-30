package io.github.nextentity.tests.jpa.golden;

import io.github.nextentity.api.EntityQuery;
import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import io.github.nextentity.testing.golden.GoldenFixtures;
import io.github.nextentity.testing.golden.GoldenSchema;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import io.github.nextentity.tests.jpa.support.MySqlL4Container;
import io.github.nextentity.tests.jpa.support.PostgresL4Container;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Supplier;

/// golden schema 的 JPA 接线：三张表由同一份实体注解经 `hbm2ddl` 建出，写入走
/// `JpaEntityOperationsFactory`（生产装配），读回也走生产查询入口。
///
/// 建表刻意**不**手写 DDL：golden 表在 JDBC 侧由方言脚本真执行（H2/MySQL/PostgreSQL/SQL Server），
/// JPA 侧由同一套注解生成——两侧列集是否一致由 [JpaGoldenSchemaLandingContract] 的往返断言对齐。
/// 手写一份 JPA 专用 DDL 只会把「注解写错」和「DDL 写错」混在一起。
public final class JpaGoldenSupport implements AutoCloseable {

    private static final Class<?>[] GOLDEN_ENTITIES = {
        GoldenSchema.Customer.class, GoldenSchema.Order.class, GoldenSchema.OrderLine.class
    };

    private final HibernateHarness harness;
    private final EntityOperationsFactory factory;

    /// H2 通道：独立内存库。
    ///
    /// @param databaseName H2 内存库名
    public JpaGoldenSupport(String databaseName) {
        this(HibernateHarness.start(databaseName, GOLDEN_ENTITIES));
    }

    private JpaGoldenSupport(HibernateHarness harness) {
        this.harness = harness;
        this.factory = new JpaEntityOperationsFactory(harness.entityManager(), harness.entityManagerFactory());
    }

    /// MySQL 容器通道：连共享的 MySQL 容器，建表交给 `hbm2ddl`。
    ///
    /// @return 已建表可用的装置
    public static JpaGoldenSupport onMySql() {
        return new JpaGoldenSupport(HibernateHarness.start(
                MySqlL4Container.jdbcUrl(), MySqlL4Container.username(), MySqlL4Container.password(), GOLDEN_ENTITIES));
    }

    /// PostgreSQL 容器通道：连共享的 PostgreSQL 容器，建表交给 `hbm2ddl`。
    ///
    /// @return 已建表可用的装置
    public static JpaGoldenSupport onPostgres() {
        return new JpaGoldenSupport(HibernateHarness.start(
                PostgresL4Container.jdbcUrl(),
                PostgresL4Container.username(),
                PostgresL4Container.password(),
                GOLDEN_ENTITIES));
    }

    /// 某个 golden 实体的写/查入口。
    ///
    /// @param type golden 实体类型
    /// @param <T>  实体类型
    /// @return 生产装配下的操作入口
    public <T> EntityOperations<T> operations(Class<T> type) {
        return factory.operations(type);
    }

    /// 全新查询入口。
    ///
    /// @param type golden 实体类型
    /// @param <T>  实体类型
    /// @return 全新查询入口
    public <T> EntityQuery<T> query(Class<T> type) {
        return operations(type).query();
    }

    /// 清空三表（顺序按外键依赖），并丢弃受管状态。
    public void clear() {
        EntityManager entityManager = harness.entityManager();
        entityManager.clear();
        harness.inTransaction(em -> {
            em.createQuery("delete from GoldenOrderLine").executeUpdate();
            em.createQuery("delete from GoldenOrder").executeUpdate();
            em.createQuery("delete from GoldenCustomer").executeUpdate();
        });
        entityManager.clear();
    }

    /// 用夹具写满三张表：一次事务内按依赖顺序 `persist`（父 → 子 → 孙）。
    ///
    /// 关联对象是同一批夹具实例，写入后主键照样回填；`bob` 的地址与 `102` 的备注为 `null`，
    /// 这两处空值是「`@Embedded` 整组为空」与「可空列」的落库证据。
    public void seedGolden() {
        EntityManager entityManager = harness.entityManager();
        entityManager.clear();
        GoldenFixtures.GoldenData data = GoldenFixtures.data();
        harness.inTransaction(em -> {
            data.customers().forEach(em::persist);
            data.orders().forEach(em::persist);
            data.lines().forEach(em::persist);
        });
        entityManager.clear();
    }

    /// 在事务里执行并返回结果（悲观锁、惰性集合的读取都需要事务）。
    ///
    /// @param work 事务体
    /// @param <R>  结果类型
    /// @return 事务体返回值
    public <R> R inTransaction(Supplier<R> work) {
        return harness.inTransaction(work);
    }

    /// 清空持久化上下文，把已读出的实体变成游离态。
    ///
    /// 单实体 `update(entity)` 对**托管**实体是 flush 语义（脏检查的活），只有游离实体才走
    /// 「按 id+version 条件更新并回写版本」的路径；要证后者必须先把实体摘下来。
    public void detach() {
        harness.entityManager().clear();
    }

    /// 取走已抓到的 SQL。
    ///
    /// @return 按执行顺序排列的 SQL；取走后账本清空
    public List<String> drainSql() {
        return harness.drainSql();
    }

    /// 清空 SQL 账本。
    public void clearSql() {
        harness.clearSql();
    }

    @Override
    public void close() {
        harness.close();
    }
}
