package io.github.nextentity.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/// 自动配置的门禁与 bean 注册：`nextentity.enabled` 是开关，开时**恰好一个**工厂 bean。
///
/// 这两个事实都是 point-mall 的硬需求：0 个 bean 会让
/// `App.getBean(EntityOperationsFactory.class)` 抛 `NoSuchBeanDefinitionException`，
/// 2 个会抛 `NoUniqueBeanDefinitionException`。
///
/// **为什么这里不用 mock 的 `EntityManagerFactory` / `EntityManager`**：按红线，mock 只能证明
/// 「调用形状对」，不能当某个能力的唯一正确性证据。本类改用真 Hibernate + H2 建 EMF、用
/// `SharedEntityManagerCreator` 建 Spring 的线程绑定共享 EM 代理，于是断言可以越过「bean 个数」
/// 直达「这个 bean 拿真元模型解析出了 `ThingEntity` 的主键」——mock 的 EMF 上这一步必然失败。
///
/// 与 [`NextEntityRealBackendTest`] 的分工：那个把整条链路接到真库上验查询/插入/并发隔离；
/// 这个只验**装配**（开关语义、bean 类型、元模型解析），不落库，所以不需要事务管理器。
class NextEntityAutoConfigurationTest {

    private static final String ENABLED = "nextentity.enabled";

    private static EntityManagerFactory entityManagerFactory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NextEntityAutoConfiguration.class))
            .withBean(EntityManagerFactory.class, () -> entityManagerFactory)
            .withBean(
                    EntityManager.class,
                    () -> SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));

    /// 真 H2 + 真 Hibernate：EMF 必须能解析 `ThingEntity` 的元模型，否则下面的断言退化成形状检查。
    ///
    /// 每个用例建一个 EMF：`EntityManagerFactory` 被注册成 Spring bean 后，上下文关闭时 Spring 会按
    /// 推断出的 `close()` 销毁方法把它关掉，跨用例复用会在第二个用例里撞
    /// `EntityManagerFactory is closed`。
    @BeforeEach
    void startDatabase() {
        org.hibernate.cfg.Configuration configuration = new org.hibernate.cfg.Configuration();
        configuration.setProperty(
                "hibernate.connection.url", "jdbc:h2:mem:spring_autoconfig;MODE=MySQL;DB_CLOSE_DELAY=-1");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.show_sql", false);
        configuration.addAnnotatedClass(ThingEntity.class);
        entityManagerFactory = configuration.buildSessionFactory();
    }

    @AfterEach
    void stopDatabase() {
        entityManagerFactory.close();
    }

    /// 开关打开时注册唯一一个工厂 bean，且这个 bean 能拿真元模型解析出实体元数据。
    @Test
    void registersExactlyOneUsableFactoryBeanWhenEnabled() {
        runner.withPropertyValues(ENABLED + "=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(EntityOperationsFactory.class))
                    .hasSize(1);

            EntityOperationsFactory factory = context.getBean(EntityOperationsFactory.class);
            assertThat(factory).isInstanceOf(JpaEntityOperationsFactory.class);

            // 真元模型解析：mock 的 EMF 走到这里会抛，所以这一段才是「装配真的能用」的证据
            EntityOperations<ThingEntity> operations = factory.operations(ThingEntity.class);
            assertThat(operations.entityType().type()).isEqualTo(ThingEntity.class);
            assertThat(operations.entityType().id().name()).isEqualTo("id");
            // `ThingEntity` 没有 `@Version`：元数据必须如实返回 null，不能编一个出来
            assertThat(operations.entityType().version()).isNull();
        });
    }

    /// 开关缺失时不注册（point-mall 之外的应用不会被动获得 bean）。
    @Test
    void registersNothingWhenFlagMissing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(EntityOperationsFactory.class))
                    .isEmpty();
        });
    }

    /// 开关显式为 false 时不注册。
    @Test
    void registersNothingWhenFlagFalse() {
        runner.withPropertyValues(ENABLED + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(EntityOperationsFactory.class))
                    .isEmpty();
        });
    }

    /// 开关取值不是字面量 `true` 时不注册：`@ConditionalOnProperty#havingValue` 是精确匹配，
    /// `1` / `TRUE` / `yes` 一律不生效。
    ///
    /// 钉这条的理由：配置写错时「静默不装配」比抛异常更难查，所以把「只认 `true`」写成契约。
    @Test
    void registersNothingForTruthyLookingValues() {
        runner.withPropertyValues(ENABLED + "=1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(EntityOperationsFactory.class))
                    .isEmpty();
        });
    }

    /// 应用自己再定义一个 `EntityOperationsFactory` 时**不会**回退——本配置没有 `@ConditionalOnMissingBean`。
    ///
    /// 已知且刻意保留的行为：契约要求「全上下文恰好一个」，由调用方保证不重复定义；本库不回退，
    /// 所以重复定义会在注入点抛 `NoUniqueBeanDefinitionException`（早失败好过静默用错的那个）。
    /// 若哪天加了 `@ConditionalOnMissingBean`，这条会红——那时要改的是契约文档，不是放宽断言。
    @Test
    void doesNotBackOffWhenApplicationDefinesItsOwnFactory() {
        runner.withPropertyValues(ENABLED + "=true")
                .withBean(
                        "applicationOwnedFactory", EntityOperationsFactory.class, () -> new EntityOperationsFactory() {
                            @Override
                            public <T> EntityOperations<T> operations(Class<T> entityClass) {
                                throw new UnsupportedOperationException("仅用于证明本配置不回退，不会被调用");
                            }
                        })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(EntityOperationsFactory.class))
                            .hasSize(2);
                });
    }
}
