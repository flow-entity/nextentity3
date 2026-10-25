package io.github.nextentity.tests.jpa.support;

import io.github.nextentity.testing.NullOrdering;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

/// Hibernate 测试装置：建表、给 `EntityManager`、抓 SQL。
///
/// 只为测试服务，不引入 Spring，也不依赖 nextentity 的任何实现类——这样 SQL 快照用例
/// 既能独立证明工具本身可用，也能被 `nextentity-jpa` 的执行器直接复用。
///
/// 两种连接：默认的 H2 内存库（MySQL 兼容模式，与 point-mall 集成测试
/// `application-integration-tests.yml` 同款），或调用方给的 JDBC 连接（真方言，见
/// [MySqlL4Container]）。**默认路径**不含方言分支：方言由 Hibernate 按 JDBC 元数据自选。
///
/// 唯一例外是**显式 opt-in** 的 H2 兼容模式开关 `-Dnextentity.h2mode=postgresql`（见
/// [H2Mode]）：它把同一个内存库换成 H2 的 PostgreSQL 兼容模式并钉上 `PostgreSQLDialect`，
/// 用来暴露「只在 MySQL 方言下成立」的假设（NULL 排序、类型、布尔、分页语法）。
/// 不设该属性时 URL 与方言解析路径与从前逐字节相同。
public final class HibernateHarness implements AutoCloseable {

    /// H2 兼容模式的系统属性名。
    ///
    /// 只认系统属性、**不**读环境变量：环境变量是隐式全局开关，机器上留一个
    /// `NEXTENTITY_H2MODE=postgresql` 就会让默认通道静默换方言，而 `-DskipITs` 的本地跑法尤其隐蔽。
    /// surefire 的 fork 会继承 Maven 命令行上的 `-D`，所以系统属性足够。
    public static final String MODE_PROPERTY = "nextentity.h2mode";

    private final SessionFactory sessionFactory;
    private final SqlRecorder recorder;
    private final EntityManager entityManager;

    /// H2 的兼容模式：本装置唯一的方言开关。
    public enum H2Mode {

        /// MySQL 兼容模式（缺省）：与 point-mall 集成测试同款，`NULL` 在 `ASC` 时排最前。
        MYSQL,

        /// PostgreSQL 兼容模式：`DATABASE_TO_LOWER=TRUE` + `DEFAULT_NULL_ORDERING=HIGH`
        /// （`NULL` 在 `ASC` 时排最后，与真 PostgreSQL 一致），方言钉成 `PostgreSQLDialect`。
        POSTGRESQL
    }

    private HibernateHarness(SessionFactory sessionFactory, SqlRecorder recorder) {
        this.sessionFactory = sessionFactory;
        this.recorder = recorder;
        this.entityManager = sessionFactory.createEntityManager();
    }

    /// 启动一套只含给定实体的 Hibernate（H2 内存库，建表后自动销毁）。
    ///
    /// @param databaseName  H2 内存库名；同一测试类复用时保持稳定，否则每次都要重建库
    /// @param entityClasses 参与映射的实体类
    /// @return 测试装置：用完必须 `close()`，否则内存库与 SessionFactory 都留着
    public static HibernateHarness start(String databaseName, Class<?>... entityClasses) {
        H2Mode mode = resolveMode(System.getProperty(MODE_PROPERTY));
        return build(jdbcUrl(mode, databaseName), "sa", null, dialectProperties(mode), entityClasses);
    }

    /// 模式 → JDBC URL 的**纯函数**（包内可见，供用例直接断言 URL 形状）。
    ///
    /// 抽出来的理由：这段字符串是「默认通道逐字节不变」这条承诺的载体，也是「PG 模式真的切到
    /// 了 PG 兼容语义」的唯一证据——此前只有手工跑 opt-in 通道才能看出它写错了，
    /// 而删掉 `DEFAULT_NULL_ORDERING=HIGH` 只会让排序差分在 PG 模式里假红。
    ///
    /// @param mode         H2 兼容模式
    /// @param databaseName H2 内存库名（同一进程多实例时按名字隔离）
    /// @return 该模式下的 JDBC URL
    static String jdbcUrl(H2Mode mode, String databaseName) {
        return switch (mode) {
            case MYSQL -> "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
            case POSTGRESQL ->
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        };
    }

