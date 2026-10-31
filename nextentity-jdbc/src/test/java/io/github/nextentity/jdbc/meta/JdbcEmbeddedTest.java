package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.support.EmbeddedAddress;
import io.github.nextentity.jdbc.support.EmbeddedProfile;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.List;
import org.junit.jupiter.api.Test;

/// `@Embedded` 的扁平化规则与拒绝分支（见 USAGE §13.4.1）：嵌套 / 主键 / 版本 / 生成键 / 关联在扁平化层面表达不了。
class JdbcEmbeddedTest {

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void embeddedColumnsUseDefaultNamingAndAttributeOverride() {
        JdbcEntityType<EmbeddedProfile> type = meta.entityType(EmbeddedProfile.class);

        JdbcEntityAttribute address = type.attribute("address");
        assertThat(address.isEmbedded()).isTrue();
        assertThat(address.embeddedTarget()).isEqualTo(EmbeddedAddress.class);
        assertThat(address.embeddedColumns())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("city", "street");
        assertThat(address.embeddedColumn("city").column()).isEqualTo("town");
        assertThat(address.embeddedColumn("street").column()).isEqualTo("address_street");
        assertThat(address.embeddedColumn("zip")).isNull();
        assertThat(type.columns())
                .extracting(JdbcEntityAttribute::column)
                .containsExactly("id", "town", "address_street", "label");
    }

