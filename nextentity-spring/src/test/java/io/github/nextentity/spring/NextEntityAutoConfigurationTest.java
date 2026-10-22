package io.github.nextentity.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.nextentity.core.EntityOperationsFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/// 自动配置的门禁与 bean 注册：`nextentity.enabled` 是开关，开时**恰好一个**工厂 bean。
///
/// 这两个事实都是 point-mall 的硬需求（`SPRING-NEEDS.md` N2 / N5）：0 个 bean 会让
/// `App.getBean(EntityOperationsFactory.class)` 抛 `NoSuchBeanDefinitionException`，
/// 2 个会抛 `NoUniqueBeanDefinitionException`。
class NextEntityAutoConfigurationTest {

    private static final String ENABLED = "nextentity.enabled";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NextEntityAutoConfiguration.class))
            .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
            .withBean(EntityManager.class, () -> mock(EntityManager.class));

    /// 开关打开时注册唯一一个工厂 bean。
    @Test
    void registersExactlyOneFactoryBeanWhenEnabled() {
        runner.withPropertyValues(ENABLED + "=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(EntityOperationsFactory.class))
                    .hasSize(1);
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
}
