package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.QuotedOrder;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.util.List;
import org.junit.jupiter.api.Test;

/// `meta` 的映射规则与拒绝文案（§4.1 / §4.3 #1–#14、#25、#26 的 meta 期部分）：
/// 表名三级回退、父类遍历、字段注解拒绝、关联元数据、枚举存储、id/version 唯一性。
class JdbcEntityRulesTest {

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void tableNamePrefersTableThenEntityNameThenSimpleName() {
        assertThat(meta.entityType(Tabled.class).table()).isEqualTo("rules_table");
        assertThat(meta.entityType(NamedOnly.class).table()).isEqualTo("rules_named");
        assertThat(meta.entityType(PlainEntity.class).table()).isEqualTo("PlainEntity");
    }

    @Test
    void emptyTableNameFallsBackToEntityName() {
        assertThat(meta.entityType(EmptyTableName.class).table()).isEqualTo("EmptyTableName");
    }

    @Test
    void mappedSuperclassFieldsComeBeforeSubclassFields() {
        JdbcEntityType<WithMappedBase> type = meta.entityType(WithMappedBase.class);

        assertThat(type.attributes()).extracting(JdbcEntityAttribute::name).containsExactly("id", "name", "flag");
        assertThat(type.id().name()).isEqualTo("id");
    }

    @Test
    void unmappedParentFieldsAreNotPersistent() {
        JdbcEntityType<WithPlainParent> type = meta.entityType(WithPlainParent.class);

        assertThat(type.attributes()).extracting(JdbcEntityAttribute::name).containsExactly("id");
        assertThat(type.attribute("inheritedField")).isNull();
    }

