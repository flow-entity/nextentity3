package io.github.nextentity.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.core.repo.EntityOperations;
import io.github.nextentity.jpa.entity.Organization;
import io.github.nextentity.jpa.meta.JpaMeta;
import jakarta.persistence.AssociationOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/// `@JoinColumns(@JoinColumn(updatable = false))`（可重复注解的**容器**写法，单列时与单列
/// `@JoinColumn` 完全同义；复合外键只能用它表达）的只读标志必须与单列写法同口径：实体更新不写该
/// 外键列、条件更新 `set(关联.id)` 显式 UOE。3.2.0 的 `getAnnotation(JoinColumn.class)` 对容器写法
/// 返回 `null`，于是只读外键被静默写库（单列写法正常拒绝）——同一映射写两种声明就两种行为。
///
/// 拒绝时机分两类，用例各自钉实际发生点：**顶层关联**的复合外键在元数据期（`JpaEntityType#of` →
/// `JpaEntityAttribute`）；**`@Embedded` 里的关联叶子**（`@JoinColumns` 多列 / `@AssociationOverride`
/// 覆盖多列 / 容器单列只读）要等到写路径才判定——`update(entity)` 的 `setEmbedded` 调
/// `JpaMeta#embeddedForeignKeyUpdatable → embeddedJoinColumn` 时抛 UOE 或跳过只读列，
/// 元数据期不扫描嵌入内部（可嵌入类型不是实体，不构造 `JpaEntityAttribute`）。
///
/// 独立 `Configuration`：本用例的实体只服务这几条规则，塞进 `JpaTestSupport` 的共享配置会波及
/// 所有子类的 `hbm2ddl` 与实体集。
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JpaJoinColumnsReadOnlyTest {

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
        configuration.addAnnotatedClass(Organization.class);
        configuration.addAnnotatedClass(ContainerReadOnlyRef.class);
        configuration.addAnnotatedClass(SingularReadOnlyRef.class);
        configuration.addAnnotatedClass(CompositeTarget.class);
        configuration.addAnnotatedClass(CompositeRef.class);
        configuration.addAnnotatedClass(AssociationOverrideMultiRef.class);
        configuration.addAnnotatedClass(EmbeddedCompositeRef.class);
        configuration.addAnnotatedClass(EmbeddedContainerReadOnlyHolder.class);
        this.sessionFactory = configuration.buildSessionFactory();
        this.meta = new JpaMeta(sessionFactory);
    }

    @AfterAll
    void stopDatabase() {
        sessionFactory.close();
    }

    @BeforeEach
    void seedRows() {
        inTx(entityManager -> {
            entityManager
                    .createNativeQuery("delete from container_readonly_ref")
                    .executeUpdate();
            entityManager.createNativeQuery("delete from singular_readonly_ref").executeUpdate();
            entityManager
                    .createNativeQuery("delete from embedded_container_readonly_holder")
                    .executeUpdate();
            entityManager.createNativeQuery("delete from organization").executeUpdate();
            entityManager.persist(new Organization(1L, "one"));
            entityManager.persist(new Organization(2L, "two"));
            entityManager.persist(
                    new ContainerReadOnlyRef(1L, "orig", entityManager.getReference(Organization.class, 1L)));
            entityManager.persist(
                    new SingularReadOnlyRef(1L, "orig", entityManager.getReference(Organization.class, 1L)));
            entityManager.persist(new EmbeddedContainerReadOnlyHolder(
                    1L, "orig", new ContainerReadOnlyEmbeddedRef(entityManager.getReference(Organization.class, 1L))));
            return null;
        });
    }

    @Test
    @DisplayName("update(entity)：容器写法的只读外键不进 SET，业务列照常写（单列写法对照组同断言）")
    void entityUpdateSkipsContainerJoinColumnsReadOnlyForeignKey() {
        inTx(entityManager -> {
            ContainerReadOnlyRef row = entityManager.find(ContainerReadOnlyRef.class, 1L);
            entityManager.detach(row);
            operations(entityManager, ContainerReadOnlyRef.class)
                    .update(new ContainerReadOnlyRef(
                            1L, "renamed", entityManager.getReference(Organization.class, 2L)));
            return null;
        });

        assertThat(labelOf("container_readonly_ref")).as("可写业务列必须照常写").isEqualTo("renamed");
        assertThat(foreignKeyOf("container_readonly_ref"))
                .as("@JoinColumns(@JoinColumn(updatable=false)) 的只读外键不得被 update(entity) 写入")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("update(entity)：单列 @JoinColumn(updatable=false) 对照组（既有语义，保持不写）")
    void entityUpdateSkipsSingularJoinColumnReadOnlyForeignKey() {
        inTx(entityManager -> {
            SingularReadOnlyRef row = entityManager.find(SingularReadOnlyRef.class, 1L);
            entityManager.detach(row);
            operations(entityManager, SingularReadOnlyRef.class)
                    .update(new SingularReadOnlyRef(1L, "renamed", entityManager.getReference(Organization.class, 2L)));
            return null;
        });

        assertThat(labelOf("singular_readonly_ref")).isEqualTo("renamed");
        assertThat(foreignKeyOf("singular_readonly_ref")).as("单列写法的既有拒绝语义").isEqualTo(1L);
    }

    @Test
    @DisplayName("条件 update set(关联.id)：容器写法同样 UOE，文案与单列一致，且不落库")
    void conditionalUpdateRejectsContainerJoinColumnsReadOnlyForeignKey() {
        inTx(entityManager -> {
            assertThatThrownBy(() -> operations(entityManager, ContainerReadOnlyRef.class)
                            .update()
                            .set(EntityPath.of(ContainerReadOnlyRef::getOwner).get(Organization::getId), 2L)
                            .where(ContainerReadOnlyRef::getId)
                            .eq(1L)
                            .execute())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("条件更新不支持 @Column(updatable=false) 列: " + ContainerReadOnlyRef.class.getName()
                            + ".owner.id");
            return null;
        });

        assertThat(foreignKeyOf("container_readonly_ref")).as("被拒绝的语句不得落库").isEqualTo(1L);
    }

    @Test
    @DisplayName("复合外键（@JoinColumns 两列）：本层单列建模表达不了，元数据期显式 UOE")
    void compositeForeignKeyIsRejectedAtMetadata() {
        assertThatThrownBy(() -> meta.entityType(CompositeRef.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("复合外键关联未支持")
                .hasMessageContaining(CompositeRef.class.getName() + ".target")
                .hasMessageContaining("列数=2");
    }

    @Test
    @DisplayName("实体侧 @AssociationOverride 覆盖出两列外键：写路径 UOE（元数据期不扫嵌入内部）")
    void associationOverrideWithMultipleJoinColumnsIsRejectedOnWrite() {
        inTx(entityManager -> {
            // operations(...) 先建：它若在元数据期就抛，用例必须红在这里，而不是被下面的 assertThatThrownBy 掩盖
            EntityOperations<AssociationOverrideMultiRef> operations =
                    operations(entityManager, AssociationOverrideMultiRef.class);
            assertThatThrownBy(() -> operations.update(
                            new AssociationOverrideMultiRef(1L, "renamed", new CompositeEmbeddedTargetRef(null))))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("复合外键关联未支持")
                    .hasMessageContaining(AssociationOverrideMultiRef.class.getName() + ".ref.target")
                    .hasMessageContaining("@AssociationOverride.joinColumns")
                    .hasMessageContaining("列数=2");
            return null;
        });
    }

    @Test
    @DisplayName("嵌入内关联的 @JoinColumns 两列：写路径 UOE（嵌入内部不走元数据期属性解析）")
    void embeddedAssociationWithMultipleJoinColumnsIsRejectedOnWrite() {
        inTx(entityManager -> {
            EntityOperations<EmbeddedCompositeRef> operations = operations(entityManager, EmbeddedCompositeRef.class);
            assertThatThrownBy(() ->
                            operations.update(new EmbeddedCompositeRef(1L, "renamed", new CompositeInnerRef(null))))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("复合外键关联未支持")
                    .hasMessageContaining(EmbeddedCompositeRef.class.getName() + ".ref.target")
                    .hasMessageContaining("@JoinColumns")
                    .hasMessageNotContaining("@AssociationOverride")
                    .hasMessageContaining("列数=2");
            return null;
        });
    }

    @Test
    @DisplayName("嵌入内关联的容器单列只读外键：update(entity) 不进 SET、库内值不变")
    void entityUpdateSkipsEmbeddedContainerReadOnlyForeignKey() {
        inTx(entityManager -> {
            EmbeddedContainerReadOnlyHolder row = entityManager.find(EmbeddedContainerReadOnlyHolder.class, 1L);
            entityManager.detach(row);
            operations(entityManager, EmbeddedContainerReadOnlyHolder.class)
                    .update(new EmbeddedContainerReadOnlyHolder(
                            1L,
                            "renamed",
                            new ContainerReadOnlyEmbeddedRef(entityManager.getReference(Organization.class, 2L))));
            return null;
        });

        assertThat(labelOf("embedded_container_readonly_holder"))
                .as("可写业务列必须照常写")
                .isEqualTo("renamed");
        assertThat(columnValueOf("embedded_container_readonly_holder", "locked_owner_id"))
                .as("@Embedded 里 @JoinColumns(@JoinColumn(updatable=false)) 的只读外键不得被 update(entity) 写入")
                .isEqualTo(1L);
    }

    private <T> EntityOperations<T> operations(EntityManager entityManager, Class<T> type) {
        return new JpaEntityOperationsFactory(entityManager, entityManager.getEntityManagerFactory()).operations(type);
    }

    private String labelOf(String table) {
        return inTx(entityManager -> (String)
                entityManager.createNativeQuery("select label from " + table).getSingleResult());
    }

    private Long foreignKeyOf(String table) {
        return columnValueOf(table, "owner_id");
    }

    private Long columnValueOf(String table, String column) {
        return inTx(entityManager -> ((Number) entityManager
                        .createNativeQuery("select " + column + " from " + table)
                        .getSingleResult())
                .longValue());
    }

    private <T> T inTx(Function<EntityManager, T> work) {
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

    /// 容器写法的只读外键：`@JoinColumns(@JoinColumn(...))` 是单列 `@JoinColumn` 的等价声明。
    @Entity
    @Table(name = "container_readonly_ref")
    public static class ContainerReadOnlyRef {

        @Id
        private Long id;

        @Column(name = "label")
        private String label;

        @ManyToOne
        @JoinColumns(@JoinColumn(name = "owner_id", updatable = false))
        private Organization owner;

        public ContainerReadOnlyRef() {}

        public ContainerReadOnlyRef(Long id, String label, Organization owner) {
            this.id = id;
            this.label = label;
            this.owner = owner;
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public Organization getOwner() {
            return owner;
        }
    }

    /// 单列写法对照组。
    @Entity
    @Table(name = "singular_readonly_ref")
    public static class SingularReadOnlyRef {

        @Id
        private Long id;

        @Column(name = "label")
        private String label;

        @ManyToOne
        @JoinColumn(name = "owner_id", updatable = false)
        private Organization owner;

        public SingularReadOnlyRef() {}

        public SingularReadOnlyRef(Long id, String label, Organization owner) {
            this.id = id;
            this.label = label;
            this.owner = owner;
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public Organization getOwner() {
            return owner;
        }
    }

    /// 复合外键的目标：`@IdClass` 复合主键。
    @Entity
    @Table(name = "composite_target")
    @IdClass(CompositeKey.class)
    public static class CompositeTarget {

        @Id
        private Long a;

        @Id
        private Long b;

        public CompositeTarget() {}
    }

    /// `@IdClass` 复合主键。
    public static class CompositeKey implements Serializable {

        private Long a;

        private Long b;

        public CompositeKey() {}

        @Override
        public boolean equals(Object other) {
            return other instanceof CompositeKey key && Objects.equals(a, key.a) && Objects.equals(b, key.b);
        }

        @Override
        public int hashCode() {
            return Objects.hash(a, b);
        }
    }

    /// 复合外键关联：两列只能写成 `@JoinColumns`，本层的单列 `joinColumnUpdatable` 建模不了。
    @Entity
    @Table(name = "composite_ref")
    public static class CompositeRef {

        @Id
        private Long id;

        @ManyToOne
        @JoinColumns({
            @JoinColumn(name = "t_a", referencedColumnName = "a"),
            @JoinColumn(name = "t_b", referencedColumnName = "b")
        })
        private CompositeTarget target;

        public CompositeRef() {}
    }

    /// 嵌入内的 to-one：本身不写 `@JoinColumn`，由实体侧 `@AssociationOverride` 给列。
    @Embeddable
    public static class CompositeEmbeddedTargetRef {

        @ManyToOne
        private CompositeTarget target;

        public CompositeEmbeddedTargetRef() {}

        public CompositeEmbeddedTargetRef(CompositeTarget target) {
            this.target = target;
        }

        public CompositeTarget getTarget() {
            return target;
        }
    }

    /// 实体侧 `@AssociationOverride` 用**两列**覆盖嵌入内关联：本层单列建模表达不了，写路径 UOE。
    @Entity
    @Table(name = "association_override_multi_ref")
    public static class AssociationOverrideMultiRef {

        @Id
        private Long id;

        @Column(name = "label")
        private String label;

        @Embedded
        @AssociationOverride(
                name = "target",
                joinColumns = {
                    @JoinColumn(name = "ao_t_a", referencedColumnName = "a"),
                    @JoinColumn(name = "ao_t_b", referencedColumnName = "b")
                })
        private CompositeEmbeddedTargetRef ref;

        public AssociationOverrideMultiRef() {}

        public AssociationOverrideMultiRef(Long id, String label, CompositeEmbeddedTargetRef ref) {
            this.id = id;
            this.label = label;
            this.ref = ref;
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public CompositeEmbeddedTargetRef getRef() {
            return ref;
        }
    }

    /// 嵌入内的 to-one 直接写 `@JoinColumns` 两列（多列外键）：写路径 UOE。
    @Embeddable
    public static class CompositeInnerRef {

        @ManyToOne
        @JoinColumns({
            @JoinColumn(name = "ei_t_a", referencedColumnName = "a"),
            @JoinColumn(name = "ei_t_b", referencedColumnName = "b")
        })
        private CompositeTarget target;

        public CompositeInnerRef() {}

        public CompositeInnerRef(CompositeTarget target) {
            this.target = target;
        }

        public CompositeTarget getTarget() {
            return target;
        }
    }

    /// 嵌入内关联直接写 `@JoinColumns` 两列的实体。
    @Entity
    @Table(name = "embedded_composite_ref")
    public static class EmbeddedCompositeRef {

        @Id
        private Long id;

        @Column(name = "label")
        private String label;

        @Embedded
        private CompositeInnerRef ref;

        public EmbeddedCompositeRef() {}

        public EmbeddedCompositeRef(Long id, String label, CompositeInnerRef ref) {
            this.id = id;
            this.label = label;
            this.ref = ref;
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public CompositeInnerRef getRef() {
            return ref;
        }
    }

    /// 嵌入内关联的**容器写法单列只读**外键：`@JoinColumns(@JoinColumn(updatable = false))`。
    /// `update(entity)` 经 `JpaMeta#embeddedForeignKeyUpdatable → embeddedJoinColumn` 的叶子回退判只读，
    /// 这条路径此前零覆盖（顶层关联的同类判定走 `JpaEntityAttribute`）。
    @Embeddable
    public static class ContainerReadOnlyEmbeddedRef {

        @ManyToOne
        @JoinColumns(@JoinColumn(name = "locked_owner_id", updatable = false))
        private Organization owner;

        public ContainerReadOnlyEmbeddedRef() {}

        public ContainerReadOnlyEmbeddedRef(Organization owner) {
            this.owner = owner;
        }

        public Organization getOwner() {
            return owner;
        }
    }

    /// 嵌入内容器单列只读外键的实体。
    @Entity
    @Table(name = "embedded_container_readonly_holder")
    public static class EmbeddedContainerReadOnlyHolder {

        @Id
        private Long id;

        @Column(name = "label")
        private String label;

        @Embedded
        private ContainerReadOnlyEmbeddedRef ref;

        public EmbeddedContainerReadOnlyHolder() {}

        public EmbeddedContainerReadOnlyHolder(Long id, String label, ContainerReadOnlyEmbeddedRef ref) {
            this.id = id;
            this.label = label;
            this.ref = ref;
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public ContainerReadOnlyEmbeddedRef getRef() {
            return ref;
        }
    }
}
