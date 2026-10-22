package io.github.nextentity.integration.config;

import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.integration.config.env.DatabaseEnvironmentVariables;
import io.github.nextentity.integration.config.fixtures.TestDataFactory;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

/// 2.2.3 `integration` 用例的测试装置：一套真实 Hibernate + 一个环境（H2 / MySQL）。
///
/// 与 2.2.3 的差别只有装配方式，语义刻意保持一致：
/// - 2.2.3 用 Spring Boot 起容器（`IntegrationTestApplication`），这里直接用 Hibernate，
///   因为 3.x 的 `JpaEntityOperationsFactory` 只要 `EntityManager` + `EntityManagerFactory`，
///   中间那层 Spring 对被测语义没有贡献。
/// - **建表用环境自带的 DDL，不用 `hbm2ddl`**：2.2.3 的列名（`department_id`、`created_at`）
///   依赖 Spring Boot 的驼峰转下划线命名策略，所以这里显式装 `CamelCaseToUnderscoresNamingStrategy`，
///   让 DDL 成为唯一事实来源——实体映射写错会直接报列不存在，而不是被自动建表掩盖。
public final class LegacyHarness implements AutoCloseable {

    /// 清表顺序：子表在前，避免外键顺序问题（与 2.2.3 `DatabaseInitializer.RESET_ORDER` 一致）。
    private static final List<String> TABLES_IN_DELETE_ORDER = List.of(
            "instant_entity",
            "auto_increment_entity",
            "sales_order",
            "customer",
            "person_with_cross_layer_embedded",
            "person_with_nested_overridden_contact",
            "person_with_overridden_address",
            "person_with_nested_address",
            "person_with_address",
            "employee",
            "department",
            "category",
            "lockable_entity");

    private final SessionFactory sessionFactory;
    private final EntityManager entityManager;
    private final EntityOperationsFactory operationsFactory;
    private final DatabaseEnvironmentVariables environment;

    private LegacyHarness(SessionFactory sessionFactory, DatabaseEnvironmentVariables environment) {
        this.sessionFactory = sessionFactory;
        this.environment = environment;
        this.entityManager = sessionFactory.createEntityManager();
        this.operationsFactory = new JpaEntityOperationsFactory(entityManager, sessionFactory);
    }

    /// 起一个环境：建 SessionFactory、按环境 DDL 建表、写入夹具。
    ///
    /// @param environment   数据库环境（URL / 账号 / DDL）
    /// @param entityClasses 参与映射的实体类
    /// @return 装置实例；用完必须 `close()`
    public static LegacyHarness start(DatabaseEnvironmentVariables environment, Class<?>... entityClasses) {
        Configuration configuration = new Configuration()
                .setProperty("hibernate.connection.url", environment.getJdbcUrl())
                .setProperty("hibernate.connection.username", environment.getUsername())
                .setProperty("hibernate.connection.password", environment.getPassword())
                .setProperty("hibernate.connection.driver_class", environment.getDriverClassName())
                .setProperty("hibernate.hbm2ddl.auto", "none")
                .setProperty(
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        for (Class<?> entityClass : entityClasses) {
            configuration.addAnnotatedClass(entityClass);
        }
        LegacyHarness harness = new LegacyHarness(configuration.buildSessionFactory(), environment);
        harness.createSchema();
        harness.reset();
        return harness;
    }

    /// 当前线程用的 `EntityManager`（整个环境只有一个，用例串行执行）。
    public EntityManager entityManager() {
        return entityManager;
    }

    /// 实体管理器工厂（JPA 元模型从它解析）。
    public EntityManagerFactory entityManagerFactory() {
        return sessionFactory;
    }

    /// 按实体类取 3.x 的写入口：写方法自动包一层事务，读方法直通。
    ///
    /// 2.2.3 的 `TransactionUpdateExecutor` 就是这个作用——用例里大量 `getUpdateExecutor().insert(...)`
    /// 并没有自己开事务，靠执行器兜住。
    public <T> EntityOperations<T> operations(Class<T> type) {
        return new TransactionalEntityOperations<>(operationsFactory.operations(type), this);
    }

    /// 删除一条实体（2.2.3 `PersistExecutor.delete(entity, descriptor)` 的对位）。
    ///
    /// 3.x 的写入接缝按调用点裁剪掉了 `delete`，这里用 JPA 的 `remove` 补齐——
    /// 用例真正断言的是"删完查不到"，与谁执行删除无关。
    public void delete(Object entity) {
        inTransaction(() -> {
            entityManager.remove(entityManager.contains(entity) ? entity : entityManager.merge(entity));
            return null;
        });
    }

    /// 批量删除（2.2.3 `PersistExecutor.deleteAll(entities, descriptor)` 的对位）。
    public void deleteAll(Iterable<?> entities) {
        inTransaction(() -> {
            for (Object entity : entities) {
                delete(entity);
            }
            return null;
        });
    }

    /// 在事务里执行；已有事务则加入，不嵌套开新事务。
    public <R> R inTransaction(Supplier<R> work) {
        if (entityManager.getTransaction().isActive()) {
            return work.get();
        }
        entityManager.getTransaction().begin();
        R result;
        try {
            result = work.get();
            entityManager.getTransaction().commit();
        } catch (RuntimeException e) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw e;
        } finally {
            // 与 Spring 的"一次事务一个持久化上下文"对齐：事务结束后实体脱离，
            // 用例读到的永远是库里的新值，而不是一级缓存里的旧对象。
            entityManager.clear();
        }
        return result;
    }

