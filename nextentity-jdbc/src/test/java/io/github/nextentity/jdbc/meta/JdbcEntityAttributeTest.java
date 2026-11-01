package io.github.nextentity.jdbc.meta;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nextentity.jdbc.support.DisabledConversionItem;
import io.github.nextentity.jdbc.support.EmbeddedProfile;
import io.github.nextentity.jdbc.support.IgnoredAssociationItem;
import io.github.nextentity.jdbc.support.OrdinalItem;
import io.github.nextentity.jdbc.support.PackagePrivateConvertedItem;
import io.github.nextentity.jdbc.support.SnapshotAsset;
import io.github.nextentity.jdbc.support.SnapshotItem;
import io.github.nextentity.jdbc.support.SnapshotOwner;
import io.github.nextentity.jdbc.support.SuperclassConvertedItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

/// `JdbcEntityAttribute` 的读写语义：标量直写、嵌入列经容器中转、关联 @Id、`@Convert` 与 JOIN 列标志。
class JdbcEntityAttributeTest {

    private final JdbcMeta meta = new JdbcMeta();

    @Test
    void scalarAttributeReadsAndWritesTheFieldDirectly() {
        JdbcEntityType<SnapshotItem> type = meta.entityType(SnapshotItem.class);
        JdbcEntityAttribute name = type.attribute("name");
        SnapshotItem item = type.newInstance();

        assertThat(name.get(item)).isNull();
        name.set(item, "banana");
        assertThat(name.get(item)).isEqualTo("banana");

        assertThat(name.column()).isEqualTo("name");
        assertThat(name.isColumnQuoted()).isFalse();
        assertThat(name.isColumnInsertable()).isTrue();
        assertThat(name.isColumnUpdatable()).isTrue();
        assertThat(name.isEmbedded()).isFalse();
        assertThat(name.embeddedTarget()).isNull();
        assertThat(name.embeddedColumns()).isEmpty();
        assertThat(name.converter()).isNull();
        assertThat(name.databaseType()).isNull();
        assertThat(name.isAssociation()).isFalse();
        assertThat(name.isJoinColumnInsertable()).isFalse();
        assertThat(name.isJoinColumnUpdatable()).isFalse();
    }

    @Test
    void embeddedColumnWritesCreateTheContainerOnDemand() {
        JdbcEntityType<EmbeddedProfile> type = meta.entityType(EmbeddedProfile.class);
        JdbcEntityAttribute city = type.attribute("address").embeddedColumn("city");
        JdbcEntityAttribute street = type.attribute("address").embeddedColumn("street");
        EmbeddedProfile profile = type.newInstance();

        assertThat(city.get(profile)).isNull();
        city.set(profile, null);
        assertThat(profile.address()).isNull();

        city.set(profile, "Yantai");
        assertThat(city.get(profile)).isEqualTo("Yantai");
        assertThat(profile.address()).isNotNull();

        street.set(profile, "No. 1");
        assertThat(street.get(profile)).isEqualTo("No. 1");
        assertThat(profile.address().city()).isEqualTo("Yantai");
        assertThat(profile.address().street()).isEqualTo("No. 1");
    }

    @Test
    void embeddedAttributeItselfHasNoColumn() {
        JdbcEntityAttribute address = meta.entityType(EmbeddedProfile.class).attribute("address");

        assertThat(address.column()).isNull();
        assertThat(address.isColumnInsertable()).isFalse();
        assertThat(address.isColumnUpdatable()).isFalse();
        assertThat(address.isId()).isFalse();
        assertThat(address.isVersion()).isFalse();
        assertThat(address.isGenerated()).isFalse();
        assertThat(address.isAssociation()).isFalse();
        assertThat(address.enumStorage()).isEqualTo(EnumStorage.NONE);
        assertThat(address.converter()).isNull();
        assertThat(address.databaseType()).isNull();
    }

    @Test
    void ignoredAssociationHasNoIdFieldSoAssociatedIdStaysNull() {
        JdbcEntityAttribute owners =
                meta.entityType(IgnoredAssociationItem.class).attribute("owners");

        assertThat(owners.isAssociation()).isTrue();
        assertThat(owners.joinColumn()).isNull();
        assertThat(owners.associatedId(null)).isNull();
    }

    @Test
    void associationIdComesFromTheReferencedIdField() {
        JdbcEntityAttribute owner = meta.entityType(SnapshotAsset.class).attribute("owner");

        assertThat(owner.associatedId(null)).isNull();
        assertThat(owner.associatedId(new SnapshotOwner(7L, "o"))).isEqualTo(7L);
        assertThat(owner.isAssociation()).isTrue();
        assertThat(owner.joinColumn()).isEqualTo("owner_id");
        assertThat(owner.isJoinColumnInsertable()).isTrue();
        assertThat(owner.isJoinColumnUpdatable()).isTrue();
        assertThat(owner.column()).isNull();
    }

