package io.github.nextentity.spring;

import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.core.repo.SimpleEntityOperationsFactory;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.exec.JpaExecutor;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/// NextEntity 的 Spring 装配：**只注册一个 bean** —— 唯一的 {@link EntityOperationsFactory}。
///
/// 为什么只有一个 bean：point-mall 的 `AppRepositoryImpl:43` 用
/// `App.getBean(EntityOperationsFactory.class)`（= `context.getBean(Class)`）取工厂，0 个会
/// `NoSuchBeanDefinitionException`，多 1 个会 `NoUniqueBeanDefinitionException`。除此之外
/// point-mall 不消费本库的任何 Spring 特性（取证见 `tmp/contract/SPRING-NEEDS.md`），所以不加
/// 事务管理器、事件发布、通用仓储、属性开关。
///
/// 开关语义：`nextentity.enabled=true` 才装配（point-mall `application.yml:141-142`）。
/// 未配置或为 `false` 时本配置不生效，context 里就没有工厂 bean。
///
/// 装配内容：JPA 后端提供四个零件（`JpaMeta` / `JpaCompiler` / `JpaExecutor` /
/// `JpaPersistExecutor`），由 core 的 `SimpleEntityOperationsFactory` 拼成工厂；
/// 查询语义与分页组成不在本模块（TEAM-CONTRACT §3）。
@AutoConfiguration
@ConditionalOnProperty(prefix = "nextentity", name = "enabled", havingValue = "true")
public class NextEntityAutoConfiguration {

    /// 唯一的实体操作工厂。
    ///
    /// `JpaMeta` 用 `EntityManagerFactory` 建一次（元模型是应用级、内部有缓存）；
    /// 编译器 / 执行器 / 写入执行器吃 Spring 注入的 `EntityManager` 代理（事务绑定那个）。
    ///
    /// @param entityManagerFactory 实体管理器工厂
    /// @param entityManager        实体管理器代理
    /// @return 工厂 bean
    @Bean
    public EntityOperationsFactory entityOperationsFactory(
            EntityManagerFactory entityManagerFactory, EntityManager entityManager) {
        JpaMeta meta = new JpaMeta(entityManagerFactory);
        return new SimpleEntityOperationsFactory<>(
                meta::entityType,
                new JpaCompiler(entityManager, meta),
                new JpaExecutor(entityManager, meta),
                entityType -> new JpaPersistExecutor<>(entityManager, meta, entityType));
    }
}
