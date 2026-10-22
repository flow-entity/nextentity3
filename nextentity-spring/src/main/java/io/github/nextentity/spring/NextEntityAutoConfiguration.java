package io.github.nextentity.spring;

import io.github.nextentity.core.EntityOperationsFactory;
import io.github.nextentity.jpa.JpaEntityOperationsFactory;
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
/// JPA 后端的零件装配在 `JpaEntityOperationsFactory` 里（本模块只做「拿到两个 JPA bean → 交给它」），
/// 查询语义与分页组成不在本模块（TEAM-CONTRACT §3）。
@AutoConfiguration
@ConditionalOnProperty(prefix = "nextentity", name = "enabled", havingValue = "true")
public class NextEntityAutoConfiguration {

    /// 唯一的实体操作工厂。
    ///
    /// @param entityManagerFactory 实体管理器工厂（元模型从这里解析一次并缓存）
    /// @param entityManager        实体管理器代理（Spring 注入的是事务绑定的那个）
    /// @return 工厂 bean
    @Bean
    public EntityOperationsFactory entityOperationsFactory(
            EntityManagerFactory entityManagerFactory, EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManagerFactory);
    }
}
