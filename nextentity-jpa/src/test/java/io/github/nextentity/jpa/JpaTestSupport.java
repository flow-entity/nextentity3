package io.github.nextentity.jpa;

import io.github.nextentity.api.model.Tuple;
import io.github.nextentity.core.builder.TupleValue;
import io.github.nextentity.core.spec.RowMapper;
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

    /// 多列投影取的列值：按列序排列的 `Tuple`。
    ///
    /// 行来自 `RowAssembler` 交给映射器的那一份——多列时是后端原样的 `Object[]`，用例断言的
    /// `row.get(i)` 因此仍是后端第 `i` 个 cell，测试里没有第二份装配实现可供抄。
    ///
    /// 非数组行包成「1 个 cell 的行」——与 `RowAssembler#tuple` 对多列规格的计法一致，于是「行尺寸
    /// 不对」只在 `RowAssembler` 里判定，测试不再自带第二套形态猜测；既有断言的列位置也不变。
    ///
    /// 不走辅助方法：`row -> asTuple(row)` 在 `RowMapper<?>` 的目标类型下会被推成 `RowMapper<?>`，
    /// 表达式 `row instanceof Object[]` 也就成了对捕获类型的无意义匹配。
    static final RowMapper<Tuple> ROW = row -> asTuple(row);

    /// 原样返回后端交回的行类型：`RowMapper` 的身份实现。
    ///
    /// 单列用例用它，规格里的映射器于是与「手写单列 `ValueSelection` + 取第 0 个 cell」一字不差，
    /// 装配链路上没有第二份实现掺进来；后端交回的长度 1 `Object[]` 与裸值都原样可见。
    static final RowMapper<Tuple> RAW = row -> row;

    /// 把「执行器交回的原始行列表」按 {@link #ROW} 折算成按列序的 `Tuple` 列表。
    ///
    /// 规格若直接装映射器，产物类型就是 `R`、拿不到后端原行；要让用例断言的仍是后端第 `i` 个 cell，
    /// 就得在这里过一遍同一个工具。生产装配里没有这一步，所以它只出现在测试侧。
    static List<Tuple> rows(List<?> raw) {
        return raw.stream().map(JpaTestSupport::asTuple).toList();
    }

    /// 把执行器交回的原始行列表按 {@link #SINGLE} 折成单列值列表。
    ///
    /// 同上：规格若直接装映射器，产物类型就是 `R`、拿不到后端原行；这里对原行过一次同一个取值器。
    static List<Object> values(List<?> raw) {
        return raw.stream().map(row -> SINGLE.map((Tuple) asTuple(row))).toList();
    }

    /// 把后端交回的一行包成「按列序的 `Tuple`」。
    ///
    /// 数组行**原样**做成 `TupleValue`（零拷贝，长度 2 的行仍是 2 个 cell，用例断言的 `row.get(i)`
    /// 就还是后端第 `i` 个 cell）；非数组行当成「1 个 cell 的一行」。
    private static Tuple asTuple(Object row) {
        if (row instanceof Object[] cells) {
            return new TupleValue(cells);
        }
        return new SquashedRow(row instanceof Tuple tuple ? tuple.get(0) : row);
    }

    /// 非数组行做成的 1 列元组。
    private record SquashedRow(Object value) implements Tuple {

        @Override
        public Object get(int index) {
            if (index != 0) {
                throw new IndexOutOfBoundsException("1 列行只接受下标 0，实际 " + index);
            }
            return value;
        }

        @Override
        public int size() {
            return 1;
        }
    }

    /// 单列投影取的列值。
    ///
    /// 单列的行在 `RowAssembler` 里已收成「1 个 cell」（容量 1 的 `Object[]` 或裸值都如此），
    /// 取第 0 个 cell 就是列值；`SingleTuple#get` 只认下标 0，别处越界会当场报错。
    static final RowMapper<Object> SINGLE = row -> row.get(0);

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