    @Test
    void getterAnnotationInMappedSuperclassIsRejected() {
        assertThatThrownBy(() -> meta.entityType(GetterAnnotatedBase.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("只支持字段访问（注解须在字段上）: " + GetterAnnotatedBase.class.getName());
    }

    @Test
    void propertyAccessIsRejected() {
        assertThatThrownBy(() -> meta.entityType(PropertyAccess.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("只支持字段访问（注解须在字段上）: " + PropertyAccess.class.getName());
    }

    @Test
    void explicitFieldAccessIsAccepted() {
        assertThat(meta.entityType(FieldAccess.class).attributes())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("id");
    }

    @Test
    void everyMappingAnnotationOnGetterIsRejected() {
        List<Class<?>> getters = List.of(
                GetterId.class,
                GetterVersion.class,
                GetterColumn.class,
                GetterEnumerated.class,
                GetterManyToOne.class,
                GetterOneToOne.class,
                GetterOneToMany.class,
                GetterManyToMany.class,
                GetterElementCollection.class,
                GetterEmbedded.class,
                GetterEmbeddedId.class,
                GetterConvert.class,
                GetterJoinColumn.class,
                GetterGeneratedValue.class);

        for (Class<?> getter : getters) {
            assertThatThrownBy(() -> meta.entityType(getter))
                    .as(getter.getSimpleName())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("只支持字段访问（注解须在字段上）: " + getter.getName());
        }
    }

    @Test
    void isPrefixedGetterIsRejected() {
        assertThatThrownBy(() -> meta.entityType(IsGetter.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("只支持字段访问（注解须在字段上）: " + IsGetter.class.getName());
    }

    @Test
    void staticGetterWithoutMappingIsIgnored() {
        assertThat(meta.entityType(StaticGetter.class).attributes())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("id");
    }

    @Test
    void getterWithParametersIsNotAProperty() {
        assertThat(meta.entityType(ParameterGetter.class).attributes())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("id");
    }

    @Test
    void annotatedMethodWithoutGetterPrefixIsNotAProperty() {
        assertThat(meta.entityType(NonGetterMethod.class).attributes())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("id");
    }

    @Test
    void plainGetterWithoutAnnotationIsNotAProperty() {
        assertThat(meta.entityType(PlainGetter.class).attributes())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("id");
    }

    @Test
    void staticFinalAndTransientFieldsAreExcluded() {
        JdbcEntityType<ExcludedFields> type = meta.entityType(ExcludedFields.class);

        assertThat(type.attributes()).extracting(JdbcEntityAttribute::name).containsExactly("id", "name");
        assertThat(type.attribute("transientField")).isNull();
    }

    @Test
    void quotedReferencedColumnNameIsRecorded() {
        assertThat(meta.entityType(QuotedOrder.class).attribute("owner").isReferencedColumnQuoted())
                .isTrue();
        assertThat(meta.entityType(SnapshotAsset.class).attribute("owner").isReferencedColumnQuoted())
                .isFalse();
    }

    @Test
    void quotedJoinColumnWithoutReferencedNameIsNotRecordedAsQuoted() {
        JdbcEntityAttribute owner =
                meta.entityType(QuotedJoinColumnEntity.class).attribute("owner");

        assertThat(owner.isJoinColumnQuoted()).isTrue();
        assertThat(owner.isReferencedColumnQuoted()).isFalse();
    }

    @Test
    void oneToOneWithMappedByIsIgnored() {
        JdbcEntityAttribute child = meta.entityType(MappedByOwner.class).attribute("child");

        assertThat(child.isAssociation()).isTrue();
        assertThat(child.joinColumn()).isNull();
    }

    @Test
    void oneToOneWithJoinColumnIsRenderedAndWritable() {
        JdbcEntityType<OneToOneIgnored> type = meta.entityType(OneToOneIgnored.class);

        assertThat(type.attributes()).extracting(JdbcEntityAttribute::name).containsExactly("id", "owner");
        JdbcEntityAttribute owner = type.attribute("owner");
        assertThat(owner).isNotNull();
        assertThat(owner.isAssociation()).isTrue();
        assertThat(owner.joinColumn()).isEqualTo("owner_id");
        assertThat(owner.isJoinColumnInsertable()).isTrue();
        assertThat(owner.associationTarget()).isEqualTo(SnapshotOwner.class);
    }

    @Test
    void collectionAssociationsAreIgnored() {
        for (Class<?> ignored :
                List.of(OneToManyIgnored.class, ManyToManyIgnored.class, ElementCollectionIgnored.class)) {
            JdbcEntityType<?> type = meta.entityType(ignored);
            assertThat(type.attributes()).as(ignored.getSimpleName()).hasSize(1);
            assertThat(type.attribute("targets")).as(ignored.getSimpleName()).isNotNull();
            assertThat(type.attribute("targets").joinColumn()).isNull();
        }
    }

    @Test
    void manyToOneCarriesJoinColumnAndTarget() {
        JdbcEntityAttribute owner = meta.entityType(ManagedAsset.class).attribute("owner");

        assertThat(owner.isAssociation()).isTrue();
        assertThat(owner.associationTarget()).isEqualTo(SnapshotOwner.class);
        assertThat(owner.joinColumn()).isEqualTo("owner_id");
        assertThat(owner.enumStorage()).isEqualTo(EnumStorage.NONE);
        assertThat(meta.entityType(ManagedAsset.class).attributes())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("id", "owner");
    }

    @Test
    void manyToOneWithoutJoinColumnUsesJpaDefaultName() {
        JdbcEntityAttribute owner = meta.entityType(MissingJoinColumn.class).attribute("owner");

        assertThat(owner.joinColumn()).isEqualTo("owner_id");
    }

    @Test
    void manyToOneWithEmptyJoinColumnNameUsesJpaDefaultName() {
        JdbcEntityAttribute owner = meta.entityType(EmptyJoinColumn.class).attribute("owner");

        assertThat(owner.joinColumn()).isEqualTo("owner_id");
    }

    @Test
    void referencedColumnMatchingTargetIdIsAccepted() {
        JdbcEntityAttribute owner = meta.entityType(ReferencedIdAsset.class).attribute("owner");

        assertThat(owner.isAssociation()).isTrue();
        assertThat(owner.joinColumn()).isEqualTo("owner_id");
    }

    @Test
    void emptyReferencedColumnIsAccepted() {
        JdbcEntityAttribute owner = meta.entityType(EmptyReferencedAsset.class).attribute("owner");

        assertThat(owner.joinColumn()).isEqualTo("owner_id");
    }

    @Test
    void referencedColumnMatchingCustomIdColumnIsAcceptedIgnoringCase() {
        JdbcEntityAttribute owner = meta.entityType(CustomReferencedAsset.class).attribute("owner");

        assertThat(owner.associationTarget()).isEqualTo(CustomIdOwner.class);
    }

    @Test
    void referencedColumnOtherThanTargetIdIsRejected() {
        assertThatThrownBy(() -> meta.entityType(NameReferencedAsset.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("@JoinColumn(referencedColumnName=name) 未支持: " + NameReferencedAsset.class.getName()
                        + ".owner（目标 " + SnapshotOwner.class.getName() + " 的 @Id 列是 id）");
    }

    @Test
    void duplicateVersionIsRejected() {
        assertThatThrownBy(() -> meta.entityType(DuplicateVersion.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(JdbcEntityRules.VERSION_UNSUPPORTED + DuplicateVersion.class.getName() + ".second");
    }

    @Test
    void duplicateIdIsRejected() {
        assertThatThrownBy(() -> meta.entityType(DuplicateId.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("实体必须有且只有一个 @Id 字段: " + DuplicateId.class.getName());
    }

    @Test
    void primitiveIntVersionIsAccepted() {
        JdbcEntityType<IntVersioned> type = meta.entityType(IntVersioned.class);

        assertThat(type.version()).isNotNull();
        assertThat(type.version().type()).isEqualTo(int.class);
    }

    @Test
    void integerVersionIsAccepted() {
        assertThat(meta.entityType(IntegerVersioned.class).version().type()).isEqualTo(Integer.class);
    }

    @Test
    void generatedFlagOnlyAppliesToIdentifier() {
        JdbcEntityType<GeneratedFlag> type = meta.entityType(GeneratedFlag.class);

        assertThat(type.id().isGenerated()).isTrue();
        assertThat(type.attribute("counter").isGenerated()).isFalse();
    }

    @Test
    void customColumnNameAndEmptyColumnNameFallBackToField() {
        JdbcEntityType<Columns> type = meta.entityType(Columns.class);

        assertThat(type.attribute("name").column()).isEqualTo("item_name");
        assertThat(type.attribute("fallback").column()).isEqualTo("fallback");
        assertThat(type.column("name")).isEqualTo("item_name");
        assertThat(type.column("missing")).isNull();
    }

    @Test
    void enumStorageFollowsEnumeratedAnnotation() {
        assertThat(meta.entityType(StringEnum.class).attribute("status").enumStorage())
                .isEqualTo(EnumStorage.NAME);
        assertThat(meta.entityType(OrdinalEnum.class).attribute("status").enumStorage())
                .isEqualTo(EnumStorage.ORDINAL);
        assertThat(meta.entityType(DefaultEnum.class).attribute("status").enumStorage())
                .isEqualTo(EnumStorage.ORDINAL);
        assertThat(meta.entityType(Columns.class).attribute("name").enumStorage())
                .isEqualTo(EnumStorage.NONE);
    }

    @Test
    void attributesByNamesAreSeparateViews() {
        JdbcEntityType<Columns> type = meta.entityType(Columns.class);

        assertThatThrownBy(() -> type.attributes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatCode(type::newInstance).doesNotThrowAnyException();
    }

    @Test
    void mappedSuperclassWithInheritanceAnnotationIsRejected() {
        assertThatThrownBy(() -> meta.entityType(Inherited.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("实体继承未支持: " + Inherited.class.getName());
    }

    @Test
    void idClassIsRejected() {
        assertThatThrownBy(() -> meta.entityType(WithIdClass.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("复合主键未支持: " + WithIdClass.class.getName());
    }

    @Test
    void embeddedFieldsAreFlattenedIntoColumns() {
        JdbcEntityType<EmbeddedField> type = meta.entityType(EmbeddedField.class);

        JdbcEntityAttribute address = type.attribute("address");
        assertThat(address.isEmbedded()).isTrue();
        assertThat(address.embeddedColumns())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("city");
        assertThat(type.columns()).extracting(JdbcEntityAttribute::column).containsExactly("id", "address_city");
    }

    @Test
    void embeddableTypeWithoutEmbeddedAnnotationIsAlsoFlattened() {
        JdbcEntityAttribute address = meta.entityType(EmbeddableTypeField.class).attribute("address");

        assertThat(address.isEmbedded()).isTrue();
        assertThat(address.embeddedColumn("city").column()).isEqualTo("address_city");
    }

    @Test
    void convertFieldCarriesAttributeConverter() {
        JdbcEntityAttribute payload = meta.entityType(ConvertedField.class).attribute("payload");

        assertThat(payload.converter()).isNotNull();
        assertThat(payload.databaseType()).isEqualTo(String.class);
    }

    @Test
    void embeddedIdIsStillRejectedAsCompositeKey() {
        assertThatThrownBy(() -> meta.entityType(EmbeddedIdField.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("复合主键未支持: " + EmbeddedIdField.class.getName());
    }

    @Entity
    @Table(name = "rules_table")
    static class Tabled {
        @Id
        private Long id;
    }

    @Entity(name = "rules_named")
    static class NamedOnly {
        @Id
        private Long id;
    }

    @Entity
    static class PlainEntity {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "")
    static class EmptyTableName {
        @Id
        private Long id;
    }

    @MappedSuperclass
    static class MappedBase {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "rules_mapped")
    static class WithMappedBase extends MappedBase {
        private String name;
        private Integer flag;
    }

    static class PlainParent {
        private String inheritedField;
    }

    @Entity
    @Table(name = "rules_plain_parent")
    static class WithPlainParent extends PlainParent {
        @Id
        private Long id;
    }

    @MappedSuperclass
    static class AnnotatedGetterSuperclass {
        @Column
        public String getName() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_base")
    static class GetterAnnotatedBase extends AnnotatedGetterSuperclass {
        @Id
        private Long id;
    }

    @Entity
    @Access(AccessType.PROPERTY)
    @Table(name = "rules_property_access")
    static class PropertyAccess {
        @Id
        private Long id;
    }

    @Entity
    @Access(AccessType.FIELD)
    @Table(name = "rules_field_access")
    static class FieldAccess {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "rules_getter_id")
    static class GetterId {
        @Id
        public Long getId() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_version")
    static class GetterVersion {
        @Version
        public Integer getVersion() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_column")
    static class GetterColumn {
        @Column
        public String getName() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_enumerated")
    static class GetterEnumerated {
        @Enumerated(EnumType.STRING)
        public ItemStatus getStatus() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_many_to_one")
    static class GetterManyToOne {
        @ManyToOne
        @JoinColumn(name = "owner_id")
        public SnapshotOwner getOwner() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_one_to_one")
    static class GetterOneToOne {
        @OneToOne
        public SnapshotOwner getOwner() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_one_to_many")
    static class GetterOneToMany {
        @OneToMany
        public List<SnapshotOwner> getOwners() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_many_to_many")
    static class GetterManyToMany {
        @ManyToMany
        public List<SnapshotOwner> getOwners() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_element_collection")
    static class GetterElementCollection {
        @ElementCollection
        public List<String> getTags() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_embedded")
    static class GetterEmbedded {
        @Embedded
        public String getAddress() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_embedded_id")
    static class GetterEmbeddedId {
        @EmbeddedId
        public String getId() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_convert")
    static class GetterConvert {
        @Convert(converter = NoopConverter.class)
        public String getPayload() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_join_column")
    static class GetterJoinColumn {
        @JoinColumn(name = "owner_id")
        public Long getOwnerId() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_getter_generated")
    static class GetterGeneratedValue {
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_is_getter")
    static class IsGetter {
        @Column
        public boolean isActive() {
            return false;
        }
    }

    @Entity
    @Table(name = "rules_static_getter")
    static class StaticGetter {
        @Id
        private Long id;

        @Column
        public static String getStatic() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_parameter_getter")
    static class ParameterGetter {
        @Id
        private Long id;

        @Column
        public String getWithParam(int index) {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_non_getter_method")
    static class NonGetterMethod {
        @Id
        private Long id;

        @Column
        public String annotated() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_plain_getter")
    static class PlainGetter {
        @Id
        private Long id;

        public String plain() {
            return null;
        }
    }

    @Entity
    @Table(name = "rules_excluded_fields")
    static class ExcludedFields {
        @Id
        private Long id;

        private String name;

        @Transient
        private String transientField;

        static String STATIC;

        final String constant = "x";
    }

    @Entity
    @Table(name = "rules_one_to_one")
    static class OneToOneIgnored {
        @Id
        private Long id;

        @OneToOne
        @JoinColumn(name = "owner_id")
        private SnapshotOwner owner;
    }

    @Entity
    @Table(name = "rules_one_to_many")
    static class OneToManyIgnored {
        @Id
        private Long id;

        @OneToMany
        private List<SnapshotOwner> targets;
    }

    @Entity
    @Table(name = "rules_many_to_many")
    static class ManyToManyIgnored {
        @Id
        private Long id;

        @ManyToMany
        private List<SnapshotOwner> targets;
    }

    @Entity
    @Table(name = "rules_element_collection")
    static class ElementCollectionIgnored {
        @Id
        private Long id;

        @ElementCollection
        private List<SnapshotOwner> targets;
    }

    @Entity
    @Table(name = "rules_managed_asset")
    static class ManagedAsset {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "owner_id")
        private SnapshotOwner owner;
    }

    @Entity
    @Table(name = "rules_missing_join_column")
    static class MissingJoinColumn {
        @Id
        private Long id;

        @ManyToOne
        private SnapshotOwner owner;
    }

    @Entity
    @Table(name = "rules_empty_join_column")
    static class EmptyJoinColumn {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "")
        private SnapshotOwner owner;
    }

    @Entity
    @Table(name = "rules_referenced_id_asset")
    static class ReferencedIdAsset {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "owner_id", referencedColumnName = "id")
        private SnapshotOwner owner;
    }

    @Entity
    @Table(name = "rules_empty_referenced_asset")
    static class EmptyReferencedAsset {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "owner_id", referencedColumnName = "")
        private SnapshotOwner owner;
    }

    @Entity
    @Table(name = "rules_custom_id_owner")
    static class CustomIdOwner {
        @Id
        @Column(name = "owner_code")
        private Long code;
    }

    @Entity
    @Table(name = "rules_custom_referenced_asset")
    static class CustomReferencedAsset {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "owner_code", referencedColumnName = "OWNER_CODE")
        private CustomIdOwner owner;
    }

    @Entity
    @Table(name = "rules_name_referenced_asset")
    static class NameReferencedAsset {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "owner_name", referencedColumnName = "name")
        private SnapshotOwner owner;
    }

    @Entity
    @Table(name = "rules_duplicate_version")
    static class DuplicateVersion {
        @Id
        private Long id;

        @Version
        private Integer first;

        @Version
        private Integer second;
    }

    @Entity
    @Table(name = "rules_duplicate_id")
    static class DuplicateId {
        @Id
        private Long id;

        @Id
        private Long other;
    }

    @Entity
    @Table(name = "rules_int_version")
    static class IntVersioned {
        @Id
        private Long id;

        @Version
        private int version;
    }

    @Entity
    @Table(name = "rules_integer_version")
    static class IntegerVersioned {
        @Id
        private Long id;

        @Version
        private Integer version;
    }

    @Entity
    @Table(name = "rules_generated_flag")
    static class GeneratedFlag {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @GeneratedValue
        private Long counter;
    }

    @Entity
    @Table(name = "rules_columns")
    static class Columns {
        @Id
        private Long id;

        @Column(name = "item_name")
        private String name;

        @Column(name = "")
        private String fallback;
    }

    @Entity
    @Table(name = "rules_string_enum")
    static class StringEnum {
        @Id
        private Long id;

        @Enumerated(EnumType.STRING)
        private ItemStatus status;
    }

    @Entity
    @Table(name = "rules_ordinal_enum")
    static class OrdinalEnum {
        @Id
        private Long id;

        @Enumerated(EnumType.ORDINAL)
        private ItemStatus status;
    }

    @Entity
    @Table(name = "rules_default_enum")
    static class DefaultEnum {
        @Id
        private Long id;

        private ItemStatus status;
    }

    @Entity
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @Table(name = "rules_inherited")
    static class Inherited {
        @Id
        private Long id;
    }

    @Entity
    @IdClass(CompositeKey.class)
    @Table(name = "rules_id_class")
    static class WithIdClass {
        @Id
        private Long id;

        @Id
        private Long other;
    }

    static class CompositeKey {
        private Long id;
        private Long other;
    }

    @Entity
    @Table(name = "rules_embedded_field")
    static class EmbeddedField {
        @Id
        private Long id;

        @Embedded
        private Address address;
    }

    @Entity
    @Table(name = "rules_embeddable_type")
    static class EmbeddableTypeField {
        @Id
        private Long id;

        private Address address;
    }

    @Embeddable
    static class Address {
        private String city;
    }

    @Entity
    @Table(name = "rules_convert")
    static class ConvertedField {
        @Id
        private Long id;

        @Convert(converter = NoopConverter.class)
        private String payload;
    }

    @Entity
    @Table(name = "rules_embedded_id")
    static class EmbeddedIdField {
        @EmbeddedId
        private CompositeKey key;
    }

    static class NoopConverter implements AttributeConverter<String, String> {

        @Override
        public String convertToDatabaseColumn(String attribute) {
            return attribute;
        }

        @Override
        public String convertToEntityAttribute(String dbData) {
            return dbData;
        }
    }

    @Test
    void quotedNamesContainingReservedDelimitersAreRejected() {
        for (Class<?> fixture : List.of(
                DoubleQuoteTable.class, BacktickTable.class, OpeningBracketTable.class, ClosingBracketTable.class)) {
            assertThatThrownBy(() -> meta.entityType(fixture))
                    .as(fixture.getSimpleName())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("标识符名不能含引用定界符（\" ` [ ]）")
                    .hasMessageContaining(fixture.getName());
        }
    }

    @Test
    void quotedColumnNameContainingDelimiterIsRejected() {
        assertThatThrownBy(() -> meta.entityType(QuotedDelimiterColumn.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("标识符名不能含引用定界符（\" ` [ ]）")
                .hasMessageContaining(QuotedDelimiterColumn.class.getName() + ".desc");
    }

    @Entity
    @Table(name = "`ord\"er`")
    static class DoubleQuoteTable {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "`ord`er`")
    static class BacktickTable {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "`ord[er`")
    static class OpeningBracketTable {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "`ord]er`")
    static class ClosingBracketTable {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "`order`")
    static class QuotedDelimiterColumn {
        @Id
        private Long id;

        @Column(name = "`de\"sc`")
        private String desc;
    }

    @Test
    void delimitersAtNameBoundariesAreRejected() {
        assertThatThrownBy(() -> meta.entityType(DelimiterFirstTable.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("标识符名不能含引用定界符（\" ` [ ]）")
                .hasMessageContaining(DelimiterFirstTable.class.getName());

        assertThatThrownBy(() -> meta.entityType(DelimiterLastTable.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("标识符名不能含引用定界符（\" ` [ ]）")
                .hasMessageContaining(DelimiterLastTable.class.getName());
    }

    @Entity
    @Table(name = "`\"order`")
    static class DelimiterFirstTable {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "`order]`")
    static class DelimiterLastTable {
        @Id
        private Long id;
    }

    @Test
    void delimitersAtTheStartOfEveryNameAreRejected() {
        for (Class<?> fixture : List.of(
                DelimiterFirstTable.class,
                BacktickFirstTable.class,
                OpeningBracketFirstTable.class,
                ClosingBracketFirstTable.class)) {
            assertThatThrownBy(() -> meta.entityType(fixture))
                    .as(fixture.getSimpleName())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("标识符名不能含引用定界符（\" ` [ ]）");
        }
    }

    @Entity
    @Table(name = "``order`")
    static class BacktickFirstTable {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "`[order`")
    static class OpeningBracketFirstTable {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "`]order`")
    static class ClosingBracketFirstTable {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "`order\"`")
    static class QuoteLastTable {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "`order``")
    static class BacktickLastTable {
        @Id
        private Long id;
    }

    @Entity
    @Table(name = "`order[`")
    static class OpeningBracketLastTable {
        @Id
        private Long id;
    }

    @Test
    void delimitersAtTheEndOfEveryNameAreRejected() {
        for (Class<?> fixture : List.of(
                DelimiterLastTable.class,
                QuoteLastTable.class,
                BacktickLastTable.class,
                OpeningBracketLastTable.class)) {
            assertThatThrownBy(() -> meta.entityType(fixture))
                    .as(fixture.getSimpleName())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("标识符名不能含引用定界符（\" ` [ ]）");
        }
    }

    @Entity
    static class MappedByOwner {
        @Id
        Long id;

        @OneToOne(mappedBy = "owner")
        MappedByChild child;
    }

    @Entity
    static class MappedByChild {
        @Id
        Long id;

        @OneToOne
        @JoinColumn(name = "owner_id")
        MappedByOwner owner;
    }

    @Entity
    static class QuotedJoinColumnEntity {

        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "`owner_id`")
        SnapshotOwner owner;
    }
}
