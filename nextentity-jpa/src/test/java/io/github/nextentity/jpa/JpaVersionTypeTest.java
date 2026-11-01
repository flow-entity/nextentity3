package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jpa.entity.InsertableFalseVersioned;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Timestamp;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// `@Version` 的类型集在**元数据期**校验：只放行 `int` / `long` / `short`（含包装类型）。
///
/// 拒绝理由与 JDBC 后端同口径（`JdbcEntityRules.VERSION_UNSUPPORTED`，USAGE §13.4.3）：版本递增只实现
/// 这三个类型族，别的类型到了 `update(entity)` 拼 `SET` 时才抛，等于「读能用、写不能用」——同一实体
/// 在 JPA 后端 `query().list()` / `count()` / 条件更新全部可用，只有 `update(entity)` 炸，缺陷因此
/// 藏得很深。fail-fast 把拒绝挪到解析期，两后端对同一映射给出同一个答案。
///
/// 独立的 `Configuration`：本用例的实体只服务于这一条规则，塞进 `JpaTestSupport` 的共享配置会
/// 波及所有子类的 `hbm2ddl` 与实体集。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JpaVersionTypeTest {

    private SessionFactory sessionFactory;
    private JpaMeta meta;

    @BeforeAll
    void startDatabase() {
        Configuration configuration = new Configuration();
        configuration.setProperty(
                "hibernate.connection.url",
                "jdbc:h2:mem:" + getClass().getSimpleName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.show_sql", false);
        configuration.addAnnotatedClass(TimestampVersioned.class);
        configuration.addAnnotatedClass(IntegerVersioned.class);
        configuration.addAnnotatedClass(ShortVersioned.class);
        configuration.addAnnotatedClass(ShortPrimitiveVersioned.class);
        configuration.addAnnotatedClass(InsertableFalseVersioned.class);
        this.sessionFactory = configuration.buildSessionFactory();
        this.meta = new JpaMeta(sessionFactory);
    }

    @AfterAll
    void stopDatabase() {
        sessionFactory.close();
    }

    @Test
    @DisplayName("@Version Timestamp：元数据期拒绝，文案与 JDBC 后端逐字一致")
    void timestampVersionIsRejected() {
        assertThatThrownBy(() -> meta.entityType(TimestampVersioned.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(
                        "@Version 只支持 int / long / short（含包装类型）: " + TimestampVersioned.class.getName() + ".version");
    }

    @Test
    @DisplayName("对照：@Version Integer 照常解析（拒绝只针对三个数值类型族之外的版本）")
    void integerVersionIsStillAccepted() {
        assertThat(meta.entityType(IntegerVersioned.class).version()).isNotNull();
    }

    @Test
    @DisplayName("对照：@Version Short 照常解析（类型集的包装分支各自钉一条）")
    void shortVersionIsStillAccepted() {
        assertThat(meta.entityType(ShortVersioned.class).version()).isNotNull();
    }

    @Test
    @DisplayName("对照：@Version short（原始类型）照常解析")
    void shortPrimitiveVersionIsStillAccepted() {
        assertThat(meta.entityType(ShortPrimitiveVersioned.class).version()).isNotNull();
    }

    @Test
    @DisplayName("对照：@Version + @Column(insertable=false) 的拒绝仍在（两条拒绝互不吞掉）")
    void insertableFalseVersionIsStillRejected() {
        assertThatThrownBy(() -> meta.entityType(InsertableFalseVersioned.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("@Version 列不支持 @Column(insertable=false)"
                        + "（版本初值由本层写入，insertable 只约束业务列）: "
                        + InsertableFalseVersioned.class.getName() + ".version");
    }

    /// `@Version java.sql.Timestamp`：JDBC 后端拒绝的形态，JPA 侧同样必须在元数据期拒绝。
    @Entity
    @Table(name = "timestamp_versioned")
    public static class TimestampVersioned {

        @Id
        private Long id;

        @Version
        private Timestamp version;

        public TimestampVersioned() {}

        public Long getId() {
            return id;
        }

        public Timestamp getVersion() {
            return version;
        }
    }

    /// 类型集内的对照：`@Version Integer` 必须照常解析。
    @Entity
    @Table(name = "integer_versioned")
    public static class IntegerVersioned {

        @Id
        private Long id;

        @Version
        private Integer version;

        public IntegerVersioned() {}

        public Long getId() {
            return id;
        }

        public Integer getVersion() {
            return version;
        }
    }

    /// 类型集内的对照：`@Version Short` 必须照常解析。
    @Entity
    @Table(name = "short_versioned")
    public static class ShortVersioned {

        @Id
        private Long id;

        @Version
        private Short version;

        public ShortVersioned() {}

        public Long getId() {
            return id;
        }

        public Short getVersion() {
            return version;
        }
    }

    /// 类型集内的对照：`@Version short`（原始类型）必须照常解析——包装与原始两条分支各钉一条，
    /// 否则 `javaType == short.class` 那半条判据被删掉也没有用例变红。
    @Entity
    @Table(name = "short_primitive_versioned")
    public static class ShortPrimitiveVersioned {

        @Id
        private Long id;

        @Version
        private short version;

        public ShortPrimitiveVersioned() {}

        public Long getId() {
            return id;
        }

        public short getVersion() {
            return version;
        }
    }
}
