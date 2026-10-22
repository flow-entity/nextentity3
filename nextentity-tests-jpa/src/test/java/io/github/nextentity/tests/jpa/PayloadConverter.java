package io.github.nextentity.tests.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/// 存储形态与内存形态不同的转换器：模拟 point-mall `EncryptedJsonConverter` 那类 `@Convert` 字段。
///
/// 落库写 `v1:<原文>`，读回落库值时去掉前缀——用来证明「绑定值/落库值是转换后形态」。
@Converter
public class PayloadConverter implements AttributeConverter<String, String> {

    private static final String PREFIX = "v1:";

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : PREFIX + attribute;
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : dbData.substring(PREFIX.length());
    }
}
