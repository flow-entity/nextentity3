package io.github.nextentity.tests.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.spring.AbstractRepository;
import io.github.nextentity.spring.NextEntityAutoConfiguration;
import io.github.nextentity.tests.jpa.support.HibernateHarness;
import io.github.nextentity.tests.jpa.support.PostgresL4Container;
import io.github.nextentity.tests.jpa.support.PostgresL4Gate;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/// Spring 侧真实接线的**容器变体**：自动配置 + 事务绑定的共享 `EntityManager` 代理 + 真 PostgreSQL 8。
///
/// 与 `nextentity-spring` 的 `NextEntityRealBackendTest` 的分工：那边跑 H2 兼容模式，证明的是
/// 「代理是线程绑定的、查询与插入能跑」；这里把同一套接线接到**真方言**上，补的是 H2 挡不住的
/// 三件事——
///
/// 1. **提交边界**：回滚必须真的丢数据（H2 内存库上 `setRollbackOnly` 也是丢，但它同时证明不了
///    「Spring 事务与 PostgreSQL 连接真的绑在一起」）；
/// 2. **未提交不可见**：另一个线程用同一个共享代理查，必须看不到本线程未提交的行——这条在
///    「插入被悄悄自动提交」时会立刻变红，而单线程用例看不出来；
/// 3. **并发隔离**：多线程各查各的行，连接不会串。
///
/// 断言全部落在**结果层**（行内容、行数），不看 SQL 文本：SQL 文本只能证明「下发对了」，
/// 而这条链要证的是「真库上行为对」。
@ExtendWith(PostgresL4Gate.class)
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSpringRepositoryIntegrationTest {

    private static final String ALPHA = "alpha";

    /// 静态：嵌套的 `@Configuration` 是静态类，取 EMF 时没有外部实例可引用。
    private static EntityManagerFactory entityManagerFactory;

    private AnnotationConfigApplicationContext context;

    private SpringWiredRepository repository;

    private TransactionTemplate transactionTemplate;

    @BeforeAll
    void startDatabase() {
        HibernateHarness harness = HibernateHarness.start(
                PostgresL4Container.jdbcUrl(),
                PostgresL4Container.username(),
                PostgresL4Container.password(),
                SpringWiredEntity.class);
        entityManagerFactory = harness.entityManagerFactory();
    }

    @AfterAll
    void stopDatabase() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    @BeforeEach
    void startContext() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("nextentity.enabled", "true")));
        context.register(NextEntityAutoConfiguration.class, SpringWiring.class);
        context.refresh();

        repository = new SpringWiredRepository(context.getBean(EntityOperationsFactory.class));
        transactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            transactionTemplate.executeWithoutResult(status -> context.getBean(EntityManager.class)
                    .createQuery("delete from SpringWiredEntity")
                    .executeUpdate());
            context.close();
        }
    }

    /// 自动配置产出的工厂直接喂给 `AbstractRepository` 子类，查询在真 PostgreSQL 上执行。
    @Test
    void repositoryQueriesPostgresThroughSharedEntityManagerProxy() {
        insert(1L, ALPHA, 10);
        insert(2L, ALPHA, 30);
        insert(3L, "beta", 20);

        List<Long> ids = repository
                .query()
                .where(SpringWiredEntity::getName)
                .eq(ALPHA)
                .orderBy(SpringWiredEntity::getScore)
                .desc()
                .list()
                .stream()
                .map(SpringWiredEntity::getId)
                .toList();

        assertThat(ids).containsExactly(2L, 1L);
    }

    /// 回滚必须丢数据：`setRollbackOnly` 之后行数仍是 0，证明 Spring 事务真的管住了这条 PostgreSQL 连接。
    @Test
    void rollbackDiscardsInsert() {
        transactionTemplate.executeWithoutResult(status -> {
            repository.insert(new SpringWiredEntity(1L, ALPHA, 10));
            assertThat(repository.query().count())
                    .as("同一事务内必须读得到自己的写：否则「回滚后为 0」对空实现的 insert 也成立")
                    .isEqualTo(1L);
            status.setRollbackOnly();
        });

        assertThat(repository.query().count()).isZero();
    }

    /// 本线程未提交的行，别的线程（同一个共享代理、另一条连接）看不到；提交后才看得到。
    @Test
    void uncommittedRowIsInvisibleToOtherThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                repository.insert(new SpringWiredEntity(1L, ALPHA, 10));
                assertThat(repository.query().count())
                        .as("本线程必须读得到自己的写：否则下面的「不可见」对空实现的 insert 也成立")
                        .isEqualTo(1L);
                try {
                    Future<Long> seen = pool.submit(() -> repository.query().count());
                    assertThat(seen.get(30, TimeUnit.SECONDS))
                            .as("未提交的行对其它线程必须不可见")
                            .isZero();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(repository.query().count()).isEqualTo(1L);
    }

    /// 多线程并发读同一个仓储：每个线程只看到自己的行，共享代理不会把连接串到别的线程上。
    @Test
    void concurrentReadersEachSeeOnlyTheirRows() throws Exception {
        int threads = 4;
        int rowsPerThread = 3;
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
                        .where(SpringWiredEntity::getName)
                        .eq("owner-" + owner)
                        .orderBy(SpringWiredEntity::getId)
                        .asc()
                        .list()
                        .stream()
                        .map(SpringWiredEntity::getId)
                        .toList());
            }
            List<Future<List<Long>>> futures = pool.invokeAll(tasks);
            for (int t = 0; t < threads; t++) {
                List<Long> expected = new ArrayList<>();
                for (int r = 0; r < rowsPerThread; r++) {
                    expected.add((long) (t * 100 + r));
                }
                assertThat(futures.get(t).get(30, TimeUnit.SECONDS)).containsExactlyElementsOf(expected);
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void insert(long id, String name, int score) {
        transactionTemplate.executeWithoutResult(status -> repository.insert(new SpringWiredEntity(id, name, score)));
    }

    /// 仓储子类：`AbstractRepository` 就是给这种用法准备的（泛型解析实体类型）。
    private static final class SpringWiredRepository extends AbstractRepository<SpringWiredEntity> {

        private SpringWiredRepository(EntityOperationsFactory factory) {
            super(factory);
        }
    }

    /// 真后端零件：EMF（复用容器装置，不另建连接池）、事务管理器、Spring 的事务绑定共享 EM 代理。
    @Configuration(proxyBeanMethods = false)
    static class SpringWiring {

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
