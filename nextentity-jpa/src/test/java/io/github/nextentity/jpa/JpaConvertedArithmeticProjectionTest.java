package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.api.Path;
import io.github.nextentity.core.repo.EntityOperations;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// `@Convert` 数值列上的算术投影返回**转换器的数据库类型**，这是 `docs/USAGE.md` §7 已披露的已知限制：
/// `AttributeConverter` 不声明「加性是否保值」，非保值转换器上的算值不落在属性值域内，所以不反转换。
/// 本类钉住现状——静态类型是属性类型 `Long`，运行期是 `BigDecimal`（`for (Long v : rows)` 会当场
/// `ClassCastException`），而纯列投影与 `max()` 仍按列映射反转换回 `Long`。
///
/// 独立的 `Configuration`：夹具只服务于这条口径，塞进 `JpaTestSupport` 的共享配置会波及所有子类的
/// `hbm2ddl` 与实体集。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JpaConvertedArithmeticProjectionTest {

    private SessionFactory sessionFactory;

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
        configuration.addAnnotatedClass(ConvertedAmount.class);
        this.sessionFactory = configuration.buildSessionFactory();
        inTx(entityManager -> {
            entityManager.persist(new ConvertedAmount(1L, 100L));
            entityManager.persist(new ConvertedAmount(2L, 200L));
            return null;
        });
    }

    @AfterAll
    void stopDatabase() {
        sessionFactory.close();
    }

    @Test
    @DisplayName("算术投影（amount + 1）：运行期是转换器的数据库类型 BigDecimal，不是属性类型 Long")
    void arithmeticProjectionYieldsConverterDatabaseType() {
        List<?> rows = inTx(entityManager -> operations(entityManager)
                .query()
                .select(Path.of(ConvertedAmount::getAmount).add(1L))
                .list());

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(Object::getClass).containsOnly(BigDecimal.class);
        assertThat(values(rows).stream()
                        .map(row -> ((BigDecimal) row).toPlainString())
                        .toList())
                .containsExactlyInAnyOrder("101", "201");
    }

    @Test
    @DisplayName("对照：同列的纯列投影按列映射反转换，运行期是属性类型 Long")
    void plainColumnProjectionYieldsAttributeType() {
        List<?> rows = inTx(entityManager -> operations(entityManager)
                .query()
                .select(Path.of(ConvertedAmount::getAmount))
                .list());

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(Object::getClass).containsOnly(Long.class);
        assertThat(values(rows)).containsExactlyInAnyOrder(100L, 200L);
    }

    @Test
    @DisplayName("对照：max() 按列映射反转换，运行期是属性类型 Long")
    void maxAggregateYieldsAttributeType() {
        List<?> rows = inTx(entityManager -> operations(entityManager)
                .query()
                .select(Path.of(ConvertedAmount::getAmount).max())
                .list());

        assertThat(rows).hasSize(1);
        assertThat(rows).extracting(Object::getClass).containsOnly(Long.class);
        assertThat(values(rows)).containsExactly(200L);
    }

    /// 行元素按 `Object` 取出：`list()` 的静态元素类型是属性类型（`Long`），断言里若沿用它，
    /// 迭代就会插入 `checkcast`，把「运行期类型不对」变成 `ClassCastException`——那是调用点的
    /// 症状，不是本类要钉的事实。
    private static List<Object> values(List<?> rows) {
        return rows.stream().map(row -> (Object) row).toList();
    }

    private <T> T inTx(Function<EntityManager, T> work) {
        EntityManager entityManager = sessionFactory.createEntityManager();
        try {
            entityManager.getTransaction().begin();
            T result = work.apply(entityManager);
            entityManager.getTransaction().commit();
            return result;
        } finally {
            entityManager.close();
        }
    }

    private static EntityOperations<ConvertedAmount> operations(EntityManager entityManager) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory())
                .operations(ConvertedAmount.class);
    }

    /// `@Convert` 数值列：属性类型 `Long`，数据库形态 `BigDecimal`（在受支持类型清单内，元数据期不拒绝）。
    @Entity
    @Table(name = "converted_amount")
    public static class ConvertedAmount {

        @Id
        private Long id;

        @Convert(converter = AmountConverter.class)
        @Column(name = "amount", columnDefinition = "numeric(19,0)")
        private Long amount;

        public ConvertedAmount() {}

        public ConvertedAmount(Long id, Long amount) {
            this.id = id;
            this.amount = amount;
        }

        public Long getId() {
            return id;
        }

        public Long getAmount() {
            return amount;
        }
    }

    /// 非保值转换器：`Long` ↔ `BigDecimal` 的恒等映射，`amount + 1` 在数据库侧算出 `BigDecimal`。
    @Converter
    public static class AmountConverter implements AttributeConverter<Long, BigDecimal> {

        @Override
        public BigDecimal convertToDatabaseColumn(Long attribute) {
            return attribute == null ? null : BigDecimal.valueOf(attribute);
        }

        @Override
        public Long convertToEntityAttribute(BigDecimal dbData) {
            return dbData == null ? null : dbData.longValueExact();
        }
    }
}