    /// 模式 → 额外的 Hibernate 属性（纯函数，包内可见）。
    ///
    /// MySQL 模式不钉方言（H2 的 MySQL 兼容模式由 Hibernate 自动识别，钉了反而与默认通道不一致）。
    ///
    /// @param mode H2 兼容模式
    /// @return 需要额外设置的属性，可能为空
    static Map<String, String> dialectProperties(H2Mode mode) {
        return switch (mode) {
            case MYSQL -> Map.of();
            case POSTGRESQL -> Map.of("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        };
    }

    /// 把属性值解析成模式：缺省/空白 → [H2Mode#MYSQL]，未知取值直接抛 `IllegalArgumentException`。
    ///
    /// 「未知取值直接失败」是刻意的：静默回退到 `mysql` 会让打错一个字母的
    /// `-Dnextentity.h2mode=postgre` 变成「什么都没跑，但构建是绿的」。
    ///
    /// @param property 系统属性原值，可为 `null`
    /// @return 解析出的模式，非 `null`
    /// @throws IllegalArgumentException 取值既不是 `mysql` 也不是 `postgresql`
    public static H2Mode resolveMode(String property) {
        if (property == null || property.isBlank()) {
            return H2Mode.MYSQL;
        }
        return switch (property.trim().toLowerCase(Locale.ROOT)) {
            case "mysql" -> H2Mode.MYSQL;
            case "postgresql" -> H2Mode.POSTGRESQL;
            default ->
                throw new IllegalArgumentException(
                        "未知的 " + MODE_PROPERTY + ": " + property + "（可选 mysql / postgresql）");
        };
    }

    /// 当前 H2 兼容模式下，后端在 `ASC` 时把 `null` 放在哪一侧。
    ///
    /// 一致性基类的排序/分组/关联差分 oracle 都读这一处（`ConsistencyTestBase#nullOrdering()` 的
    /// 后端实现），这样「同一套用例换方言」不需要给每个用例类各写一套期望值。
    ///
    /// @return 升序时 `null` 的相对位置
    public static NullOrdering nullOrdering() {
        return nullOrdering(resolveMode(System.getProperty(MODE_PROPERTY)));
    }

    /// 模式 → `null` 排序口径的纯映射（方言事实，与属性怎么来无关，便于直接断言）。
    ///
    /// @param mode 模式，非 `null`
    /// @return 该模式下升序时 `null` 的相对位置
    public static NullOrdering nullOrdering(H2Mode mode) {
        return mode == H2Mode.POSTGRESQL ? NullOrdering.NULLS_LAST : NullOrdering.NULLS_FIRST;
    }

    /// 启动一套只含给定实体的 Hibernate，连到调用方给的库（真方言，例如容器起的 MySQL）。
    ///
    /// 方言**不**硬编码：Hibernate 按 JDBC 元数据自动选，所以同一个装置既能连 H2 也能连 MySQL，
    /// L4 的「同一套用例换方言」才不需要两套接线。
    ///
    /// 建表仍走 `hbm2ddl`（`create-drop`），与 H2 侧由同一份实体元数据生成——手写 DDL 会把
    /// 「方言差异」和「DDL 写错」混在一起，对拍就说不清是谁的问题。
    ///
    /// @param jdbcUrl       JDBC 连接串，非 `null`
    /// @param username      数据库账号，非 `null`
    /// @param password      数据库口令，非 `null`（无口令传空串）
    /// @param entityClasses 参与映射的实体类
    /// @return 测试装置：用完必须 `close()`
    public static HibernateHarness start(String jdbcUrl, String username, String password, Class<?>... entityClasses) {
        return build(jdbcUrl, username, password, entityClasses);
    }

    private static HibernateHarness build(String jdbcUrl, String username, String password, Class<?>... entityClasses) {
        return build(jdbcUrl, username, password, Map.of(), entityClasses);
    }

    /// 建装置的唯一出口：`extraProperties` 用来钉方言（H2 兼容模式下的 `hibernate.dialect`）。
    private static HibernateHarness build(
            String jdbcUrl,
            String username,
            String password,
            Map<String, String> extraProperties,
            Class<?>... entityClasses) {
        SqlRecorder recorder = new SqlRecorder();
        Configuration configuration = new Configuration()
                .setProperty("hibernate.connection.url", jdbcUrl)
                .setProperty("hibernate.connection.username", username)
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setStatementInspector(recorder);
        extraProperties.forEach(configuration::setProperty);
        if (password != null) {
            configuration.setProperty("hibernate.connection.password", password);
        }
        for (Class<?> entityClass : entityClasses) {
            configuration.addAnnotatedClass(entityClass);
        }
        return new HibernateHarness(configuration.buildSessionFactory(), recorder);
    }

    /// 当前测试线程独占的 `EntityManager`。
    ///
    /// @return 实例独占的 `EntityManager`，由 `close()` 一并关闭
    public EntityManager entityManager() {
        return entityManager;
    }

    /// 实体管理器工厂（JPA 元模型从它解析，见 `JpaMeta`）。
    ///
    /// @return 同一个 `SessionFactory`（它同时充当 `EntityManagerFactory`）
    public EntityManagerFactory entityManagerFactory() {
        return sessionFactory;
    }

    /// 在事务里跑一段写入，并在正常结束时提交。
    ///
    /// @param work 事务体，非 `null`；正常返回即提交，抛异常则不提交
    public void inTransaction(Consumer<EntityManager> work) {
        entityManager.getTransaction().begin();
        work.accept(entityManager);
        entityManager.getTransaction().commit();
    }

    /// 在事务里跑一段查询并返回结果（悲观锁这类语句必须在事务内执行）。
    ///
    /// @param work 事务体，非 `null`
    /// @param <R>  结果类型
    /// @return 事务体的返回值，原样透传
    public <R> R inTransaction(Supplier<R> work) {
        entityManager.getTransaction().begin();
        R result = work.get();
        entityManager.getTransaction().commit();
        return result;
    }

    /// 取走已抓到的 SQL，并清空账本。
    ///
    /// @return 按执行顺序排列的 SQL；取走后账本清空
    public List<String> drainSql() {
        return recorder.drain();
    }

    /// 清空 SQL 账本（例如跳过建表 DDL 后开始记一次查询）。
    public void clearSql() {
        recorder.clear();
    }

    @Override
    public void close() {
        entityManager.close();
        sessionFactory.close();
    }
}
