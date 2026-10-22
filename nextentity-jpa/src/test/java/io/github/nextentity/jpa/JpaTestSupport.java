package io.github.nextentity.jpa;

import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.Note;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.math.BigDecimal;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/// 模块内测试的公共底座：H2 + 真实 Hibernate（版本对齐 point-mall 的 Boot 4.0.0）。
///
/// 只管「起库 / 灌固定数据 / 事务」，不抓 SQL：SQL 快照归 `nextentity-tests-jpa`
/// （spring-harness 拥有），这里不建第二套快照工具。
abstract class JpaTestSupport {

    private static SessionFactory sessionFactory;
    private static JpaMeta meta;

    @BeforeAll
    static void startDatabase() {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:jpa-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.show_sql", false);
        configuration.addAnnotatedClass(Organization.class);
        configuration.addAnnotatedClass(Customer.class);
        configuration.addAnnotatedClass(ShopOrder.class);
        sessionFactory = configuration.buildSessionFactory();
        meta = new JpaMeta(sessionFactory);

        Organization acme = new Organization(1L, "Acme");
        Organization empty = new Organization(2L, "Empty");
        inTx(entityManager -> {
            entityManager.persist(acme);
            entityManager.persist(empty);
            entityManager.persist(new Customer(10L, 1L, "Alice", acme));
            entityManager.persist(new Customer(11L, 2L, "Bob", empty));
            return null;
        });
    }

    /// 每个用例前重建订单数据：更新用例会改金额与版本号，不能让它污染其它用例的断言。
    @BeforeEach
    void resetOrders() {
        inTx(entityManager -> {
            entityManager.createQuery("delete from ShopOrder").executeUpdate();
            entityManager.persist(new ShopOrder(
                    100L,
                    entityManager.getReference(Customer.class, 10L),
                    OrderStatus.PAID,
                    new BigDecimal("12.30"),
                    new Note("vip")));
            entityManager.persist(new ShopOrder(101L, null, OrderStatus.CREATED, new BigDecimal("5.00"), null));
            return null;
        });
    }

    @AfterAll
    static void stopDatabase() {
        sessionFactory.close();
    }

    /// 共享的元模型。
    ///
    /// @return JPA 元模型
    static JpaMeta meta() {
        return meta;
    }

    /// 在事务里执行，返回结果。
    ///
    /// @param work 事务内动作
    /// @param <T>  结果类型
    /// @return 动作返回值
    static <T> T inTx(Function<EntityManager, T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        try {
            T result = work.apply(entityManager);
            transaction.commit();
            return result;
        } finally {
            entityManager.close();
        }
    }
}
