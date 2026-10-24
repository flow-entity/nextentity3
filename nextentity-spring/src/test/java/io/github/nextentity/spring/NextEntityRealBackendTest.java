package io.github.nextentity.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.EntityOperationsFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/// `NextEntityAutoConfiguration` 的真实接线：真 Hibernate + H2 + **事务绑定的共享 EntityManager 代理**。
///
/// 与 [`NextEntityAutoConfigurationTest`] 的分工：那个用 `ApplicationContextRunner` + mock 证明
/// 「bean 数量与开关语义」，按红线只能算形状证据；这里把真后端接上，证明
/// 「注入的 `EntityManager` 是线程绑定的代理时，查询与插入真的能跑，且并发线程互不串」。
///
/// 为什么这条重要：`AbstractRepository` 是 Spring 侧唯一入口，point-mall 的仓储全是它的子类；
/// 工厂拿到的 `EntityManager` 在 Spring 里是 `SharedEntityManagerCreator` 的代理，
/// 每次调用按线程绑定/新建——这一点 mock 不出来，也只有在真并发下才看得出来。
class NextEntityRealBackendTest {

    private static final String ALPHA = "alpha";

    private static EntityManagerFactory entityManagerFactory;

    private AnnotationConfigApplicationContext context;

    private ThingRepository repository;

    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void startDatabase() {
        org.hibernate.cfg.Configuration configuration = new org.hibernate.cfg.Configuration();
        configuration.setProperty(
                "hibernate.connection.url", "jdbc:h2:mem:spring_real_backend;MODE=MySQL;DB_CLOSE_DELAY=-1");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.show_sql", false);
        configuration.addAnnotatedClass(ThingEntity.class);
        entityManagerFactory = configuration.buildSessionFactory();
    }

    @AfterAll
    static void stopDatabase() {
        entityManagerFactory.close();
    }

    /// 起一个最小 context：只注册自动配置 + 真 EMF + 共享 EM 代理 + 事务管理器。
    private void startContext() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("nextentity.enabled", "true")));
        context.register(NextEntityAutoConfiguration.class, RealBackendWiring.class);
        context.refresh();

        EntityOperationsFactory factory = context.getBean(EntityOperationsFactory.class);
        repository = new ThingRepository(factory);
        transactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            clearTable();
            context.close();
        }
    }

    /// 自动配置产出的工厂能直接喂给 `AbstractRepository` 子类，查询走真实库。
    @Test
    void repositoryQueriesRealBackendThroughAutoConfiguration() {
        startContext();
        insert(1L, ALPHA, 10);

        List<Long> ids = repository.query().where(ThingEntity::getName).eq(ALPHA).list().stream()
                .map(ThingEntity::getId)
                .toList();

        assertThat(ids).containsExactly(1L);
    }

    /// `AbstractRepository#insert` 在调用方事务里提交（本类不接管事务边界）。
    @Test
    void insertCommitsInCallerTransaction() {
        startContext();

        transactionTemplate.executeWithoutResult(status -> repository.insert(new ThingEntity(2L, "beta", 20)));

        assertThat(repository.query().where(ThingEntity::getName).eq("beta").count())
                .isEqualTo(1L);
    }

    /// 8 个线程同时打同一个仓储：每个线程只看到自己的行，且共享 EM 代理不会把连接串到别的线程上。
    @Test
    void concurrentThreadsOnSharedEntityManagerStayIsolated() throws Exception {
        startContext();
        int threads = 8;
        int rowsPerThread = 5;
        for (int t = 0; t < threads; t++) {
            for (int r = 0; r < rowsPerThread; r++) {
                insert((long) (t * 100 + r), "owner-" + t, r);
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<Long>>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int owner = t;
                tasks.add(() -> repository
                        .query()
                        .where(ThingEntity::getName)
                        .eq("owner-" + owner)
                        .orderBy(ThingEntity::getId)
                        .asc()
                        .list()
                        .stream()
                        .map(ThingEntity::getId)
                        .toList());
            }
            List<Future<List<Long>>> futures = pool.invokeAll(tasks);
            for (int t = 0; t < threads; t++) {
                List<Long> expected = new ArrayList<>();
                for (int r = 0; r < rowsPerThread; r++) {
                    expected.add((long) (t * 100 + r));
                }
                assertThat(futures.get(t).get()).containsExactlyElementsOf(expected);
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /// 并发写：每个线程在自己的事务里插入，互不覆盖，最终行数等于总插入数。
    @Test
    void concurrentInsertsEachCommitInTheirOwnTransaction() throws Exception {
        startContext();
        int threads = 4;
        int rowsPerThread = 3;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int owner = t;
                tasks.add(() -> {
                    for (int r = 0; r < rowsPerThread; r++) {
                        ThingEntity entity = new ThingEntity((long) (1000 + owner * 10 + r), "writer-" + owner, r);
                        transactionTemplate.executeWithoutResult(status -> repository.insert(entity));
                    }
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(repository.query().count()).isEqualTo((long) (threads * rowsPerThread));
    }

    private void insert(long id, String name, int score) {
        transactionTemplate.executeWithoutResult(status -> repository.insert(new ThingEntity(id, name, score)));
    }

    private void clearTable() {
        transactionTemplate.executeWithoutResult(status -> context.getBean(EntityManager.class)
                .createQuery("delete from ThingEntity")
                .executeUpdate());
    }

    /// 仓储子类：`AbstractRepository` 就是给这种用法准备的（泛型解析实体类型）。
    private static final class ThingRepository extends AbstractRepository<ThingEntity> {

        private ThingRepository(EntityOperationsFactory factory) {
            super(factory);
        }
    }

    /// 真后端零件：EMF、事务管理器、以及 Spring 的事务绑定共享 EM 代理。
    @Configuration(proxyBeanMethods = false)
    static class RealBackendWiring {

        @Bean(destroyMethod = "")
        EntityManagerFactory entityManagerFactory() {
            return entityManagerFactory;
        }

        @Bean(destroyMethod = "")
        EntityManager entityManager(EntityManagerFactory entityManagerFactory) {
            return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }
}
