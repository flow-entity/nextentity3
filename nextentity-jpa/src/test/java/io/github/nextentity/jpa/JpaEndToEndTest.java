package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.core.spec.AttributePath;
import io.github.nextentity.core.spec.Comparison;
import io.github.nextentity.core.spec.ComparisonOperator;
import io.github.nextentity.core.spec.EntitySelection;
import io.github.nextentity.core.spec.NullCheck;
import io.github.nextentity.core.spec.PathProjection;
import io.github.nextentity.core.spec.QuerySpec;
import io.github.nextentity.core.spec.Spec;
import io.github.nextentity.core.spec.ValueSelection;
import io.github.nextentity.core.spec.Window;
import io.github.nextentity.jpa.compile.JpaCompiler;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.Note;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.exec.JpaExecutor;
import io.github.nextentity.jpa.exec.JpaPersistExecutor;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// 端到端：`QuerySpec` → Criteria（JpaCompiler）→ H2 真实执行（JpaExecutor）。
///
/// SQL 文本快照归 `nextentity-tests-jpa`（spring-harness 拥有），这里只验证行为与语义：
/// 等值查询、嵌套路径的 LEFT JOIN、count / exists、单列投影、`@Version` 更新与冲突。
class JpaEndToEndTest {

    private static SessionFactory sessionFactory;
    private static JpaMeta meta;

    @BeforeAll
    static void setUp() {
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
        Customer alice = new Customer(10L, 1L, "Alice", acme);
        Customer bob = new Customer(11L, 2L, "Bob", empty);
        inTx(entityManager -> {
            entityManager.persist(acme);
            entityManager.persist(empty);
            entityManager.persist(alice);
            entityManager.persist(bob);
            entityManager.persist(
                    new ShopOrder(100L, alice, OrderStatus.PAID, new BigDecimal("12.30"), new Note("vip")));
            entityManager.persist(new ShopOrder(101L, null, OrderStatus.CREATED, new BigDecimal("5.00"), null));
            return null;
        });
    }

    @AfterAll
    static void tearDown() {
        sessionFactory.close();
    }

    @Test
    void entityListWithEquality() {
        QuerySpec spec = query(
                new Comparison(AttributePath.of(ShopOrder.class, "status"), ComparisonOperator.EQ, OrderStatus.PAID));
        List<ShopOrder> orders = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(spec)));
        assertThat(orders).extracting(ShopOrder::getId).containsExactly(100L);
    }

    @Test
    void nestedPathUsesLeftJoin() {
        QuerySpec byOrg = query(
                new Comparison(AttributePath.of(ShopOrder.class, "customer", "orgId"), ComparisonOperator.EQ, 1L));
        List<ShopOrder> matched = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(byOrg)));
        assertThat(matched).extracting(ShopOrder::getId).containsExactly(100L);

        // customer 为空的订单只有在 LEFT JOIN 下才会被 IS NULL 命中；INNER JOIN 会漏掉它
        QuerySpec orphan = query(new NullCheck(AttributePath.of(ShopOrder.class, "customer", "orgId"), false));
        List<ShopOrder> unmatched = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(orphan)));
        assertThat(unmatched).extracting(ShopOrder::getId).containsExactly(101L);
    }

    @Test
    void countAndExists() {
        QuerySpec paid = query(
                new Comparison(AttributePath.of(ShopOrder.class, "status"), ComparisonOperator.EQ, OrderStatus.PAID));
        inTx(entityManager -> {
            JpaCompiler compiler = compiler(entityManager);
            JpaExecutor executor = executor(entityManager);
            assertThat(executor.count(compiler.compile(paid.forCount()))).isEqualTo(1L);
            assertThat(executor.exists(compiler.compile(paid.forExists()))).isTrue();
            assertThat(executor.count(compiler.compile(query(null).forCount()))).isEqualTo(2L);
            return null;
        });
    }

    @Test
    void singleColumnProjection() {
        QuerySpec ids = new QuerySpec(
                ShopOrder.class,
                new ValueSelection(List.of(new PathProjection(AttributePath.of(ShopOrder.class, "id"))), false),
                null,
                List.of(),
                List.of(),
                new Window(0, 10),
                null);
        List<Long> result = inTx(entityManager ->
                executor(entityManager).list(compiler(entityManager).compile(ids)));
        assertThat(result).containsExactlyInAnyOrder(100L, 101L);
    }

    @Test
    void updateBumpsVersionAndDetectsConflict() {
        inTx(entityManager -> {
            ShopOrder order = entityManager.find(ShopOrder.class, 100L);
            entityManager.detach(order);
            order.setAmount(new BigDecimal("99.00"));
            persister(entityManager).update(order);
            assertThat(order.getVersion()).isEqualTo(1);
            return null;
        });
        inTx(entityManager -> {
            ShopOrder reloaded = entityManager.find(ShopOrder.class, 100L);
            assertThat(reloaded.getAmount()).isEqualByComparingTo("99.00");
            assertThat(reloaded.getVersion()).isEqualTo(1);
            return null;
        });
        inTx(entityManager -> {
            ShopOrder stale = entityManager.find(ShopOrder.class, 100L);
            entityManager.detach(stale);
            stale.setVersion(0);
            assertThatThrownBy(() -> persister(entityManager).update(stale))
                    .isInstanceOf(OptimisticLockException.class);
            return null;
        });
    }

    private static QuerySpec query(Spec where) {
        return new QuerySpec(ShopOrder.class, new EntitySelection(), where, List.of(), List.of(), Window.NONE, null);
    }

    private static JpaCompiler compiler(EntityManager entityManager) {
        return new JpaCompiler(entityManager, meta);
    }

    private static JpaExecutor executor(EntityManager entityManager) {
        return new JpaExecutor(entityManager, meta);
    }

    private static JpaPersistExecutor<ShopOrder> persister(EntityManager entityManager) {
        return new JpaPersistExecutor<>(entityManager, meta, ShopOrder.class);
    }

    private static <T> T inTx(Function<EntityManager, T> work) {
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
