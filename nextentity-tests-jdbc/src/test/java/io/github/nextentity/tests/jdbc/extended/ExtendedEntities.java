package io.github.nextentity.tests.jdbc.extended;

import io.github.nextentity.testing.ItemStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/// 3.1.0 能力面的落库夹具：扩展列类型、`@Embedded` 扁平列、`@Convert`、`@Column(insertable=false)`、
/// `short` 生成键与 `short` 版本。列名显式写死，避免各方言的大小写折叠差异。
final class ExtendedEntities {

    private ExtendedEntities() {}

    @Entity
    @Table(name = "extended_item")
    static class ExtendedItem {

        @Id
        Long id;

        @Column(name = "flag_col")
        Boolean flag;

        @Column(name = "ratio_col")
        Double ratio;

        @Column(name = "weight_col")
        Float weight;

        @Column(name = "count_col")
        Short count;

        @Column(name = "price_col")
        BigDecimal price;

        @Column(name = "serial_col")
        BigInteger serial;

        @Column(name = "birth_date")
        LocalDate birthDate;

        @Column(name = "created_at")
        LocalDateTime createdAt;

        @Column(name = "wake_time")
        LocalTime wakeTime;

        @Column(name = "synced_at")
        Instant syncedAt;

        @Column(name = "sql_date")
        Date sqlDate;

        @Column(name = "sql_time")
        Time sqlTime;

        @Column(name = "sql_timestamp")
        Timestamp sqlTimestamp;

        protected ExtendedItem() {}

        ExtendedItem(
                Long id,
                Boolean flag,
                Double ratio,
                Float weight,
                Short count,
                BigDecimal price,
                BigInteger serial,
                LocalDate birthDate,
                LocalDateTime createdAt,
                LocalTime wakeTime,
                Instant syncedAt,
                Date sqlDate,
                Time sqlTime,
                Timestamp sqlTimestamp) {
            this.id = id;
            this.flag = flag;
            this.ratio = ratio;
            this.weight = weight;
            this.count = count;
            this.price = price;
            this.serial = serial;
            this.birthDate = birthDate;
            this.createdAt = createdAt;
            this.wakeTime = wakeTime;
            this.syncedAt = syncedAt;
            this.sqlDate = sqlDate;
            this.sqlTime = sqlTime;
            this.sqlTimestamp = sqlTimestamp;
        }
    }

    @Embeddable
    static class Address {

        String city;

        String street;

        protected Address() {}

        Address(String city, String street) {
            this.city = city;
            this.street = street;
        }
    }

    @Entity
    @Table(name = "embedded_profile")
    static class EmbeddedProfile {

        @Id
        Long id;

        @Embedded
        @AttributeOverride(name = "city", column = @Column(name = "town"))
        Address address;

        String label;

        protected EmbeddedProfile() {}

        EmbeddedProfile(Long id, Address address, String label) {
            this.id = id;
            this.address = address;
            this.label = label;
        }
    }

    @Converter
    static class StatusConverter implements AttributeConverter<ItemStatus, String> {

        @Override
        public String convertToDatabaseColumn(ItemStatus attribute) {
            return attribute == null ? null : attribute.name();
        }

        @Override
        public ItemStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : ItemStatus.valueOf(dbData);
        }
    }

    @Entity
    @Table(name = "converted_item")
    static class ConvertedStatusItem {

        @Id
        Long id;

        @Convert(converter = StatusConverter.class)
        ItemStatus status;

        String note;

        protected ConvertedStatusItem() {}

        ConvertedStatusItem(Long id, ItemStatus status, String note) {
            this.id = id;
            this.status = status;
            this.note = note;
        }
    }

    @Entity
    @Table(name = "flag_column_item")
    static class FlagColumnItem {

        @Id
        Long id;

        String name;

        @Column(name = "created_by", insertable = false)
        String createdBy;

        protected FlagColumnItem() {}

        FlagColumnItem(Long id, String name, String createdBy) {
            this.id = id;
            this.name = name;
            this.createdBy = createdBy;
        }
    }

    @Entity
    @Table(name = "short_versioned_item")
    static class ShortVersionedItem {

        @Id
        Long id;

        String name;

        @Version
        short version;

        protected ShortVersionedItem() {}

        ShortVersionedItem(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Entity
    @Table(name = "short_generated_item")
    static class ShortGeneratedItem {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Short id;

        String name;

        protected ShortGeneratedItem() {}

        ShortGeneratedItem(String name) {
            this.name = name;
        }
    }
}
