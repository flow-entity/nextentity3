package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.jdbc.support.GeneratedItem;
import io.github.nextentity.jdbc.support.IgnoredAssociationItem;
import io.github.nextentity.jdbc.support.IntegerVersionedItem;
import io.github.nextentity.jdbc.support.ItemStatus;
import io.github.nextentity.jdbc.support.OrdinalItem;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import io.github.nextentity.jdbc.support.VersionedItem;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 元数据正向矩阵（§2.1 / §7.2）：表名、属性声明序、列名、主键、版本、生成键、枚举存储、关联属性的保留方式。
class JdbcMetaTest {

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void scalarColumnsKeepDeclarationOrder() {
        JdbcEntityType<SnapshotItem> type = meta.entityType(SnapshotItem.class);
        assertThat(type.type()).isEqualTo(SnapshotItem.class);
        assertThat(type.table()).isEqualTo("item");
        assertThat(type.attributes())
                .extracting(JdbcEntityAttribute::name)
                .containsExactly("id", "name", "score", "score2", "note", "status");
        assertThat(type.attributes())
                .extracting(JdbcEntityAttribute::column)
                .containsExactly("id", "name", "score", "score2", "note", "status");
        assertThat(type.id().name()).isEqualTo("id");
        assertThat(type.id().type()).isEqualTo(Long.class);
        assertThat(type.version()).isNull();
        assertThat(type.newInstance()).isInstanceOf(SnapshotItem.class);
    }

    @Test
    void enumStorageFollowsEnumeratedType() {
        assertThat(meta.entityType(SnapshotItem.class).attribute("status").enumStorage())
                .isEqualTo(EnumStorage.NAME);
        assertThat(meta.entityType(OrdinalItem.class).attribute("status").enumStorage())
                .isEqualTo(EnumStorage.ORDINAL);
        assertThat(meta.entityType(SnapshotItem.class).attribute("name").enumStorage())
                .isEqualTo(EnumStorage.NONE);
    }

    @Test
    void generatedKeyAndVersionAreMarked() {
        assertThat(meta.entityType(GeneratedItem.class).id().isGenerated()).isTrue();
        assertThat(meta.entityType(SnapshotItem.class).id().isGenerated()).isFalse();

        JdbcEntityAttribute version = meta.entityType(VersionedItem.class).version();
        assertThat(version).isNotNull();
        assertThat(version.name()).isEqualTo("version");
        assertThat(version.type()).isEqualTo(long.class);
        assertThat(version.isVersion()).isTrue();

        assertThat(meta.entityType(IntegerVersionedItem.class).version().type()).isEqualTo(Integer.class);
    }

    @Test
    void manyToOneAssociationIsExposedByMeta() {
        // §2.1 + 决策 10：attributes() = 全部持久属性（标量 + @ManyToOne，声明序）；§4.3 #9 只忽略集合/一对一。
        JdbcEntityType<SnapshotAsset> type = meta.entityType(SnapshotAsset.class);
        assertThat(type.attributes()).extracting(JdbcEntityAttribute::name).containsExactly("id", "name", "owner");

        JdbcEntityAttribute owner = type.attribute("owner");
        assertThat(owner).isNotNull();
        assertThat(owner.isAssociation()).isTrue();
        assertThat(owner.joinColumn()).isEqualTo("owner_id");
        assertThat(owner.associationTarget()).isEqualTo(SnapshotOwner.class);
        assertThat(owner.isId()).isFalse();
        assertThat(owner.isVersion()).isFalse();
        assertThat(owner.enumStorage()).isEqualTo(EnumStorage.NONE);
    }

    @Test
    void collectionAssociationIsIgnoredButResolvable() {
        // §4.3 #9：集合/一对一关联是唯一「静默忽略」项——不进 attributes()，但 attribute(name) 可解析
        JdbcEntityType<IgnoredAssociationItem> type = meta.entityType(IgnoredAssociationItem.class);
        assertThat(type.attributes()).extracting(JdbcEntityAttribute::name).containsExactly("id");

        JdbcEntityAttribute owners = type.attribute("owners");
        assertThat(owners).isNotNull();
        assertThat(owners.isAssociation()).isTrue();
        assertThat(owners.joinColumn()).isNull();
        assertThat(owners.associationTarget()).isEqualTo(List.class);
    }

    @Test
    void unknownAttributeNameYieldsNullSoTheCompilerCanReportIt() {
        // 「未知属性: <type>.<field>」由编译层抛（§4.3 #15 同款），meta 只返回 null。
        JdbcEntityType<SnapshotItem> type = meta.entityType(SnapshotItem.class);
        assertThat(type.attribute("missing")).isNull();
        assertThat(type.column("missing")).isNull();
    }

    @Test
    void entityTypeLookupIsCached() {
        assertThat(meta.entityType(SnapshotItem.class)).isSameAs(meta.entityType(SnapshotItem.class));
    }

    @Test
    void attributeLookupByName() {
        JdbcEntityType<SnapshotItem> type = meta.entityType(SnapshotItem.class);
        assertThat(type.attribute("score2").type()).isEqualTo(Integer.class);
        assertThat(type.column("status")).isEqualTo("status");
        assertThat(type.attributes())
                .extracting(JdbcEntityAttribute::type)
                .containsExactly(
                        Long.class, String.class, Integer.class, Integer.class, String.class, ItemStatus.class);
        assertThat(type.attributes()).hasSize(6);
    }

    @Test
    void newInstanceFailureIsWrappedAsIllegalState() {
        JdbcEntityType<ThrowingConstructorEntity> type = meta.entityType(ThrowingConstructorEntity.class);

        assertThatThrownBy(type::newInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实例化实体失败")
                .hasMessageContaining(ThrowingConstructorEntity.class.getName());
    }

    @Test
    void scansIntegerVersionAndPrimitiveIntGeneratedKey() {
        JdbcEntityType<IntegerVersionedScan> versioned = meta.entityType(IntegerVersionedScan.class);
        assertThat(versioned.version()).isNotNull();
        assertThat(versioned.version().type()).isEqualTo(Integer.class);

        JdbcEntityType<PrimitiveIntGenerated> generated = meta.entityType(PrimitiveIntGenerated.class);
        assertThat(generated.id().isGenerated()).isTrue();
    }

    @Test
    void resolvesIdDeclaredInMappedSuperclass() {
        assertThat(meta.entityType(SuperclassIdEntity.class).id().name()).isEqualTo("id");
    }

    /// 无参构造器抛异常：`newInstance()` 的失败路径（承载体只在用例里用）。
    @Entity
    @Table(name = "meta_throwing_constructor")
    static class ThrowingConstructorEntity {

        @Id
        private Long id;

        ThrowingConstructorEntity() {
            throw new IllegalStateException("boom");
        }
    }

    @Entity
    @Table(name = "integer_versioned_scan")
    static class IntegerVersionedScan {

        @Id
        private Long id;

        @Version
        private Integer version;
    }

    @Entity
    @Table(name = "primitive_int_generated")
    static class PrimitiveIntGenerated {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private int id;
    }

    @MappedSuperclass
    static class BaseWithId {

        @Id
        private Long id;
    }

    @Entity
    @Table(name = "superclass_id_entity")
    static class SuperclassIdEntity extends BaseWithId {

        private String name;
    }
}
