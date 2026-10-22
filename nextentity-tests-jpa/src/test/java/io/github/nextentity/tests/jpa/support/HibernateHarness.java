package io.github.nextentity.tests.jpa.support;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Consumer;
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
    /// @param databaseName  H2 内存库名，同一测试类复用时保持稳定
    /// @param entityClasses 参与映射的实体类
    /// @return 测试装置，用完必须 `close()`
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
    /// @return 实体管理器
    public EntityManager entityManager() {
        return entityManager;
    }

    /// 立刻下发挂起的 DML，并让快照看到它。
    public void flush() {
        entityManager.flush();
    }

    /// 在事务里跑一段写入，并在正常结束时提交。
    ///
    /// @param work 事务体
    public void inTransaction(Consumer<EntityManager> work) {
        entityManager.getTransaction().begin();
        work.accept(entityManager);
        entityManager.getTransaction().commit();
    }

    /// 取走已抓到的 SQL，并清空账本。
    ///
    /// @return 按执行顺序排列的 SQL 列表
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
