package io.github.nextentity.jdbc.support;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// `@Convert` 转换器拒绝 null 时，列值为 SQL NULL 必须直接读成 null，不得把 null 交给转换器。
@Entity
@Table(name = "strict_converted_item")
public class StrictConvertedItem {

    @Id
    private Long id;

    @Convert(converter = StrictStatusConverter.class)
    private ItemStatus status;

    public StrictConvertedItem() {}

    public StrictConvertedItem(Long id, ItemStatus status) {
        this.id = id;
        this.status = status;
    }

    public Long id() {
        return id;
    }

    public ItemStatus status() {
        return status;
    }

    static class StrictStatusConverter implements AttributeConverter<ItemStatus, String> {

        @Override
        public String convertToDatabaseColumn(ItemStatus attribute) {
            if (attribute == null) {
                throw new IllegalArgumentException("status 不能为 null");
            }
            return attribute.name();
        }

        @Override
        public ItemStatus convertToEntityAttribute(String dbData) {
            if (dbData == null) {
                throw new IllegalArgumentException("数据库值不能为 null");
            }
            return ItemStatus.valueOf(dbData);
        }
    }
}
