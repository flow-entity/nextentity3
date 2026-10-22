package io.github.nextentity.jpa.entity;

import jakarta.persistence.AttributeConverter;

/// 测试用值对象：模拟 point-mall 里 `@Convert` 字段的形状（业务类型 ↔ 数据库字符串）。
///
/// @param text 业务值
public record Note(String text) {

    /// `Note` ↔ `String` 的转换器，由 Hibernate 在读写时自动应用。
    public static final class Converter implements AttributeConverter<Note, String> {

        @Override
        public String convertToDatabaseColumn(Note attribute) {
            return attribute == null ? null : attribute.text();
        }

        @Override
        public Note convertToEntityAttribute(String dbData) {
            return dbData == null ? null : new Note(dbData);
        }
    }
}
