package io.github.nextentity.tests.jpa.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

/// H2 + Hibernate 测试装置：建表、给 `EntityManager`、抓 SQL。
///
/// 只为测试服务，不引入 Spring，也不依赖 nextentity 的任何实现类——这样 SQL 快照用例
/// 既能独立证明工具本身可用，也能被 `nextentity-jpa` 的执行器直接复用。
///
/// H2 使用 MySQL 兼容模式，与 point-mall 集成测试（`application-integration-tests.yml`）同款。
public final class HibernateHarness implements AutoCloseable {

    private final SessionFactory sessionFactory;
    private final SqlRecorder recorder;
    private final EntityManager entityManager;

    private HibernateHarness(SessionFactory sessionFactory, SqlRecorder recorder) {
        this.sessionFactory = sessionFactory;
        this.recorder = recorder;
        this.entityManager = sessionFactory.createEntityManager();
    }

    /// 启动一套只含给定实体的 Hibernate（H2 内存库，建表后自动销毁）。
    ///
    /// @param databaseName  H2 内存库名；同一测试类复用时保持稳定，否则每次都要重建库
    /// @param entityClasses 参与映射的实体类
    /// @return 测试装置：用完必须 `close()`，否则内存库与 SessionFactory 都留着
    public static HibernateHarness start(String databaseName, Class<?>... entityClasses) {
        SqlRecorder recorder = new SqlRecorder();
        Configuration configuration = new Configuration()
                .setProperty(
                        "hibernate.connection.url", "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setStatementInspector(recorder);
        for (Class<?> entityClass : entityClasses) {
            configuration.addAnnotatedClass(entityClass);
        }
        return new HibernateHarness(configuration.buildSessionFactory(), recorder);
    }

    /// 当前测试线程独占的 `EntityManager`。
    ///
    /// @return 实例独占的 `EntityManager`，由 `close()` 一并关闭
    public EntityManager entityManager() {
        return entityManager;
    }

    /// 实体管理器工厂（JPA 元模型从它解析，见 `JpaMeta`）。
    ///
    /// @return 同一个 `SessionFactory`（它同时充当 `EntityManagerFactory`）
    public EntityManagerFactory entityManagerFactory() {
        return sessionFactory;
    }

    /// 立刻下发挂起的 DML，并让快照看到它。
    public void flush() {
        entityManager.flush();
    }

    /// 在事务里跑一段写入，并在正常结束时提交。
    ///
    /// @param work 事务体，非 `null`；正常返回即提交，抛异常则不提交
    public void inTransaction(Consumer<EntityManager> work) {
        entityManager.getTransaction().begin();
        work.accept(entityManager);
        entityManager.getTransaction().commit();
    }

    /// 在事务里跑一段查询并返回结果（悲观锁这类语句必须在事务内执行）。
    ///
    /// @param work 事务体，非 `null`
    /// @param <R>  结果类型
    /// @return 事务体的返回值，原样透传
    public <R> R inTransaction(Supplier<R> work) {
        entityManager.getTransaction().begin();
        R result = work.get();
        entityManager.getTransaction().commit();
        return result;
    }

    /// 取走已抓到的 SQL，并清空账本。
    ///
    /// @return 按执行顺序排列的 SQL；取走后账本清空
    public List<String> drainSql() {
        return recorder.drain();
    }

    /// 清空 SQL 账本（例如跳过建表 DDL 后开始记一次查询）。
    public void clearSql() {
        recorder.clear();
    }

    @Override
    public void close() {
        entityManager.close();
        sessionFactory.close();
    }
}