    @Test
    void joinColumnFlagsAreReadFromTheAnnotation() {
        JdbcEntityType<AssociationFlagsEntity> type = meta.entityType(AssociationFlagsEntity.class);

        JdbcEntityAttribute skipped = type.attribute("skipped");
        JdbcEntityAttribute locked = type.attribute("locked");
        assertThat(skipped.joinColumn()).isEqualTo("skipped_id");
        assertThat(skipped.isJoinColumnInsertable()).isFalse();
        assertThat(skipped.isJoinColumnUpdatable()).isTrue();
        assertThat(locked.joinColumn()).isEqualTo("locked_id");
        assertThat(locked.isJoinColumnInsertable()).isTrue();
        assertThat(locked.isJoinColumnUpdatable()).isFalse();
    }

    /// 写侧可写性判据（同列双映射的查重就只看这一个值）：**任一写标志为真即可写**——两条写路径各看一个标志
    /// （`insert` 看 `insertable`、`SET` 看 `updatable`），只有两者都为假时该列才真的不进任何写语句。
    /// 标量看 `@Column`、关联外键看 `@JoinColumn`，两条分支分别钉住：任一分支写错都会让
    /// 「只读副本 + 可写副本」的同列双映射被判成冲突（合法映射被拒），或让真冲突被放过。
    @Test
    void isWritableIsTrueWhenEitherWriteFlagIsOn() {
        JdbcEntityType<WriteFlagEntity> scalars = meta.entityType(WriteFlagEntity.class);
        assertThat(scalars.attribute("insertOnly").isWritable()).isTrue();
        assertThat(scalars.attribute("updateOnly").isWritable()).isTrue();
        assertThat(scalars.attribute("writable").isWritable()).isTrue();
        assertThat(scalars.attribute("readOnly").isWritable()).isFalse();

        JdbcEntityType<AssociationFlagsEntity> associations = meta.entityType(AssociationFlagsEntity.class);
        assertThat(associations.attribute("skipped").isWritable()).isTrue();
        assertThat(associations.attribute("locked").isWritable()).isTrue();
        assertThat(associations.attribute("readOnly").isWritable()).isFalse();
    }

    @Test
    void converterOnASuperclassIsResolvedThroughTheHierarchy() {
        JdbcEntityAttribute status =
                meta.entityType(SuperclassConvertedItem.class).attribute("status");

        assertThat(status.converter()).isNotNull();
        assertThat(status.databaseType()).isEqualTo(String.class);
        assertThat(status.enumStorage()).isEqualTo(EnumStorage.NONE);
    }

    @Test
    void disabledConversionIsTreatedAsNoConverter() {
        JdbcEntityAttribute payload =
                meta.entityType(DisabledConversionItem.class).attribute("payload");

        assertThat(payload.converter()).isNull();
        assertThat(payload.databaseType()).isNull();
        assertThat(payload.enumStorage()).isEqualTo(EnumStorage.NONE);
    }

    @Test
    void packagePrivateConverterIsInstantiated() {
        JdbcEntityAttribute status =
                meta.entityType(PackagePrivateConvertedItem.class).attribute("status");

        assertThat(status.converter()).isNotNull();
        assertThat(status.databaseType()).isEqualTo(String.class);
    }

    @Test
    void enumAttributeReportsItsStorageForm() {
        JdbcEntityType<OrdinalItem> type = meta.entityType(OrdinalItem.class);

        assertThat(type.attribute("status").enumStorage()).isEqualTo(EnumStorage.ORDINAL);
        assertThat(type.attribute("status").converter()).isNull();
        assertThat(type.attribute("id").enumStorage()).isEqualTo(EnumStorage.NONE);
    }

    @Entity
    @Table(name = "association_flags")
    static class AssociationFlagsEntity {

        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "skipped_id", insertable = false)
        private SnapshotOwner skipped;

        @ManyToOne
        @JoinColumn(name = "locked_id", updatable = false)
        private SnapshotOwner locked;

        @ManyToOne
        @JoinColumn(name = "read_only_id", insertable = false, updatable = false)
        private SnapshotOwner readOnly;
    }

    @Entity
    @Table(name = "write_flags")
    static class WriteFlagEntity {

        @Id
        private Long id;

        @Column(insertable = false)
        private String insertOnly;

        @Column(updatable = false)
        private String updateOnly;

        private String writable;

        @Column(insertable = false, updatable = false)
        private String readOnly;
    }
}
