package io.github.nextentity.jpa;

import io.github.nextentity.jpa.entity.Article;
import io.github.nextentity.jpa.entity.Customer;
import io.github.nextentity.jpa.entity.DerivedRecord;
import io.github.nextentity.jpa.entity.ElementCollectionHolder;
import io.github.nextentity.jpa.entity.Note;
import io.github.nextentity.jpa.entity.OrderStatus;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.entity.PriceWindow;
import io.github.nextentity.jpa.entity.Seal;
import io.github.nextentity.jpa.entity.SealHolder;
import io.github.nextentity.jpa.entity.Shipment;
import io.github.nextentity.jpa.entity.ShopOrder;
import io.github.nextentity.jpa.entity.Tag;
import io.github.nextentity.jpa.entity.VersionedEntry;
import io.github.nextentity.jpa.entity.VersionedLedger;
import io.github.nextentity.jpa.entity.Warehouse;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

/// 模块内测试的公共底座：H2 + 真实 Hibernate（版本对齐 point-mall 的 Boot 4.0.0）。
///
/// 只管「起库 / 灌固定数据 / 事务 / 抓 SQL」，并用 {@link #normalized(List)} 把抓到的语句
/// 归一化后做**整句**断言。
///
/// 这里也写死整条 SQL：本模块只跑 H2 + 当前 Hibernate 一条通道，语句文本是确定的。片段断言
/// （`containsIgnoringCase("not")`）会把 `so1_0.note` 这类列名当成命中，本轮已全部换成整句；
/// 跨方言稳定的语句另由 `nextentity-tests-jpa` 的快照钉住（那边跑 H2 与真 MySQL 两条通道）。
///
/// `PER_CLASS` + 实例字段是刻意的：每个测试类拿**自己的 SessionFactory 和自己的内存库**
/// （库名取自类名）。共用 `static` 字段时两个子类的 `@BeforeAll` 会互相覆盖
/// `sessionFactory`/`meta`，而 `hbm2ddl.auto=create-drop` 会在后起的那个 EMF 里重建 schema，
/// 把先跑那个类已写入的数据清掉——那是上一轮抓到过的随机失败根因。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class JpaTestSupport {

    private final SessionFactory sessionFactory;
    private final JpaMeta meta;
    private final List<String> sql = Collections.synchronizedList(new ArrayList<>());

    JpaTestSupport() {
        Configuration configuration = new Configuration();
        configuration.setProperty(
                "hibernate.connection.url",
                "jdbc:h2:mem:" + getClass().getSimpleName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.show_sql", false);
        configuration.setStatementInspector(this::recordSql);
        configuration.addAnnotatedClass(Organization.class);
        configuration.addAnnotatedClass(Customer.class);
        configuration.addAnnotatedClass(ShopOrder.class);
        configuration.addAnnotatedClass(VersionedLedger.class);
        configuration.addAnnotatedClass(VersionedEntry.class);
        configuration.addAnnotatedClass(DerivedRecord.class);
        configuration.addAnnotatedClass(Shipment.class);
        configuration.addAnnotatedClass(PriceWindow.class);
        configuration.addAnnotatedClass(Warehouse.class);
        configuration.addAnnotatedClass(Article.class);
        configuration.addAnnotatedClass(Tag.class);
        configuration.addAnnotatedClass(Seal.class);
        configuration.addAnnotatedClass(SealHolder.class);
        configuration.addAnnotatedClass(ElementCollectionHolder.class);
        this.sessionFactory = configuration.buildSessionFactory();
        this.meta = new JpaMeta(sessionFactory);
    }

    @BeforeAll
    void seedCustomers() {
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
        drainSql();
    }

    @AfterAll
    void stopDatabase() {
        sessionFactory.close();
    }

    /// 共享的元模型（本测试类专属）。
    ///
    /// @return JPA 元模型
    JpaMeta meta() {
        return meta;
    }

    /// 取出并清空已记录的 SQL，用于渲染形态断言。
    ///
    /// @return 自上次清空以来 Hibernate 实际下发的 SQL
    List<String> drainSql() {
        synchronized (sql) {
            List<String> drained = List.copyOf(sql);
            sql.clear();
            return drained;
        }
    }

    /// 归一化抓到的语句：转小写 + 连续空白压成一个空格，用于整句快照断言。
    ///
    /// @param sqls {@link #drainSql()} 的返回值
    /// @return 归一化后的语句，顺序不变
    static List<String> normalized(List<String> sqls) {
        return sqls.stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim())
                .toList();
    }

    /// 在事务里执行并提交，返回结果。
    ///
    /// `commit()` 会 flush 挂起的 persist，所以「先提交写入、再另起事务读写」时数据一定在库里。
    ///
    /// @param work 事务内动作
    /// @param <T>  结果类型
    /// @return 动作返回值
    <T> T inTx(Function<EntityManager, T> work) {
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

    private String recordSql(String statement) {
        sql.add(statement);
        return statement;
    }
}