    @Test
    void nestedEmbeddedAnnotationIsRejected() {
        assertThatThrownBy(() -> meta.entityType(NestedEmbeddedOnlyEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌套 @Embedded 未支持: " + NestedEmbeddedOnlyEntity.class.getName() + ".address.value");
    }

    @Test
    void nestedEmbeddableTypeIsRejectedEvenWithoutTheAnnotation() {
        assertThatThrownBy(() -> meta.entityType(NestedEmbeddableTypeEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌套 @Embedded 未支持: " + NestedEmbeddableTypeEntity.class.getName() + ".address.inner");
    }

    @Test
    void transientFieldsInsideEmbeddableAreSkipped() {
        JdbcEntityType<EmbeddedWithTransientEntity> type = meta.entityType(EmbeddedWithTransientEntity.class);

        JdbcEntityAttribute address = type.attribute("address");
        assertThat(address.embeddedColumns())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("city");
        assertThat(type.columns()).extracting(JdbcEntityAttribute::column).containsExactly("id", "address_city");
    }

    @Test
    void attributeOverrideWithoutANameIsRejected() {
        assertThatThrownBy(() -> meta.entityType(OverrideWithoutNameEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("@AttributeOverride 缺少属性名: " + OverrideWithoutNameEntity.class.getName() + ".address");
    }

    @Test
    void nestedAttributeOverridePathIsRejectedAtTheBoundary() {
        assertThatThrownBy(() -> meta.entityType(OverrideNestedPathEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌套 @AttributeOverride 未支持: " + OverrideNestedPathEntity.class.getName()
                        + ".address.city.name");
    }

    @Test
    void attributeOverrideNameStartingWithDotIsRejected() {
        assertThatThrownBy(() -> meta.entityType(OverrideDotPrefixEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌套 @AttributeOverride 未支持: " + OverrideDotPrefixEntity.class.getName() + ".address..city");
    }

    @Test
    void quotedAttributeOverrideColumnIsRecordedAsQuoted() {
        JdbcEntityAttribute address =
                meta.entityType(QuotedOverrideEntity.class).attribute("address");

        assertThat(address.embeddedColumn("city").column()).isEqualTo("town");
        assertThat(address.embeddedColumn("city").isColumnQuoted()).isTrue();
    }

    @Test
    void associationInsideEmbeddableIsRejected() {
        assertThatThrownBy(() -> meta.entityType(OneToOneInsideEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌入属性内的关联未支持: " + OneToOneInsideEntity.class.getName() + ".value.owner");
        assertThatThrownBy(() -> meta.entityType(OneToManyInsideEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌入属性内的关联未支持: " + OneToManyInsideEntity.class.getName() + ".value.owners");
        assertThatThrownBy(() -> meta.entityType(ManyToManyInsideEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌入属性内的关联未支持: " + ManyToManyInsideEntity.class.getName() + ".value.tags");
        assertThatThrownBy(() -> meta.entityType(ElementCollectionInsideEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌入属性内的关联未支持: " + ElementCollectionInsideEntity.class.getName() + ".value.tags");
    }

    @Test
    void entityWithoutEmbeddedFieldHasNoEmbeddedTarget() {
        JdbcEntityAttribute name = meta.entityType(SnapshotItem.class).attribute("name");

        assertThat(name.isEmbedded()).isFalse();
        assertThat(name.embeddedTarget()).isNull();
        assertThat(name.embeddedColumns()).isEmpty();
    }

    @Test
    void embeddableSuperclassFieldsAreMapped() {
        JdbcEntityType<InheritedEmbeddedEntity> type = meta.entityType(InheritedEmbeddedEntity.class);

        assertThat(type.attribute("address").embeddedColumns())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("city", "street");
        assertThat(type.columns())
                .extracting(JdbcEntityAttribute::column)
                .containsExactly("id", "address_city", "address_street");
    }

    @Test
    void unknownAttributeOverrideNameIsRejected() {
        assertThatThrownBy(() -> meta.entityType(UnknownOverrideEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("未命中的 @AttributeOverride: " + UnknownOverrideEntity.class.getName() + ".address.town");
    }

    @Test
    void propertyAccessInsideEmbeddableIsRejected() {
        assertThatThrownBy(() -> meta.entityType(PropertyAccessEmbeddableEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("只支持字段访问（注解须在字段上）: " + PropertyAccessEmbeddable.class.getName());
    }

    @Test
    void classLevelPropertyAccessInsideEmbeddableIsRejected() {
        assertThatThrownBy(() -> meta.entityType(ClassLevelPropertyAccessEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("只支持字段访问（注解须在字段上）: " + ClassLevelPropertyAccessEmbeddable.class.getName());
    }

    @Test
    void embeddableExtendingEntityIsRejected() {
        assertThatThrownBy(() -> meta.entityType(EmbeddableExtendsEntityEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("实体继承未支持: " + EmbeddableExtendingAnEntity.class.getName());
    }

    @Test
    void shadowedEmbeddableFieldIsRejected() {
        assertThatThrownBy(() -> meta.entityType(ShadowedFieldEntity.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("嵌入类型内字段名重复（父类字段被子类遮蔽）: " + ShadowedFieldEntity.class.getName() + ".address.street");
    }

    @Test
    void plainSuperclassFieldsAreNotMapped() {
        JdbcEntityType<PlainSuperclassEntity> type = meta.entityType(PlainSuperclassEntity.class);

        assertThat(type.attribute("address").embeddedColumns())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("street");
        assertThat(type.columns()).extracting(JdbcEntityAttribute::column).containsExactly("id", "address_street");
    }

    @Test
    void multipleMappedSuperclassesAreCollectedFromBottomUp() {
        JdbcEntityType<DeepInheritedEntity> type = meta.entityType(DeepInheritedEntity.class);

        assertThat(type.attribute("address").embeddedColumns())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("city", "zip", "street");
        assertThat(type.columns())
                .extracting(JdbcEntityAttribute::column)
                .containsExactly("id", "address_city", "address_zip", "address_street");
    }

    // ---------------------------------------------------------------- 夹具

    @Entity
    @Table(name = "embedded_only_nested")
    static class NestedEmbeddedOnlyEntity {

        @Id
        private Long id;

        @Embedded
        private NestedEmbeddedOnlyAddress address;
    }

    @Embeddable
    static class NestedEmbeddedOnlyAddress {

        @Embedded
        private String value;
    }

    @Entity
    @Table(name = "embedded_nested_type")
    static class NestedEmbeddableTypeEntity {

        @Id
        private Long id;

        @Embedded
        private NestedEmbeddableTypeAddress address;
    }

    @Embeddable
    static class NestedEmbeddableTypeAddress {

        private NestedEmbeddableTypeInner inner;
    }

    @Embeddable
    static class NestedEmbeddableTypeInner {

        private String city;
    }

    @Entity
    @Table(name = "embedded_with_transient")
    static class EmbeddedWithTransientEntity {

        @Id
        private Long id;

        @Embedded
        private EmbeddedWithTransientAddress address;
    }

    @Embeddable
    static class EmbeddedWithTransientAddress {

        private String city;

        @Transient
        private String ignored;
    }

    @Entity
    @Table(name = "embedded_override_without_name")
    static class OverrideWithoutNameEntity {

        @Id
        private Long id;

        @Embedded
        @AttributeOverride(name = "", column = @Column(name = "town"))
        private EmbeddedAddressOnly address;
    }

    @Entity
    @Table(name = "embedded_override_nested_path")
    static class OverrideNestedPathEntity {

        @Id
        private Long id;

        @Embedded
        @AttributeOverride(name = "city.name", column = @Column(name = "town"))
        private EmbeddedAddressOnly address;
    }

    @Entity
    @Table(name = "embedded_quoted_override")
    static class QuotedOverrideEntity {

        @Id
        private Long id;

        @Embedded
        @AttributeOverride(name = "city", column = @Column(name = "`town`"))
        private EmbeddedAddressOnly address;
    }

    @Embeddable
    static class EmbeddedAddressOnly {

        private String city;
    }

    @Entity
    @Table(name = "embedded_one_to_one")
    static class OneToOneInsideEntity {

        @Id
        private Long id;

        @Embedded
        private OneToOneInsideValue value;
    }

    @Embeddable
    static class OneToOneInsideValue {

        @OneToOne
        private SnapshotOwner owner;
    }

    @Entity
    @Table(name = "embedded_one_to_many")
    static class OneToManyInsideEntity {

        @Id
        private Long id;

        @Embedded
        private OneToManyInsideValue value;
    }

    @Embeddable
    static class OneToManyInsideValue {

        @OneToMany
        private List<SnapshotOwner> owners;
    }

    @Entity
    @Table(name = "embedded_many_to_many")
    static class ManyToManyInsideEntity {

        @Id
        private Long id;

        @Embedded
        private ManyToManyInsideValue value;
    }

    @Embeddable
    static class ManyToManyInsideValue {

        @ManyToMany
        private List<SnapshotOwner> tags;
    }

    @Entity
    @Table(name = "embedded_element_collection")
    static class ElementCollectionInsideEntity {

        @Id
        private Long id;

        @Embedded
        private ElementCollectionInsideValue value;
    }

    @Embeddable
    static class ElementCollectionInsideValue {

        @ElementCollection
        private List<String> tags;
    }

    @Entity
    @Table(name = "override_dot_prefix")
    static class OverrideDotPrefixEntity {

        @Id
        Long id;

        @Embedded
        @AttributeOverride(name = ".city", column = @Column(name = "town"))
        EmbeddedAddress address;
    }

    @Entity
    @Table(name = "embedded_superclass")
    static class InheritedEmbeddedEntity {

        @Id
        private Long id;

        @Embedded
        private InheritedEmbeddableAddress address;
    }

    @MappedSuperclass
    static class InheritedEmbeddableBase {

        private String city;
    }

    @Embeddable
    static class InheritedEmbeddableAddress extends InheritedEmbeddableBase {

        private String street;
    }

    @Entity
    @Table(name = "embedded_unknown_override")
    static class UnknownOverrideEntity {

        @Id
        private Long id;

        @Embedded
        @AttributeOverride(name = "town", column = @Column(name = "city_x"))
        private EmbeddedAddressOnly address;
    }

    @Entity
    @Table(name = "embedded_property_access")
    static class PropertyAccessEmbeddableEntity {

        @Id
        private Long id;

        @Embedded
        private PropertyAccessEmbeddable address;
    }

    @Embeddable
    static class PropertyAccessEmbeddable {

        private String city;

        @Column(name = "town")
        public String getCity() {
            return city;
        }
    }

    @Entity
    @Table(name = "embedded_class_level_property_access")
    static class ClassLevelPropertyAccessEntity {

        @Id
        private Long id;

        @Embedded
        private ClassLevelPropertyAccessEmbeddable address;
    }

    @Embeddable
    @Access(AccessType.PROPERTY)
    static class ClassLevelPropertyAccessEmbeddable {

        private String city;

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }

    @Entity
    @Table(name = "embeddable_extends_entity")
    static class EmbeddableExtendsEntityEntity {

        @Id
        private Long id;

        @Embedded
        private EmbeddableExtendingAnEntity address;
    }

    @Entity
    @Table(name = "embedded_extended_base")
    static class ExtendedBaseEntity {

        @Id
        private Long id;
    }

    @Embeddable
    static class EmbeddableExtendingAnEntity extends ExtendedBaseEntity {

        private String street;
    }

    @Entity
    @Table(name = "embedded_plain_superclass")
    static class PlainSuperclassEntity {

        @Id
        private Long id;

        @Embedded
        private PlainSuperclassAddress address;
    }

    static class PlainSuperclassBase {

        private String city;
    }

    @Embeddable
    static class PlainSuperclassAddress extends PlainSuperclassBase {

        private String street;
    }

    @Entity
    @Table(name = "embedded_deep_superclass")
    static class DeepInheritedEntity {

        @Id
        private Long id;

        @Embedded
        private DeepInheritedAddress address;
    }

    @MappedSuperclass
    static class DeepInheritedTop {

        private String city;
    }

    @MappedSuperclass
    static class DeepInheritedMiddle extends DeepInheritedTop {

        private String zip;
    }

    @Embeddable
    static class DeepInheritedAddress extends DeepInheritedMiddle {

        private String street;
    }

    @Entity
    @Table(name = "embedded_shadowed")
    static class ShadowedFieldEntity {

        @Id
        private Long id;

        @Embedded
        private ShadowedAddress address;
    }

    @MappedSuperclass
    static class ShadowedBase {

        private String street;
    }

    @Embeddable
    static class ShadowedAddress extends ShadowedBase {

        private String street;
    }
}
