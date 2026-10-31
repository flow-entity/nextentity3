package io.github.nextentity.tests.jdbc.phasec;

import jakarta.persistence.AttributeConverter;

/// Phase C 夹具：**非恒等**的主键列映射（`9L ↔ "owner-9"`）。
///
/// 刻意不用 `toString` 型转换器：那类映射对 H2 的隐式数值→字符转换恰好一致（落库 `"9"` 就是转换结果），
/// 写路径没走列映射也会绿，测不到缺陷。
public final class OwnerIdColumnConverter implements AttributeConverter<Long, String> {

    private static final String PREFIX = "owner-";

    @Override
    public String convertToDatabaseColumn(Long id) {
        return id == null ? null : PREFIX + id;
    }

    @Override
    public Long convertToEntityAttribute(String value) {
        return value == null ? null : Long.valueOf(value.substring(PREFIX.length()));
    }
}
