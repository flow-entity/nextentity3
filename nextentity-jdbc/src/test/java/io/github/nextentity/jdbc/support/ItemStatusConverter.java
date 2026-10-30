package io.github.nextentity.jdbc.support;

import jakarta.persistence.AttributeConverter;

/// `@Convert` 样本转换器：枚举 → name（数据库类型 = `String`）。
public final class ItemStatusConverter implements AttributeConverter<ItemStatus, String> {

    @Override
    public String convertToDatabaseColumn(ItemStatus status) {
        return status == null ? null : status.name();
    }

    @Override
    public ItemStatus convertToEntityAttribute(String value) {
        return value == null ? null : ItemStatus.valueOf(value);
    }
}
