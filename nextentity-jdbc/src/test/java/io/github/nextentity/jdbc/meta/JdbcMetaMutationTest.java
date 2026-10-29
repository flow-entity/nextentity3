package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.support.IgnoredAssociationItem;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import java.util.List;
import org.junit.jupiter.api.Test;

/// meta 的变异补杀用例：只补 `JdbcEntityRulesTest` / `JdbcMetaTest` 没覆盖到的分支，
/// 不重复既有断言（重复会被等价台账的双向比对当噪声）。
class JdbcMetaMutationTest {

    private final JdbcMeta meta = new JdbcMeta();

    // ---------- JdbcEntityRules.fields / rejectPropertyAnnotations ----------

    @Test
    void entityParentIsRejected() {
        assertThatThrownBy(() -> meta.entityType(MutationEntityChild.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("实体继承未支持: " + MutationEntityChild.class.getName());
    }

    @Test
    void mappedSuperclassChainStopsAtPlainParent() {
        JdbcEntityType<MutationMappedEntity> type = meta.entityType(MutationMappedEntity.class);

        assertThat(type.attributes()).extracting(JdbcEntityAttribute::name).containsExactly("mappedField", "id");
        assertThat(type.attribute("plainField")).isNull();
    }

    @Test
    void parameterGetterAndStaticGetterAreNotProperties() {
        JdbcEntityType<MutationOddGetters> type = meta.entityType(MutationOddGetters.class);

        assertThat(type.attributes()).extracting(JdbcEntityAttribute::name).containsExactly("id");
    }

    @Test
    void plainGetterWithoutMappingAnnotationIsNotAProperty() {
        JdbcEntityType<MutationPlainGetter> type = meta.entityType(MutationPlainGetter.class);

        assertThat(type.attributes()).extracting(JdbcEntityAttribute::name).containsExactly("id");
    }

    // ---------- JdbcEntityRules.rejectField / mapped ----------

    @Test
    void embeddedFieldWithNonEmbeddableTypeIsRejected() {
        assertThatThrownBy(() -> meta.entityType(MutationEmbeddedString.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("@Embedded 未支持: " + MutationEmbeddedString.class.getName() + ".address");
    }

    @Test
    void manyToOneGetterWithoutJoinColumnIsRejected() {
        assertThatThrownBy(() -> meta.entityType(MutationManyToOneGetter.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("只支持字段访问（注解须在字段上）: " + MutationManyToOneGetter.class.getName());
    }

    @Test
    void generatedValueGetterWithoutIdIsRejected() {
        assertThatThrownBy(() -> meta.entityType(MutationGeneratedGetter.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("只支持字段访问（注解须在字段上）: " + MutationGeneratedGetter.class.getName());
    }

    // ---------- JdbcColumns.idColumn（@JoinColumn(referencedColumnName)） ----------

    @Test
    void referencedColumnAcceptsIdFromMappedSuperclass() {
        JdbcEntityAttribute target = meta.entityType(MutationRefBaseId.class).attribute("target");

        assertThat(target.joinColumn()).isEqualTo("target_id");
    }

    @Test
    void referencedColumnAcceptsCustomIdColumnCaseInsensitively() {
        JdbcEntityAttribute target = meta.entityType(MutationRefCustomId.class).attribute("target");

        assertThat(target.joinColumn()).isEqualTo("target_id");
    }

    @Test
    void referencedColumnAcceptsEmptyValue() {
        JdbcEntityAttribute target = meta.entityType(MutationRefEmpty.class).attribute("target");

        assertThat(target.joinColumn()).isEqualTo("target_id");
    }

    @Test
    void referencedColumnWithoutTargetIdThrowsIllegalArgument() {
        assertThatThrownBy(() -> meta.entityType(MutationRefNoId.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("实体必须有且只有一个 @Id 字段: " + MutationNoIdTarget.class.getName());
    }

    @Test
    void referencedColumnPointingElsewhereIsRejected() {
        assertThatThrownBy(() -> meta.entityType(MutationRefWrong.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("@JoinColumn(referencedColumnName=name) 未支持: "
                        + MutationRefWrong.class.getName()
                        + ".target（目标 "
                        + MutationCustomIdTarget.class.getName()
                        + " 的 @Id 列是 tid）");
    }

    @Test
    void referencedColumnAcceptsIdWithEmptyColumnName() {
        JdbcEntityAttribute target =
                meta.entityType(MutationRefEmptyColumnName.class).attribute("target");

        assertThat(target.joinColumn()).isEqualTo("target_id");
    }

    @Test
    void referencedColumnFindsIdTwoLevelsUp() {
        JdbcEntityAttribute target =
                meta.entityType(MutationRefDeepBaseId.class).attribute("target");

        assertThat(target.joinColumn()).isEqualTo("target_id");
    }

    // ---------- JdbcEntityAttribute 的标量/关联槽位 ----------

    @Test
    void associationAttributeExposesNullScalarSlots() {
        JdbcEntityAttribute owner = meta.entityType(SnapshotAsset.class).attribute("owner");

        assertThat(owner.column()).isNull();
        assertThat(owner.joinColumn()).isEqualTo("owner_id");
        assertThat(owner.associationTarget()).isEqualTo(SnapshotOwner.class);
        assertThat(owner.isAssociation()).isTrue();
        assertThat(owner.isId()).isFalse();
        assertThat(owner.isVersion()).isFalse();
        assertThat(owner.isGenerated()).isFalse();
        assertThat(owner.enumStorage()).isEqualTo(EnumStorage.NONE);
    }

    @Test
    void scalarAttributeExposesNullAssociationSlots() {
        JdbcEntityType<SnapshotItem> type = meta.entityType(SnapshotItem.class);

        for (JdbcEntityAttribute attribute : type.attributes()) {
            if (attribute.isAssociation()) {
                continue;
            }
            assertThat(attribute.associationTarget()).isNull();
            assertThat(attribute.joinColumn()).isNull();
        }
        assertThat(type.id().isGenerated()).isFalse();
        assertThat(type.id().column()).isEqualTo("id");
    }

    @Test
    void ignoredAssociationAttributeHasNullJoinColumn() {
        JdbcEntityAttribute owners =
                meta.entityType(IgnoredAssociationItem.class).attribute("owners");

        assertThat(owners.isAssociation()).isTrue();
        assertThat(owners.joinColumn()).isNull();
        assertThat(owners.column()).isNull();
        assertThat(owners.associationTarget()).isEqualTo(List.class);
    }

    // ---------- JdbcEntityType.newInstance ----------

    @Test
    void newInstanceCreatesEntityThroughProtectedConstructor() {
        SnapshotItem item = meta.entityType(SnapshotItem.class).newInstance();

        assertThat(item).isNotNull();
        assertThat(item.id()).isNull();
    }

    @Test
    void entityWithProtectedConstructorCanBeInstantiated() {
        MutationProtectedCtor entity =
                meta.entityType(MutationProtectedCtor.class).newInstance();

        assertThat(entity).isNotNull();
    }

    // ---------- fixtures ----------

    @Entity
    static class MutationEntityParent {
        @Id
        private Long id;
    }

    @Entity
    static class MutationEntityChild extends MutationEntityParent {}

    static class MutationPlainMiddle {
        private String plainField;
    }

    @MappedSuperclass
    static class MutationMappedBase extends MutationPlainMiddle {
        private String mappedField;
    }

    @Entity
    static class MutationMappedEntity extends MutationMappedBase {
        @Id
        private Long id;
    }

    @Entity
    static class MutationOddGetters {
        @Id
        private Long id;

        @Column
        public String getName(String suffix) {
            return suffix;
        }

        @Column
        public static String getStatic() {
            return null;
        }
    }

    @Entity
    static class MutationPlainGetter {
        @Id
        private Long id;

        public String getLabel() {
            return "label";
        }
    }

    @Entity
    static class MutationEmbeddedString {
        @Id
        private Long id;

        @Embedded
        private String address;
    }

    @Entity
    static class MutationManyToOneGetter {
        @Id
        private Long id;

        @ManyToOne
        public SnapshotOwner getOwner() {
            return null;
        }
    }

    @Entity
    static class MutationGeneratedGetter {
        @Id
        private Long id;

        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getGenerated() {
            return null;
        }
    }

    @MappedSuperclass
    static class MutationIdBase {
        @Id
        private Long id;
    }

    @Entity
    static class MutationIdTarget extends MutationIdBase {
        private String name;
    }

    @Entity
    static class MutationRefBaseId {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "target_id", referencedColumnName = "id")
        private MutationIdTarget target;
    }

    @Entity
    static class MutationCustomIdTarget {
        @Id
        @Column(name = "tid")
        private Long id;
    }

    @Entity
    static class MutationRefCustomId {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "target_id", referencedColumnName = "TID")
        private MutationCustomIdTarget target;
    }

    @Entity
    static class MutationRefEmpty {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "target_id")
        private MutationIdTarget target;
    }

    static class MutationNoIdTarget {
        private Long id;
    }

    @Entity
    static class MutationRefNoId {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "target_id", referencedColumnName = "id")
        private MutationNoIdTarget target;
    }

    @Entity
    static class MutationRefWrong {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "target_id", referencedColumnName = "name")
        private MutationCustomIdTarget target;
    }

    @Entity
    static class MutationEmptyColumnNameTarget {
        @Id
        @Column(name = "")
        private Long id;
    }

    @Entity
    static class MutationRefEmptyColumnName {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "target_id", referencedColumnName = "id")
        private MutationEmptyColumnNameTarget target;
    }

    @MappedSuperclass
    static class MutationDeepIdBase {
        @Id
        private Long id;
    }

    static class MutationDeepPlainMiddle extends MutationDeepIdBase {}

    @Entity
    static class MutationDeepIdTarget extends MutationDeepPlainMiddle {
        private String name;
    }

    @Entity
    static class MutationRefDeepBaseId {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "target_id", referencedColumnName = "id")
        private MutationDeepIdTarget target;
    }

    @Entity
    static class MutationProtectedCtor {
        @Id
        private Long id;

        protected MutationProtectedCtor() {}
    }
}