    /// 清空全部表并重装夹具。
    public void reset() {
        inTransaction(() -> {
            entityManager.clear();
            for (String table : TABLES_IN_DELETE_ORDER) {
                entityManager.createNativeQuery("delete from " + table).executeUpdate();
            }
            return null;
        });
        inTransaction(() -> {
            persistAll(TestDataFactory.createDepartments());
            persistAll(TestDataFactory.createCategories());
            persistAll(TestDataFactory.createEmployees());
            persistAll(TestDataFactory.createLockableEntities());
            persistAll(TestDataFactory.createCustomers());
            persistAll(TestDataFactory.createSalesOrders());
            return null;
        });
    }

    /// 环境名，用于用例显示名（如 `h2`）。
    public String environmentName() {
        return environment.name();
    }

    @Override
    public void close() {
        entityManager.close();
        sessionFactory.close();
    }

    private void createSchema() {
        entityManager.getTransaction().begin();
        try {
            for (String sql : environment.ddl()) {
                entityManager.createNativeQuery(sql).executeUpdate();
            }
            entityManager.getTransaction().commit();
        } catch (RuntimeException e) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw e;
        }
    }

    private void persistAll(List<?> entities) {
        for (Object entity : entities) {
            entityManager.persist(entity);
        }
        entityManager.flush();
    }

    /// 把 3.x 的写入接缝包成"每次调用一个事务"，读路径不加装饰。
    private record TransactionalEntityOperations<T>(EntityOperations<T> delegate, LegacyHarness harness)
            implements EntityOperations<T> {

        @Override
        public io.github.nextentity.api.EntityQuery<T> query() {
            return delegate.query();
        }

        @Override
        public io.github.nextentity.core.meta.EntityType<T> entityType() {
            return delegate.entityType();
        }

        @Override
        public void insert(T entity) {
            harness.inTransaction(() -> {
                delegate.insert(entity);
                return null;
            });
        }

        @Override
        public void insertAll(Iterable<T> entities) {
            harness.inTransaction(() -> {
                delegate.insertAll(entities);
                return null;
            });
        }

        @Override
        public void update(T entity) {
            harness.inTransaction(() -> {
                delegate.update(entity);
                return null;
            });
        }

        @Override
        public void updateAll(Iterable<T> entities) {
            harness.inTransaction(() -> {
                delegate.updateAll(entities);
                return null;
            });
        }
    }
}
