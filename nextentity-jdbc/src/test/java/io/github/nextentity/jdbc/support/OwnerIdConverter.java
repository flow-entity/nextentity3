package io.github.nextentity.jdbc.support;

import jakarta.persistence.AttributeConverter;

/// `@Id` 上的 `@Convert` 样本转换器：主键 `Long` ↔ 数据库列 `String`。
public final class OwnerIdConverter implements AttributeConverter<Long, String> {

    @Override
    public String convertToDatabaseColumn(Long id) {
        return id == null ? null : id.toString();
    }

    @Override
    public Long convertToEntityAttribute(String value) {
        return value == null ? null : Long.valueOf(value);
    }
}
